package org.tron.core.db2.archive.serving.format;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.hash.Hashing;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;
import org.tron.core.db2.archive.ArchivePersistenceException;
import org.tron.core.db2.archive.serving.format.ServingIndexRecordCodec.PageDescriptor;
import org.tron.core.db2.archive.serving.format.ServingIndexRecordCodec.PageWriter;

public class ServingIndexCompressedPageTest {

  @Test
  public void matchesExplicitCompressedPageBytes() {
    // Header, absolute 1, delta 1, tail restart offset zero, big-endian CRC32C.
    byte[] expected = Hex.decode("01000000020100010100007a924b76");
    assertArrayEquals(expected, ServingIndexRecordCodec.encodeEpochPage(new long[]{1, 2}));
    assertArrayEquals(new long[]{1, 2}, ServingIndexRecordCodec.decodeEpochPage(expected));
    // Three single-entry segments: tail offsets 0/1/2 and absolute heights 0/127/128.
    byte[] restarts = Hex.decode("01000000030001007f80010000000100026abb299c");
    assertArrayEquals(restarts,
        ServingIndexRecordCodec.encodeEpochPage(new long[]{0, 127, 128}, 1));
    assertArrayEquals(new long[]{0, 127, 128}, ServingIndexRecordCodec.decodeEpochPage(restarts));
  }

  @Test
  public void roundTripsRestartBoundaries() {
    Random random = new Random(502);
    for (int interval : new int[]{1, 64, 256}) {
      for (int count : new int[]{1, 4, 63, 64, 65, 255, 256, 257}) {
        long[] epochs = new long[count];
        for (int i = 1; i < count; i++) {
          epochs[i] = epochs[i - 1] + 1 + random.nextInt(2_097_152);
        }
        assertArrayEquals(epochs, ServingIndexRecordCodec.decodeEpochPage(
            ServingIndexRecordCodec.encodeEpochPage(epochs, interval)));
        assertArrayEquals(epochs, ServingIndexRecordCodec.decodeEpochPage(
            ServingIndexRecordCodec.encodeEpochPage(epochs)));
      }
    }
    long[] boundary = {0, 127, 128, 16383, 16384, Long.MAX_VALUE};
    assertArrayEquals(boundary, ServingIndexRecordCodec.decodeEpochPage(
        ServingIndexRecordCodec.encodeEpochPage(boundary)));
    assertArrayEquals(new long[]{Long.MAX_VALUE}, ServingIndexRecordCodec.decodeEpochPage(
        ServingIndexRecordCodec.encodeEpochPage(new long[]{Long.MAX_VALUE})));
  }

  @Test
  public void rollsPagesOnEncodedBytesIndependentOfEntryCount() {
    // One-byte entries with no restart inside the page: appends are admitted while the
    // data area is at most 4088 bytes, so the first page holds exactly 4089 entries.
    long[] oneByteEach = consecutive(0, 4100);
    List<byte[]> pages = paginate(oneByteEach, ServingIndexRecordCodec.PAGE_DATA_BUDGET);
    assertEquals(2, pages.size());
    assertEquals(4089, ServingIndexRecordCodec.pageEpochCount(pages.get(0)));
    assertEquals(11, ServingIndexRecordCodec.pageEpochCount(pages.get(1)));
    assertArrayEquals(Arrays.copyOfRange(oneByteEach, 0, 4089),
        ServingIndexRecordCodec.decodeEpochPage(pages.get(0)));
    assertArrayEquals(Arrays.copyOfRange(oneByteEach, 4089, 4100),
        ServingIndexRecordCodec.decodeEpochPage(pages.get(1)));
    // Five-byte deltas: the same budget admits only 819 entries, proving pages roll on
    // bytes rather than on any entry count.
    long[] fiveBytesEach = new long[1000];
    for (int index = 1; index < fiveBytesEach.length; index++) {
      fiveBytesEach[index] = fiveBytesEach[index - 1] + (1L << 28);
    }
    pages = paginate(fiveBytesEach, ServingIndexRecordCodec.PAGE_DATA_BUDGET);
    assertEquals(2, pages.size());
    assertEquals(819, ServingIndexRecordCodec.pageEpochCount(pages.get(0)));
    assertEquals(181, ServingIndexRecordCodec.pageEpochCount(pages.get(1)));
  }

  @Test
  public void defaultIntervalPagesRollOnTheByteEstimateAndRestartFresh() {
    // Consecutive heights from 101: restart anchors cost two bytes from segment one on.
    // The exact split is byte-driven (4074 + 926), not any fixed entry count.
    List<byte[]> pages = paginate(consecutive(101, 5000),
        ServingIndexRecordCodec.RESTART_INTERVAL);
    assertEquals(2, pages.size());
    assertEquals(4074, ServingIndexRecordCodec.pageEpochCount(pages.get(0)));
    assertEquals(926, ServingIndexRecordCodec.pageEpochCount(pages.get(1)));
    // The finished value exceeds the data budget once header, restart table and CRC count.
    assertTrue(pages.get(0).length > ServingIndexRecordCodec.PAGE_DATA_BUDGET);
    // The second page restarts with an absolute height, not a delta across the boundary.
    assertEquals(101 + 4074, ServingIndexRecordCodec.firstEpochOf(pages.get(1)));
  }

  @Test
  public void allowsANineByteVarintAtTheRolloverBoundary() {
    long[] epochs = consecutive(0, 4074);
    // From zero, 4073 consecutive heights plus 15 extra restart bytes take 4088 B.
    epochs[4073] = epochs[4072] + (1L << 56);
    PageWriter writer = new PageWriter();
    for (int i = 0; i < epochs.length - 1; i++) {
      writer.append(epochs[i]);
    }
    assertFalse(writer.isFull()); // 4088 + 8 == 4096: admit one more height.
    writer.append(epochs[4073]);
    assertTrue(writer.isFull());
    byte[] page = writer.finish();
    int segments = (epochs.length - 1) / ServingIndexRecordCodec.RESTART_INTERVAL + 1;
    assertEquals(4097, page.length - 7 - segments * Short.BYTES - Integer.BYTES);
    assertArrayEquals(epochs, ServingIndexRecordCodec.decodeEpochPage(page));
  }

  @Test
  public void batchingNeverChangesPageBoundaries() {
    Random random = new Random(505);
    long[] epochs = new long[6000];
    epochs[0] = 50;
    for (int index = 1; index < epochs.length; index++) {
      epochs[index] = epochs[index - 1] + 1 + random.nextInt(300);
    }
    List<byte[]> whole = paginate(epochs, ServingIndexRecordCodec.RESTART_INTERVAL);
    // Incremental commits: each chunk finishes and reopens the tail page from its bytes
    // and descriptor, exactly like a later commit would; a full tail stays untouched.
    List<byte[]> pages = new ArrayList<>();
    PageWriter writer = new PageWriter();
    int pageId = 0;
    int index = 0;
    while (index < epochs.length) {
      int end = StrictMath.min(epochs.length, index + 1 + random.nextInt(500));
      for (; index < end; index++) {
        if (writer.isFull()) {
          pages.add(writer.finish());
          writer = new PageWriter();
          pageId++;
        }
        writer.append(epochs[index]);
      }
      byte[] tail = writer.finish();
      PageDescriptor descriptor = writer.descriptor(pageId);
      writer = new PageWriter(tail, descriptor);
      // Reopening without appends must be byte-identical (zero-decode tail reuse).
      assertArrayEquals(tail, writer.finish());
      if (writer.isFull()) {
        pages.add(tail);
        writer = new PageWriter();
        pageId++;
      }
    }
    pages.add(writer.finish());
    assertEquals(whole.size(), pages.size());
    for (int page = 0; page < whole.size(); page++) {
      assertArrayEquals("page " + page + " must not depend on commit batching",
          whole.get(page), pages.get(page));
    }
  }

  @Test
  public void reusesTheEncodedHeightPrefixWhenAppending() {
    long[] epochs = consecutive(100, 11);
    byte[] encoded = ServingIndexRecordCodec.encodeEpochPage(epochs);
    PageWriter writer = new PageWriter(encoded,
        new PageDescriptor(epochs[epochs.length - 1], epochs.length, 0));
    writer.append(111);
    writer.append(115);
    byte[] grown = writer.finish();
    assertArrayEquals(new long[]{100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110,
        111, 115}, ServingIndexRecordCodec.decodeEpochPage(grown));
    // Header count and the tail table/CRC change, but the encoded height bytes survive.
    int segments = (epochs.length - 1) / ServingIndexRecordCodec.RESTART_INTERVAL + 1;
    int dataBytes = encoded.length - 7 - segments * Short.BYTES - Integer.BYTES;
    assertArrayEquals(Arrays.copyOfRange(encoded, 7, 7 + dataBytes),
        Arrays.copyOfRange(grown, 7, 7 + dataBytes));
  }

  @Test
  public void seeksByRestartAnchorAndScansOnlyTheTargetSegment() {
    Random random = new Random(504);
    long[] epochs = new long[512];
    for (int i = 1; i < epochs.length; i++) {
      epochs[i] = epochs[i - 1] + 1 + random.nextInt(1 << 21);
    }
    byte[] encoded = ServingIndexRecordCodec.encodeEpochPage(epochs);
    assertEquals(epochs.length, ServingIndexRecordCodec.pageEpochCount(encoded));
    assertEquals(epochs[0], ServingIndexRecordCodec.firstEpochOf(encoded));
    assertEquals(epochs[epochs.length - 1], ServingIndexRecordCodec.lastEpochOf(encoded));
    for (long target : new long[]{-1, 0, epochs[0], epochs[100], epochs[255],
        epochs[256], epochs[257], epochs[510], epochs[511], Long.MAX_VALUE - 1}) {
      OptionalLong found = ServingIndexRecordCodec.firstEpochGreaterThan(encoded, target);
      int expected = target >= epochs[epochs.length - 1] ? epochs.length
          : Arrays.binarySearch(epochs, target) >= 0
              ? Arrays.binarySearch(epochs, target) + 1
              : -(Arrays.binarySearch(epochs, target) + 1);
      if (expected == epochs.length) {
        assertFalse(found.isPresent());
      } else {
        assertTrue(found.isPresent());
        assertEquals(epochs[expected], found.getAsLong());
      }
    }
  }

  @Test
  public void rejectsOverBudgetAndMalformedPagesEvenWithRecomputedChecksum() {
    // A whole-array encoder must refuse heights that no single page budget can hold.
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.encodeEpochPage(consecutive(0, 6000)));
    byte[] valid = ServingIndexRecordCodec.encodeEpochPage(new long[]{1, 2});
    byte[] corrupt = valid.clone();
    corrupt[corrupt.length - 1] ^= 1;
    reject(corrupt);
    for (int length = 0; length < valid.length; length++) {
      reject(Arrays.copyOf(valid, length));
    }
    reject(page(bytes(1), 2, 5000)); // header count above the byte-derived entry bound
    reject(page(bytes(4098), 4097, 256)); // data area above the byte-derived bound
    byte[] badOffset = valid.clone();
    ByteBuffer.wrap(badOffset).putShort(9, (short) 1);
    reject(checksum(badOffset));
    byte[] badInterval = valid.clone();
    ByteBuffer.wrap(badInterval).putShort(5, (short) 0);
    reject(checksum(badInterval));
    byte[] badCount = valid.clone();
    ByteBuffer.wrap(badCount).putInt(1, 3);
    reject(checksum(badCount));
    byte[] zeroDelta = valid.clone();
    zeroDelta[8] = 0;
    reject(checksum(zeroDelta));
    // Canonical varints cannot have a redundant final zero byte.
    reject(page(new byte[]{(byte) 0x81, 0}, 1));
    reject(page(new byte[]{(byte) 0x80}, 1));
    byte[] overflow = new byte[10];
    Arrays.fill(overflow, (byte) 0xff);
    reject(page(overflow, 1));
    // Long.MAX_VALUE followed by a positive delta overflows the signed height range.
    byte[] overflowingDelta = new byte[10];
    Arrays.fill(overflowingDelta, 0, 8, (byte) 0xff);
    overflowingDelta[8] = 0x7f;
    overflowingDelta[9] = 1;
    reject(page(overflowingDelta, 2));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.encodeEpochPage(new long[]{2, 1}));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.encodeEpochPage(new long[0]));
    assertThrows(ArchivePersistenceException.class,
        () -> new PageWriter(valid, new PageDescriptor(2, 3, 0))); // header count differs
  }

  @Test
  public void directoryRoundTripsRealPageDimensions() {
    // Non-uniform byte-budgeted pages: 4089 one-byte entries then a 911-entry tail.
    List<PageDescriptor> pages = Arrays.asList(
        new PageDescriptor(4088, 4089, 0), new PageDescriptor(5000, 911, 1));
    byte[] encoded = ServingIndexRecordCodec.encodePageDirectory(pages);
    assertEquals(5 + 2 * 14 + 4, encoded.length);
    List<PageDescriptor> decoded = ServingIndexRecordCodec.decodePageDirectory(
        encoded, 5000, 5000);
    assertEquals(2, decoded.size());
    assertEquals(4088, decoded.get(0).maxEpoch());
    assertEquals(4089, decoded.get(0).entries());
    assertEquals(0, decoded.get(0).pageId());
    assertEquals(5000, decoded.get(1).maxEpoch());
    assertEquals(911, decoded.get(1).entries());
    assertEquals(1, decoded.get(1).pageId());
    // A single-page directory is valid: every paged key owns descriptors from page zero.
    byte[] single = ServingIndexRecordCodec.encodePageDirectory(
        Collections.singletonList(new PageDescriptor(105, 5, 0)));
    assertEquals(1, ServingIndexRecordCodec.decodePageDirectory(single, 5, 105).size());
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodePageDirectory(encoded, 5001, 5000));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodePageDirectory(encoded, 5000, 4999));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodePageDirectory(null, 5000, 5000));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.encodePageDirectory(new ArrayList<>()));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.encodePageDirectory(
            Arrays.asList(new PageDescriptor(4088, 4089, 0),
                new PageDescriptor(4000, 911, 1)))); // max heights not increasing
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.encodePageDirectory(
            Arrays.asList(new PageDescriptor(4088, 0, 0),
                new PageDescriptor(5000, 911, 1)))); // zero entries
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.encodePageDirectory(
            Arrays.asList(new PageDescriptor(4088, 4098, 0),
                new PageDescriptor(5000, 911, 1)))); // entries above the byte-derived bound
    byte[] flippedPageId = encoded.clone();
    flippedPageId[5 + 14 + 10] = 2; // second descriptor pageId != index
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodePageDirectory(
            checksum(flippedPageId), 5000, 5000));
    byte[] corrupted = encoded.clone();
    corrupted[corrupted.length - 1] ^= 1;
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodePageDirectory(corrupted, 5000, 5000));
    byte[] key = ServingIndexKeyCodec.keyPageDirectoryKey("code", new byte[]{0, 1});
    assertEquals(6, key[0]);
  }

  @Test
  public void countsEncodedBytesWithoutClaimingDiskSavings() {
    long[] epochs = consecutive(84_000_000L, 512);
    byte[] compressed = ServingIndexRecordCodec.encodeEpochPage(epochs);
    // 7-byte header, two 4-byte absolute starts, 510 one-byte deltas, two tail offsets, CRC.
    assertEquals(533, compressed.length);
    // A full one-byte-delta page (interval equal to the budget, single restart segment):
    // the data area alone reaches 4089 bytes and the value exceeds the 4096-byte budget.
    byte[] dense = ServingIndexRecordCodec.encodeEpochPage(
        consecutive(0, 4089), ServingIndexRecordCodec.PAGE_DATA_BUDGET);
    assertEquals(7 + 4089 + Short.BYTES + Integer.BYTES, dense.length);
    assertTrue(dense.length > ServingIndexRecordCodec.PAGE_DATA_BUDGET);
    // Historical fixed-width size is a formula, not a retained production encoder.
    assertEquals(4104, Integer.BYTES + 512 * Long.BYTES + Integer.BYTES);
  }

  /** Mirrors the Generation append loop: check the byte estimate before every entry. */
  private static List<byte[]> paginate(long[] epochs, int interval) {
    List<byte[]> pages = new ArrayList<>();
    PageWriter writer = new PageWriter(interval);
    for (long epoch : epochs) {
      if (writer.isFull()) {
        pages.add(writer.finish());
        writer = new PageWriter(interval);
      }
      writer.append(epoch);
    }
    pages.add(writer.finish());
    return pages;
  }

  private static long[] consecutive(long first, int count) {
    long[] epochs = new long[count];
    for (int index = 0; index < count; index++) {
      epochs[index] = first + index;
    }
    return epochs;
  }

  private static byte[] bytes(int length) {
    byte[] result = new byte[length];
    Arrays.fill(result, (byte) 1);
    return result;
  }

  private static byte[] page(byte[] heightBytes, int count) {
    return page(heightBytes, count, ServingIndexRecordCodec.RESTART_INTERVAL);
  }

  private static byte[] page(byte[] heightBytes, int count, int interval) {
    int segments = (count - 1) / interval + 1;
    return checksum(ByteBuffer.allocate(7 + heightBytes.length + segments * Short.BYTES + 4)
        .put((byte) 1).putInt(count).putShort((short) interval)
        .put(heightBytes).array());
  }

  private static byte[] checksum(byte[] bytes) {
    ByteBuffer.wrap(bytes).putInt(bytes.length - 4,
        Hashing.crc32c().hashBytes(bytes, 0, bytes.length - 4).asInt());
    return bytes;
  }

  private static void reject(byte[] bytes) {
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodeEpochPage(bytes));
  }
}
