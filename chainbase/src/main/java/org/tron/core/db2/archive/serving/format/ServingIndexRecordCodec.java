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
  public static final int EPOCHS_PER_PAGE = 512;
  public static final byte INLINE = 1;
  public static final byte PAGED = 2;
  public static final byte PAGED_DIRECTORY = 3;
  public static final int RESTART_INTERVAL = 256;
  public static final int DIRECTORY_MIN_PAGES = 3;
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
          || count > (long) Integer.MAX_VALUE * EPOCHS_PER_PAGE
          || mode == PAGED_DIRECTORY
              && (count - 1) / EPOCHS_PER_PAGE + 1 < DIRECTORY_MIN_PAGES) {
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

  public static byte[] encodeEpochPage(long[] epochs, int restartInterval) {
    if (epochs.length == 0 || epochs.length > EPOCHS_PER_PAGE
        || restartInterval <= 0 || restartInterval > EPOCHS_PER_PAGE) {
      throw new ArchivePersistenceException("Invalid compressed serving page dimensions");
    }
    requireStrictEpochs(asList(epochs), Long.MIN_VALUE);
    int segmentCount = (epochs.length - 1) / restartInterval + 1;
    ByteArrayOutputStream heightBytes = new ByteArrayOutputStream();
    ByteBuffer restartOffsets = ByteBuffer.allocate(segmentCount * Short.BYTES);
    for (int start = 0; start < epochs.length; start += restartInterval) {
      restartOffsets.putShort((short) heightBytes.size());
      writeUvarint(heightBytes, epochs[start]);
      int end = StrictMathWrapper.min(start + restartInterval, epochs.length);
      for (int index = start + 1; index < end; index++) {
        writeUvarint(heightBytes, epochs[index] - epochs[index - 1]);
      }
    }
    byte[] encodedHeights = heightBytes.toByteArray();
    return withChecksum(ByteBuffer
        .allocate(7 + encodedHeights.length + restartOffsets.capacity())
        .put(COMPRESSED_PAGE).putInt(epochs.length).putShort((short) restartInterval)
        .put(encodedHeights).put(restartOffsets.array()).array());
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
   * Geth-aligned tail append: keeps the encoded prefix untouched and appends only the new
   * varint bytes, adding a restart anchor whenever a segment fills. Page, directory and
   * KeyMeta commit atomically, so {@code oldCount} and {@code lastEpoch} (Geth's in-memory
   * desc fields) are trusted for the delta base instead of decoding the page.
   */
  public static byte[] appendEpochPage(byte[] encoded, List<Long> appended,
      int oldCount, long lastEpoch) {
    if (appended.isEmpty() || appended.get(0) <= lastEpoch) {
      throw new ArchivePersistenceException("Serving append is out of order");
    }
    if (oldCount <= 0 || oldCount >= EPOCHS_PER_PAGE
        || oldCount + appended.size() > EPOCHS_PER_PAGE) {
      throw new ArchivePersistenceException("Invalid compressed serving page dimensions");
    }
    PageLayout page = parsePageLayout(encoded);
    if (page.count != oldCount) {
      throw new ArchivePersistenceException("Serving tail page differs from metadata");
    }
    restartOffsets(page);
    ByteArrayOutputStream heightBytes = new ByteArrayOutputStream();
    heightBytes.write(page.payload, page.dataOffset, page.dataLength);
    ByteBuffer restartOffsets = ByteBuffer
        .allocate((page.segmentCount + appended.size() + 1) * Short.BYTES);
    for (int index = 0; index < page.segmentCount; index++) {
      restartOffsets.putShort(ByteBuffer.wrap(page.payload,
          page.offsetsOffset + index * Short.BYTES, Short.BYTES).getShort());
    }
    long previous = lastEpoch;
    int newCount = page.count;
    for (long epoch : appended) {
      if (epoch <= previous) {
        throw new ArchivePersistenceException("Serving append is out of order");
      }
      if (newCount % page.restartInterval == 0) {
        restartOffsets.putShort((short) heightBytes.size());
        writeUvarint(heightBytes, epoch);
      } else {
        writeUvarint(heightBytes, epoch - previous);
      }
      previous = epoch;
      newCount++;
    }
    restartOffsets.flip();
    byte[] encodedHeights = heightBytes.toByteArray();
    return withChecksum(ByteBuffer
        .allocate(7 + encodedHeights.length + restartOffsets.remaining())
        .put(COMPRESSED_PAGE).putInt(newCount).putShort((short) page.restartInterval)
        .put(encodedHeights).put(restartOffsets).array());
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
    if (count <= 0 || count > EPOCHS_PER_PAGE || restartInterval == 0
        || restartInterval > EPOCHS_PER_PAGE) {
      throw new ArchivePersistenceException("Invalid compressed serving page header");
    }
    int segmentCount = (count - 1) / restartInterval + 1;
    int offsetsOffset = payload.length - segmentCount * Short.BYTES;
    if (offsetsOffset < 7 + count) {
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

  /**
   * Geth-aligned fixed-size page directory: one 14-byte descriptor per page
   * ({@code pageMax:8 + pageEntries:2 + pageId:4}), replacing the variable-length delta
   * encoding so a descriptor's max height is readable without any varint decoding.
   */
  public static byte[] encodePageDirectory(long[] pageMaxHeights, long totalEntries) {
    if (pageMaxHeights.length < DIRECTORY_MIN_PAGES) {
      throw new ArchivePersistenceException("Serving directory has too few pages");
    }
    requireStrictEpochs(asList(pageMaxHeights), Long.MIN_VALUE);
    ByteBuffer descriptors = ByteBuffer.allocate(pageMaxHeights.length * 14);
    long remaining = totalEntries;
    for (int index = 0; index < pageMaxHeights.length; index++) {
      int entries = (int) StrictMathWrapper.min(EPOCHS_PER_PAGE, remaining);
      if (entries <= 0) {
        throw new ArchivePersistenceException("Serving directory entries mismatch");
      }
      remaining -= entries;
      descriptors.putLong(pageMaxHeights[index]).putShort((short) entries).putInt(index);
    }
    if (remaining != 0) {
      throw new ArchivePersistenceException("Serving directory entries mismatch");
    }
    return withChecksum(ByteBuffer.allocate(5 + descriptors.capacity())
        .put(DIRECTORY_VERSION).putInt(pageMaxHeights.length).put(descriptors.array()).array());
  }

  /** Validate dimensions against KeyMeta before allocating a directory array. */
  public static long[] decodePageDirectory(byte[] encoded, int expectedPages, long lastEpoch) {
    byte[] payload = checkedPayload(encoded, "serving page directory");
    if (payload.length != 5 + expectedPages * 14 || payload[0] != DIRECTORY_VERSION
        || ByteBuffer.wrap(payload, 1, Integer.BYTES).getInt() != expectedPages
        || expectedPages < DIRECTORY_MIN_PAGES) {
      throw new ArchivePersistenceException("Invalid serving page directory dimensions");
    }
    long[] pageMaxHeights = new long[expectedPages];
    for (int index = 0; index < expectedPages; index++) {
      int base = 5 + index * 14;
      long max = ByteBuffer.wrap(payload, base, Long.BYTES).getLong();
      int entries = Short.toUnsignedInt(
          ByteBuffer.wrap(payload, base + 8, Short.BYTES).getShort());
      int pageId = ByteBuffer.wrap(payload, base + 10, Integer.BYTES).getInt();
      boolean last = index == expectedPages - 1;
      if (pageId != index || entries <= 0 || entries > EPOCHS_PER_PAGE
          || !last && entries != EPOCHS_PER_PAGE
          || index > 0 && max <= pageMaxHeights[index - 1]) {
        throw new ArchivePersistenceException("Invalid serving page directory descriptor");
      }
      pageMaxHeights[index] = max;
    }
    if (pageMaxHeights[expectedPages - 1] != lastEpoch) {
      throw new ArchivePersistenceException("Serving page directory endpoints differ");
    }
    return pageMaxHeights;
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
