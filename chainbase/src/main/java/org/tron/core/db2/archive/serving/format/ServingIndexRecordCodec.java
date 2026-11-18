package org.tron.core.db2.archive.serving.format;

import com.google.common.hash.Hashing;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import org.tron.common.math.StrictMathWrapper;
import org.tron.core.db2.archive.ArchivePersistenceException;
import org.tron.core.db2.archive.ArchiveStoreScope;

/** Pure persistent serving records; callers retain their own value objects and Store ownership. */
public final class ServingIndexRecordCodec {
  private ServingIndexRecordCodec() {}

  private static final int MAGIC = 0x534b4947; // SKIG
  public static final short VERSION = 4;
  public static final short EXACT_VERSION = 5;
  private static final int MAX_MANIFEST_SIZE = 1024 * 1024;
  public static final int INLINE_EPOCH_LIMIT = 4;
  /**
   * Geth-aligned rolling budget for a page's encoded height bytes. The page header, restart
   * offset table and CRC live outside it, so a finished page value is larger than this.
   */
  public static final int PAGE_DATA_BUDGET = 4096;
  /**
   * Entries per page are bounded by bytes, not count: appends are admitted while
   * {@code data + Long.BYTES <= PAGE_DATA_BUDGET}, so a final nine-byte varint can bring
   * the data area to {@code PAGE_DATA_BUDGET + 1} bytes, and every entry costs at least one.
   */
  static final int MAX_PAGE_ENTRIES = PAGE_DATA_BUDGET + 1;
  public static final byte INLINE = 1;
  public static final byte PAGED = 2;
  public static final byte PAGED_DIRECTORY = 3;
  public static final int RESTART_INTERVAL = 256;
  private static final byte COMPRESSED_PAGE = 1;
  private static final byte DIRECTORY_VERSION = 1;

  public static byte[] encodeKeyMeta(KeyMetadataView meta) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      output.writeByte(meta.mode());
      output.writeLong(meta.count());
      output.writeLong(meta.firstEpoch());
      output.writeLong(meta.lastEpoch());
      if (meta.mode() == INLINE) {
        output.writeInt(meta.inlineEpochCount());
        for (int index = 0; index < meta.inlineEpochCount(); index++) {
          output.writeLong(meta.inlineEpoch(index));
        }
      }
      output.flush();
      return withChecksum(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Unexpected exact serving metadata encoding failure", impossible);
    }
  }

  public static <T> T decodeKeyMeta(byte[] encoded, KeyMetadataFactory<T> factory) {
    byte[] payload = checkedPayload(encoded, "exact serving key metadata");
    try {
      DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
      byte mode = input.readByte();
      long count = input.readLong();
      long first = input.readLong();
      long last = input.readLong();
      if (count <= 0 || first < 0 || last < first
          || mode != INLINE && mode != PAGED && mode != PAGED_DIRECTORY) {
        throw new ArchivePersistenceException("Invalid exact serving key metadata");
      }
      long[] inline = null;
      if (mode == INLINE) {
        int size = input.readInt();
        if (size != count || size <= 0 || size > INLINE_EPOCH_LIMIT) {
          throw new ArchivePersistenceException("Invalid inline serving metadata");
        }
        inline = new long[size];
        for (int i = 0; i < size; i++) {
          inline[i] = input.readLong();
        }
        requireStrictEpochs(asList(inline), Long.MIN_VALUE);
        if (inline[0] != first || inline[size - 1] != last) {
          throw new ArchivePersistenceException("Inline serving metadata endpoints differ");
        }
      } else if (count <= INLINE_EPOCH_LIMIT
          || count > (long) Integer.MAX_VALUE * MAX_PAGE_ENTRIES) {
        throw new ArchivePersistenceException("Invalid paged serving metadata");
      }
      if (input.available() != 0) {
        throw new ArchivePersistenceException("Exact serving metadata payload mismatch");
      }
      return factory.create(mode, count, first, last, inline);
    } catch (IOException failure) {
      throw new ArchivePersistenceException("Exact serving metadata is truncated", failure);
    }
  }

  /**
   * Delta-varint page, Geth-aligned layout: fixed header first, then the encoded heights,
   * then the restart offset table at the tail. The segment count is derived from the header
   * ({@code (count - 1) / restartInterval + 1}) instead of being stored separately.
   */
  public static byte[] encodeEpochPage(long[] epochs) {
    return encodeEpochPage(epochs, RESTART_INTERVAL);
  }

  /**
   * Encodes one byte-budgeted page; arrays whose encoded heights exceed the page budget are
   * rejected instead of being silently split. Callers that page a key use {@link PageWriter}.
   */
  public static byte[] encodeEpochPage(long[] epochs, int restartInterval) {
    if (epochs.length == 0) {
      throw new ArchivePersistenceException("Invalid compressed serving page dimensions");
    }
    requireStrictEpochs(asList(epochs), Long.MIN_VALUE);
    PageWriter writer = new PageWriter(restartInterval);
    for (long epoch : epochs) {
      writer.append(epoch);
    }
    return writer.finish();
  }

  public static long[] decodeEpochPage(byte[] encoded) {
    PageLayout page = parsePageLayout(encoded);
    int[] offsets = restartOffsets(page);
    long[] epochs = new long[page.count];
    for (int segment = 0; segment < page.segmentCount; segment++) {
      long[] values = decodeSegment(page, offsets, segment);
      System.arraycopy(values, 0, epochs, segment * page.restartInterval, values.length);
    }
    requireStrictEpochs(asList(epochs), Long.MIN_VALUE);
    return epochs;
  }

  /** Page header entry count without decoding, the counterpart of Geth's desc.entries. */
  public static int pageEpochCount(byte[] encoded) {
    return parsePageLayout(encoded).count;
  }

  /** First height: the restart-zero anchor, decodable in place at data offset zero. */
  public static long firstEpochOf(byte[] encoded) {
    PageLayout page = parsePageLayout(encoded);
    return readUvarint(ByteBuffer.wrap(page.payload, page.dataOffset,
        page.payload.length - page.dataOffset));
  }

  /** Last height: decodes only the final restart segment, the counterpart of sectionLast. */
  public static long lastEpochOf(byte[] encoded) {
    PageLayout page = parsePageLayout(encoded);
    int[] offsets = restartOffsets(page);
    long[] values = decodeSegment(page, offsets, page.segmentCount - 1);
    return values[values.length - 1];
  }

  /**
   * Geth seekGT alignment: binary-searches the restart anchors by decoding only each anchor's
   * absolute height, then scans a single segment. Returns the first height greater than
   * {@code target}, or empty when the whole page ends at or below it.
   */
  public static OptionalLong firstEpochGreaterThan(byte[] encoded, long target) {
    PageLayout page = parsePageLayout(encoded);
    int[] offsets = restartOffsets(page);
    int low = 0;
    int high = page.segmentCount;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (anchorEpoch(page, offsets, middle) <= target) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    int candidate = low == page.segmentCount
        ? page.segmentCount - 1 : StrictMathWrapper.max(low - 1, 0);
    long[] values = decodeSegment(page, offsets, candidate);
    for (long value : values) {
      if (value > target) {
        return OptionalLong.of(value);
      }
    }
    if (candidate + 1 < page.segmentCount) {
      return OptionalLong.of(anchorEpoch(page, offsets, candidate + 1));
    }
    return OptionalLong.empty();
  }

  /**
   * Geth-aligned page writer: pages roll on the byte estimate {@code data + Long.BYTES >
   * PAGE_DATA_BUDGET} checked before every append, so page boundaries depend only on the
   * appended heights, never on how they were batched across commits. Reopening a tail page
   * keeps its encoded height bytes verbatim; {@code entries} and {@code maxEpoch} (Geth's
   * in-memory desc fields) come from the atomically stored descriptor instead of a decode.
   */
  public static final class PageWriter {
    private final int restartInterval;
    private final ByteArrayOutputStream heightBytes = new ByteArrayOutputStream();
    private final List<Integer> segmentOffsets = new ArrayList<>();
    private int count;
    private long lastEpoch;

    public PageWriter() {
      this(RESTART_INTERVAL);
    }

    public PageWriter(int restartInterval) {
      if (restartInterval <= 0 || restartInterval > MAX_PAGE_ENTRIES) {
        throw new ArchivePersistenceException("Invalid serving restart interval");
      }
      this.restartInterval = restartInterval;
    }

    /** Reopens a tail page without decoding its heights; descriptor fields are trusted. */
    public PageWriter(byte[] encoded, PageDescriptor descriptor) {
      PageLayout page = parsePageLayout(encoded);
      if (page.count != descriptor.entries()) {
        throw new ArchivePersistenceException("Serving tail page differs from metadata");
      }
      restartInterval = page.restartInterval;
      int[] offsets = restartOffsets(page);
      for (int segment = 0; segment < page.segmentCount; segment++) {
        segmentOffsets.add(offsets[segment]);
      }
      heightBytes.write(page.payload, page.dataOffset, page.dataLength);
      count = descriptor.entries();
      lastEpoch = descriptor.maxEpoch();
    }

    public boolean isFull() {
      return heightBytes.size() + Long.BYTES > PAGE_DATA_BUDGET;
    }

    public void append(long epoch) {
      if (epoch < 0 || count > 0 && epoch <= lastEpoch) {
        throw new ArchivePersistenceException("Serving append is out of order");
      }
      if (isFull()) {
        throw new ArchivePersistenceException("Serving page byte budget is exhausted");
      }
      if (count % restartInterval == 0) {
        segmentOffsets.add(heightBytes.size());
        writeUvarint(heightBytes, epoch);
      } else {
        writeUvarint(heightBytes, epoch - lastEpoch);
      }
      lastEpoch = epoch;
      count++;
    }

    public PageDescriptor descriptor(int pageId) {
      return new PageDescriptor(lastEpoch, count, pageId);
    }

    public byte[] finish() {
      if (count == 0) {
        throw new ArchivePersistenceException("Empty serving page");
      }
      byte[] encodedHeights = heightBytes.toByteArray();
      ByteBuffer payload = ByteBuffer.allocate(
          7 + encodedHeights.length + segmentOffsets.size() * Short.BYTES);
      payload.put(COMPRESSED_PAGE).putInt(count).putShort((short) restartInterval)
          .put(encodedHeights);
      for (int offset : segmentOffsets) {
        payload.putShort((short) offset);
      }
      return withChecksum(payload.array());
    }
  }

  /** Structural page view shared by full decode, append, seek and partial reads. */
  private static final class PageLayout {
    final byte[] payload;
    final int count;
    final int restartInterval;
    final int segmentCount;
    final int dataOffset;
    final int dataLength;
    final int offsetsOffset;

    PageLayout(byte[] payload, int count, int restartInterval, int segmentCount,
        int dataOffset, int dataLength, int offsetsOffset) {
      this.payload = payload;
      this.count = count;
      this.restartInterval = restartInterval;
      this.segmentCount = segmentCount;
      this.dataOffset = dataOffset;
      this.dataLength = dataLength;
      this.offsetsOffset = offsetsOffset;
    }
  }

  private static PageLayout parsePageLayout(byte[] encoded) {
    byte[] payload = checkedPayload(encoded, "exact serving epoch page");
    if (payload.length < 7 + Short.BYTES || payload[0] != COMPRESSED_PAGE) {
      throw new ArchivePersistenceException("Invalid or truncated compressed serving page");
    }
    int count = ByteBuffer.wrap(payload, 1, Integer.BYTES).getInt();
    int restartInterval = Short.toUnsignedInt(ByteBuffer.wrap(payload, 5, Short.BYTES).getShort());
    if (count <= 0 || count > MAX_PAGE_ENTRIES || restartInterval == 0
        || restartInterval > MAX_PAGE_ENTRIES) {
      throw new ArchivePersistenceException("Invalid compressed serving page header");
    }
    int segmentCount = (count - 1) / restartInterval + 1;
    int offsetsOffset = payload.length - segmentCount * Short.BYTES;
    if (offsetsOffset < 7 + count || offsetsOffset - 7 > MAX_PAGE_ENTRIES) {
      throw new ArchivePersistenceException("Invalid compressed serving page header");
    }
    return new PageLayout(payload, count, restartInterval, segmentCount,
        7, offsetsOffset - 7, offsetsOffset);
  }

  private static int[] restartOffsets(PageLayout page) {
    int[] offsets = new int[page.segmentCount + 1];
    for (int index = 0; index < page.segmentCount; index++) {
      offsets[index] = Short.toUnsignedInt(ByteBuffer.wrap(page.payload,
          page.offsetsOffset + index * Short.BYTES, Short.BYTES).getShort());
    }
    offsets[page.segmentCount] = page.dataLength;
    if (offsets[0] != 0) {
      throw new ArchivePersistenceException("Invalid serving restart origin");
    }
    for (int index = 0; index < page.segmentCount; index++) {
      if (offsets[index] >= offsets[index + 1]) {
        throw new ArchivePersistenceException("Invalid serving restart offsets");
      }
    }
    return offsets;
  }

  private static long anchorEpoch(PageLayout page, int[] offsets, int segment) {
    return readUvarint(ByteBuffer.wrap(page.payload, page.dataOffset + offsets[segment],
        page.payload.length - page.dataOffset - offsets[segment]));
  }

  private static long[] decodeSegment(PageLayout page, int[] offsets, int segment) {
    int start = segment * page.restartInterval;
    int end = StrictMathWrapper.min(start + page.restartInterval, page.count);
    ByteBuffer segmentBytes = ByteBuffer.wrap(page.payload, page.dataOffset + offsets[segment],
        offsets[segment + 1] - offsets[segment]).slice();
    long[] epochs = new long[end - start];
    epochs[0] = readUvarint(segmentBytes);
    for (int index = 1; index < epochs.length; index++) {
      epochs[index] = addDelta(epochs[index - 1], readUvarint(segmentBytes));
    }
    if (segmentBytes.hasRemaining()) {
      throw new ArchivePersistenceException("Serving restart has trailing bytes");
    }
    return epochs;
  }

  /** Actual dimensions of a byte-budgeted page, not values derived from the key's count. */
  public static final class PageDescriptor {
    private final long maxEpoch;
    private final int entries;
    private final int pageId;

    public PageDescriptor(long maxEpoch, int entries, int pageId) {
      this.maxEpoch = maxEpoch;
      this.entries = entries;
      this.pageId = pageId;
    }

    public long maxEpoch() {
      return maxEpoch;
    }

    public int entries() {
      return entries;
    }

    public int pageId() {
      return pageId;
    }
  }

  /**
   * Geth-aligned fixed-size page directory: one 14-byte descriptor per page
   * ({@code pageMax:8 + pageEntries:2 + pageId:4}). It exists for every paged key from its
   * first page, because byte-budgeted page dimensions cannot be derived from KeyMeta.
   */
  public static byte[] encodePageDirectory(List<PageDescriptor> pages) {
    if (pages.isEmpty() || pages.size() > (Integer.MAX_VALUE - 9) / 14) {
      throw new ArchivePersistenceException("Invalid serving page directory dimensions");
    }
    ByteBuffer payload = ByteBuffer.allocate(5 + pages.size() * 14);
    payload.put(DIRECTORY_VERSION).putInt(pages.size());
    long previous = -1;
    for (int index = 0; index < pages.size(); index++) {
      PageDescriptor page = pages.get(index);
      requireDescriptor(page, index, previous);
      payload.putLong(page.maxEpoch()).putShort((short) page.entries()).putInt(page.pageId());
      previous = page.maxEpoch();
    }
    return withChecksum(payload.array());
  }

  /** Validates length before allocation, and actual entry totals against KeyMeta. */
  public static List<PageDescriptor> decodePageDirectory(byte[] encoded,
      long expectedEntries, long lastEpoch) {
    byte[] payload = checkedPayload(encoded, "serving page directory");
    if (payload.length < 19 || payload[0] != DIRECTORY_VERSION) {
      throw new ArchivePersistenceException("Invalid serving page directory dimensions");
    }
    ByteBuffer input = ByteBuffer.wrap(payload);
    input.get();
    int pageCount = input.getInt();
    if (pageCount <= 0 || pageCount > (Integer.MAX_VALUE - 9) / 14
        || input.remaining() != (long) pageCount * 14) {
      throw new ArchivePersistenceException("Invalid serving page directory dimensions");
    }
    List<PageDescriptor> pages = new ArrayList<>(pageCount);
    long previous = -1;
    long totalEntries = 0;
    for (int index = 0; index < pageCount; index++) {
      PageDescriptor page = new PageDescriptor(input.getLong(),
          Short.toUnsignedInt(input.getShort()), input.getInt());
      requireDescriptor(page, index, previous);
      pages.add(page);
      previous = page.maxEpoch();
      totalEntries += page.entries();
    }
    if (totalEntries != expectedEntries || previous != lastEpoch) {
      throw new ArchivePersistenceException("Serving page directory differs from metadata");
    }
    return pages;
  }

  private static void requireDescriptor(PageDescriptor page, int index, long previous) {
    if (page.pageId() != index || page.entries() <= 0 || page.entries() > MAX_PAGE_ENTRIES
        || page.maxEpoch() < 0 || page.maxEpoch() <= previous) {
      throw new ArchivePersistenceException("Invalid serving page directory descriptor");
    }
  }

  private static void writeUvarint(ByteArrayOutputStream output, long value) {
    while ((value & ~0x7fL) != 0) {
      output.write((int) (value & 0x7f) | 0x80);
      value >>>= 7;
    }
    output.write((int) value);
  }

  /** Heights are nonnegative signed longs, so their canonical varints use at most nine bytes. */
  private static long readUvarint(ByteBuffer input) {
    long value = 0;
    for (int shift = 0; shift <= 56; shift += 7) {
      if (!input.hasRemaining()) {
        throw new ArchivePersistenceException("Serving varint is truncated");
      }
      int next = Byte.toUnsignedInt(input.get());
      value |= (long) (next & 0x7f) << shift;
      if ((next & 0x80) == 0) {
        if (shift != 0 && next == 0) {
          throw new ArchivePersistenceException("Serving varint is not canonical");
        }
        return value;
      }
    }
    throw new ArchivePersistenceException("Serving varint overflows");
  }

  private static long addDelta(long previous, long delta) {
    if (delta <= 0 || previous > Long.MAX_VALUE - delta) {
      throw new ArchivePersistenceException("Serving delta is zero or overflows");
    }
    return previous + delta;
  }

  public static byte[] encodeCoverage(CoverageView coverage) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      output.writeUTF(coverage.getDbName());
      output.writeLong(coverage.getIndexedFrom());
      output.writeLong(coverage.getIndexedThrough());
      output.write(coverage.getHeadHash());
      output.write(coverage.getSourceDigest());
      output.writeUTF(coverage.getGenerationId());
      output.writeUTF(coverage.getComparatorId());
      output.flush();
      return withChecksum(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException("Unexpected Store coverage encoding failure", impossible);
    }
  }

  public static <T> T decodeCoverage(byte[] encoded, CoverageFactory<T> factory) {
    byte[] payload = checkedPayload(encoded, "serving Store coverage");
    try {
      DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
      String database = input.readUTF();
      long from = input.readLong();
      long through = input.readLong();
      byte[] headHash = new byte[32];
      byte[] sourceDigest = new byte[32];
      input.readFully(headHash);
      input.readFully(sourceDigest);
      String generationId = input.readUTF();
      String comparatorId = input.readUTF();
      if (from < 0
          || through < from
          || generationId.isEmpty()
          || comparatorId.isEmpty()
          || input.available() != 0) {
        throw new ArchivePersistenceException("Invalid serving Store coverage");
      }
      return factory.create(
          database, from, through, headHash, sourceDigest, generationId, comparatorId);
    } catch (IOException failure) {
      throw new ArchivePersistenceException("Serving Store coverage is truncated", failure);
    }
  }

  public static byte[] encodeDescriptor(DescriptorView descriptor) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream output = new DataOutputStream(bytes);
      output.writeInt(MAGIC);
      output.writeShort(descriptor.formatVersion());
      output.writeShort(0);
      output.writeUTF(descriptor.scopeIdentity());
      output.writeUTF(descriptor.generationId());
      output.writeLong(descriptor.indexedFrom());
      output.writeLong(descriptor.indexedThrough());
      output.write(descriptor.headHash());
      output.write(descriptor.sourceDigest());
      output.write(descriptor.latestSourceIdentityDigest());
      output.writeLong(descriptor.keyChanges());
      output.writeInt(descriptor.participants().size());
      for (String participant : descriptor.participants()) {
        output.writeUTF(participant);
      }
      output.flush();
      byte[] payload = bytes.toByteArray();
      output.writeInt(Hashing.crc32c().hashBytes(payload).asInt());
      output.flush();
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Unexpected serving manifest encoding failure", impossible);
    }
  }

  public static <T> T decodeDescriptor(byte[] encoded, DescriptorFactory<T> factory) {
    if (encoded == null || encoded.length < 96 || encoded.length > MAX_MANIFEST_SIZE) {
      throw new IllegalArgumentException("Serving index manifest length is invalid");
    }
    byte[] payload = Arrays.copyOf(encoded, encoded.length - Integer.BYTES);
    int checksum = ByteBuffer.wrap(encoded, payload.length, Integer.BYTES).getInt();
    if (checksum != Hashing.crc32c().hashBytes(payload).asInt()) {
      throw new IllegalArgumentException("Serving index manifest checksum mismatch");
    }
    try {
      DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
      if (input.readInt() != MAGIC) {
        throw new IllegalArgumentException("Unsupported serving index manifest");
      }
      short version = input.readShort();
      if ((version != EXACT_VERSION && version != VERSION && version != VERSION - 1)
          || input.readShort() != 0) {
        throw new IllegalArgumentException("Unsupported serving index manifest");
      }
      String scopeIdentity = input.readUTF();
      String generationId = input.readUTF();
      long from = input.readLong();
      long through = input.readLong();
      byte[] headHash = new byte[32];
      byte[] sourceDigest = new byte[32];
      input.readFully(headHash);
      input.readFully(sourceDigest);
      byte[] latestSourceIdentityDigest = new byte[32];
      input.readFully(latestSourceIdentityDigest);
      long keyChanges = input.readLong();
      int count = input.readInt();
      if (generationId.isEmpty()
          || from < 0
          || through < from
          || keyChanges < 0
          || count <= 0
          || count > ArchiveStoreScope.getStateDatabases().size() + 16) {
        throw new IllegalArgumentException("Invalid serving index manifest fields");
      }
      List<String> participants = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        participants.add(input.readUTF());
      }
      if (input.available() != Integer.BYTES) {
        throw new IllegalArgumentException("Serving index manifest payload mismatch");
      }
      List<String> sorted = sortedParticipants(participants);
      if (!scopeIdentity.equals(ArchiveStoreScope.scopeIdentity(sorted))) {
        throw new IllegalArgumentException("Serving index manifest scope identity mismatch");
      }
      return factory.create(
          version,
          scopeIdentity,
          generationId,
          from,
          through,
          headHash,
          sourceDigest,
          latestSourceIdentityDigest,
          sorted,
          keyChanges);
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Serving index manifest is truncated", invalid);
    }
  }

  private static byte[] withChecksum(byte[] payload) {
    return ByteBuffer.allocate(payload.length + Integer.BYTES)
        .put(payload)
        .putInt(Hashing.crc32c().hashBytes(payload).asInt())
        .array();
  }

  private static byte[] checkedPayload(byte[] encoded, String name) {
    if (encoded == null || encoded.length <= Integer.BYTES) {
      throw new ArchivePersistenceException(name + " is missing or truncated");
    }
    byte[] payload = Arrays.copyOf(encoded, encoded.length - Integer.BYTES);
    int checksum = ByteBuffer.wrap(encoded, payload.length, Integer.BYTES).getInt();
    if (checksum != Hashing.crc32c().hashBytes(payload).asInt()) {
      throw new ArchivePersistenceException(name + " checksum mismatch");
    }
    return payload;
  }

  public static void requireStrictEpochs(List<Long> epochs, long previous) {
    for (long epoch : epochs) {
      if (epoch < 0 || epoch <= previous) {
        throw new ArchivePersistenceException("Serving epochs are not strictly increasing");
      }
      previous = epoch;
    }
  }

  public static List<String> sortedParticipants(List<String> databases) {
    List<String> result = new ArrayList<>(Objects.requireNonNull(databases, "databases"));
    Collections.sort(result);
    if (result.isEmpty()) {
      throw new IllegalArgumentException("Serving index participant set must not be empty");
    }
    String previous = null;
    for (String database : result) {
      if (database == null || database.isEmpty() || database.equals(previous)) {
        throw new IllegalArgumentException("Serving index participant set is invalid");
      }
      previous = database;
    }
    return Collections.unmodifiableList(result);
  }

  private static List<Long> asList(long[] epochs) {
    List<Long> result = new ArrayList<>(epochs.length);
    for (long epoch : epochs) {
      result.add(epoch);
    }
    return result;
  }

  public interface DescriptorView {
    short formatVersion();

    String scopeIdentity();

    String generationId();

    long indexedFrom();

    long indexedThrough();

    byte[] headHash();

    byte[] sourceDigest();

    byte[] latestSourceIdentityDigest();

    List<String> participants();

    long keyChanges();
  }

  @FunctionalInterface
  public interface DescriptorFactory<T> {
    T create(
        short formatVersion,
        String scopeIdentity,
        String generationId,
        long indexedFrom,
        long indexedThrough,
        byte[] headHash,
        byte[] sourceDigest,
        byte[] latestSourceIdentityDigest,
        List<String> participants,
        long keyChanges);
  }

  public interface CoverageView {
    String getDbName();

    long getIndexedFrom();

    long getIndexedThrough();

    byte[] getHeadHash();

    byte[] getSourceDigest();

    String getGenerationId();

    String getComparatorId();
  }

  @FunctionalInterface
  public interface CoverageFactory<T> {
    T create(
        String dbName,
        long indexedFrom,
        long indexedThrough,
        byte[] headHash,
        byte[] sourceDigest,
        String generationId,
        String comparatorId);
  }

  public interface KeyMetadataView {
    byte mode();

    long count();

    long firstEpoch();

    long lastEpoch();

    int inlineEpochCount();

    long inlineEpoch(int index);
  }

  @FunctionalInterface
  public interface KeyMetadataFactory<T> {
    T create(byte mode, long count, long firstEpoch, long lastEpoch, long[] inlineEpochs);
  }
}
