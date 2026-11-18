package org.tron.core.db2.archive.serving.format;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.hash.Hashing;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Test;
import org.tron.core.db2.archive.ArchivePersistenceException;

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
    for (int interval : new int[]{1, 64, 128, 256, 512}) {
      for (int count : new int[]{1, 4, 5, 63, 64, 65, 255, 256, 257, 511, 512}) {
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
  public void appendsWithoutDecodingTheEncodedPrefix() {
    Random random = new Random(503);
    for (int interval : new int[]{1, 3, 64, 256}) {
      for (int oldCount : new int[]{1, 2, 63, 64, 65, 255, 256, 257, 511}) {
        long[] epochs = new long[oldCount];
        for (int i = 1; i < oldCount; i++) {
          epochs[i] = epochs[i - 1] + 1 + random.nextInt(1 << 20);
        }
        long base = epochs[oldCount - 1];
        byte[] encoded = ServingIndexRecordCodec.encodeEpochPage(epochs, interval);
        int room = StrictMath.min(2, 512 - oldCount);
        List<Long> appended = room == 2 ? Arrays.asList(base + 5, base + 9)
            : Collections.singletonList(base + 5);
        byte[] grown = ServingIndexRecordCodec.appendEpochPage(encoded, appended,
            oldCount, base);
        long[] expected = Arrays.copyOf(epochs, oldCount + room);
        expected[oldCount] = base + 5;
        if (room == 2) {
          expected[oldCount + 1] = base + 9;
        }
        assertArrayEquals(expected, ServingIndexRecordCodec.decodeEpochPage(grown));
      }
    }
    // Segment rotation on append: 256 entries plus two more opens a new restart anchor.
    long[] epochs = new long[256];
    for (int i = 0; i < epochs.length; i++) {
      epochs[i] = 1_000L + i;
    }
    byte[] rotated = ServingIndexRecordCodec.appendEpochPage(
        ServingIndexRecordCodec.encodeEpochPage(epochs, 256),
        Arrays.asList(2_000L, 3_000L), 256, 1_255L);
    long[] decoded = ServingIndexRecordCodec.decodeEpochPage(rotated);
    assertEquals(258, decoded.length);
    assertEquals(2_000L, decoded[256]);
    assertEquals(3_000L, decoded[257]);
    // Page-full, out-of-order and header-mismatch rejections on a nearly full page.
    long[] nearlyFullHeights = new long[511];
    for (int i = 0; i < nearlyFullHeights.length; i++) {
      nearlyFullHeights[i] = 10_000L + i;
    }
    byte[] nearlyFull = ServingIndexRecordCodec.encodeEpochPage(nearlyFullHeights, 256);
    assertThrows(ArchivePersistenceException.class, () -> ServingIndexRecordCodec.appendEpochPage(
        nearlyFull, Arrays.asList(20_000L, 30_000L), 511, 10_510L)); // page is full
    assertThrows(ArchivePersistenceException.class, () -> ServingIndexRecordCodec.appendEpochPage(
        nearlyFull, Arrays.asList(10_000L), 511, 10_510L)); // out of order
    assertThrows(ArchivePersistenceException.class, () -> ServingIndexRecordCodec.appendEpochPage(
        nearlyFull, Arrays.asList(20_000L), 510, 10_510L)); // header count mismatch
    assertEquals(512, ServingIndexRecordCodec.pageEpochCount(
        ServingIndexRecordCodec.appendEpochPage(nearlyFull,
            Collections.singletonList(20_000L), 511, 10_510L)));
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
  public void rejectsMalformedPagesEvenWithRecomputedChecksum() {
    byte[] valid = ServingIndexRecordCodec.encodeEpochPage(new long[]{1, 2});
    byte[] corrupt = valid.clone();
    corrupt[corrupt.length - 1] ^= 1;
    reject(corrupt);
    for (int length = 0; length < valid.length; length++) {
      reject(Arrays.copyOf(valid, length));
    }
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
  }

  @Test
  public void directoryRoundTripChecksDimensionsEndpointsAndChecksum() {
    long[] pageMaxHeights = {512, 1024, 1025, Long.MAX_VALUE};
    byte[] encoded = ServingIndexRecordCodec.encodePageDirectory(pageMaxHeights, 1537);
    // Fixed 14-byte descriptors: format + pageCount + 4 descriptors + CRC.
    assertEquals(5 + 4 * 14 + 4, encoded.length);
    assertArrayEquals(pageMaxHeights,
        ServingIndexRecordCodec.decodePageDirectory(encoded, 4, Long.MAX_VALUE));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodePageDirectory(encoded, 3, Long.MAX_VALUE));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodePageDirectory(encoded, 4, 2048));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodePageDirectory(null, 4, Long.MAX_VALUE));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.encodePageDirectory(pageMaxHeights, 2049));
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.encodePageDirectory(pageMaxHeights, 1536));
    byte[] corrupt = encoded.clone();
    corrupt[corrupt.length - 1] ^= 1;
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodePageDirectory(corrupt, 4, Long.MAX_VALUE));
    byte[] orphanEntry = encoded.clone();
    orphanEntry[5 + 3 * 14 + 10] = 2; // last descriptor pageId != index
    assertThrows(ArchivePersistenceException.class,
        () -> ServingIndexRecordCodec.decodePageDirectory(
            checksum(orphanEntry), 4, Long.MAX_VALUE));
    byte[] key = ServingIndexKeyCodec.keyPageDirectoryKey("code", new byte[]{0, 1});
    assertEquals(6, key[0]);
  }

  @Test
  public void countsAllEncodedBytesWithoutClaimingDiskSavings() {
    long[] epochs = new long[512];
    for (int index = 0; index < epochs.length; index++) {
      epochs[index] = 84_000_000L + index;
    }
    byte[] compressed = ServingIndexRecordCodec.encodeEpochPage(epochs);
    // 7-byte header, two 4-byte absolute starts, 510 deltas, two tail offsets, CRC.
    assertEquals(533, compressed.length);
    // Historical fixed-width size is a formula, not a retained production encoder.
    assertEquals(4104, Integer.BYTES + epochs.length * Long.BYTES + Integer.BYTES);
  }

  private static byte[] page(byte[] heightBytes, int count) {
    return checksum(ByteBuffer.allocate(7 + heightBytes.length + 2 + 4)
        .put((byte) 1).putInt(count).putShort((short) 256)
        .put(heightBytes).putShort((short) 0).array());
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
