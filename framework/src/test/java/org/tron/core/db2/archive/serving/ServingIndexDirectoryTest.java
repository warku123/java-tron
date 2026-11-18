package org.tron.core.db2.archive.serving;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.tron.core.db2.archive.serving.ServingIndexStoreTest.blocks;
import static org.tron.core.db2.archive.serving.ServingIndexStoreTest.hash;
import static org.tron.core.db2.archive.serving.ServingIndexStoreTest.key;
import static org.tron.core.db2.archive.serving.ServingIndexStoreTest.plan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.OptionalLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.core.db2.archive.ArchivePersistenceException;
import org.tron.core.db2.archive.serving.format.ServingIndexKeyCodec;
import org.tron.core.db2.archive.serving.format.ServingIndexRecordCodec;
import org.tron.core.db2.archive.serving.index.ServingIndexIncrementalPlan;
import org.tron.core.db2.archive.storage.index.ArchiveIndexStorage;
import org.tron.core.db2.stateroot.format.PathStateStoreManifest.Engine;

public class ServingIndexDirectoryTest {
  private static final long B0 = 101;
  private static final ServingIndexStore.WriteBudget LARGE =
      new ServingIndexStore.WriteBudget(500_000, 64L * 1024 * 1024);
  private static final ServingIndexStore.WriteBudget TINY =
      new ServingIndexStore.WriteBudget(64, 64 * 1024);

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void createsDirectoryAtThirdPageAndPreservesOldSnapshot() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath().resolve("single-v1");
    byte[] progress;
    try (ServingIndexStore store = open(directory)) {
      assertTrue(store.commit(plan(store, blocks(B0, 1024, 1)), LARGE, false));
      try (ArchiveIndexStorage.Reader snapshot = store.snapshot()) {
        progress = snapshot.get(PersistentServingKeyIndexGeneration.PROGRESS_KEY);
      }
    }
    try (ServingIndexStore store = open(directory);
        ArchiveIndexStorage.Reader oldSnapshot = store.snapshot();
        PersistentServingKeyIndexGeneration old = view(directory, oldSnapshot)) {
      assertEquals(5, ServingIndexStore.readDescriptor(oldSnapshot).formatVersion());
      assertArrayEquals(progress,
          oldSnapshot.get(PersistentServingKeyIndexGeneration.PROGRESS_KEY));
      assertTrue(store.commit(plan(store, blocks(B0 + 1024, 1, 1)), LARGE, false));
      assertFalse(old.firstChangeAfter("code", key(0), B0 + 1023, B0 + 1023).isPresent());
      try (ArchiveIndexStorage.Reader snapshot = store.snapshot();
          PersistentServingKeyIndexGeneration current = view(directory, snapshot)) {
        assertEquals(5, ServingIndexStore.readDescriptor(snapshot).formatVersion());
        assertArrayEquals(oldSnapshot.get(ServingIndexKeyCodec.keyPageKey("code", key(0), 0)),
            snapshot.get(ServingIndexKeyCodec.keyPageKey("code", key(0), 0)));
        assertEquals(1, snapshot.get(ServingIndexKeyCodec.keyPageKey("code", key(0), 2))[0]);
        assertEquals(3, snapshot.get(ServingIndexKeyCodec.keyMetaKey("code", key(0)))[0]);
        assertNotNull(snapshot.get(ServingIndexKeyCodec.keyPageDirectoryKey("code", key(0))));
        for (long target = B0 - 1; target <= B0 + 1024; target++) {
          OptionalLong expected = target == B0 + 1024 ? OptionalLong.empty()
              : OptionalLong.of(target + 1);
          assertEquals(expected,
              current.firstChangeAfter("code", key(0), target, B0 + 1024));
        }
        assertEquals(OptionalLong.empty(),
            current.firstChangeAfter("code", key(0), B0 + 1023, B0 + 1023));
        assertEquals(3, current.inspectStatistics().getStores().get("code").getPageCount());
      }
    }
    // A directory-required KeyMeta must never silently fall back after directory loss.
    try (ArchiveIndexStorage.Writer writer = ArchiveIndexStorage.openWriter(
        directory.resolve("keys"), Engine.ROCKSDB)) {
      writer.write(Collections.singletonList(ArchiveIndexStorage.delete(
          ServingIndexKeyCodec.keyPageDirectoryKey("code", key(0)))), true);
    }
    try (ServingIndexStore store = open(directory);
        ArchiveIndexStorage.Reader snapshot = store.snapshot();
        PersistentServingKeyIndexGeneration current = view(directory, snapshot)) {
      assertThrows(ArchivePersistenceException.class,
          () -> current.firstChangeAfter("code", key(0), B0, B0 + 1024));
    }
  }

  @Test
  public void updatesExistingDirectoryAndExtendsToFourthPage() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath().resolve("single-v1");
    byte[] directoryKey = ServingIndexKeyCodec.keyPageDirectoryKey("code", key(0));
    try (ServingIndexStore store = open(directory)) {
      assertTrue(store.commit(plan(store, blocks(B0, 1025, 1)), LARGE, false));
      try (ArchiveIndexStorage.Reader oldSnapshot = store.snapshot();
          PersistentServingKeyIndexGeneration old = view(directory, oldSnapshot)) {
        assertTrue(store.commit(plan(store, blocks(B0 + 1025, 1, 1)), LARGE, false));
        try (ArchiveIndexStorage.Reader snapshot = store.snapshot()) {
          assertArrayEquals(new long[]{B0 + 511, B0 + 1023, B0 + 1025},
              ServingIndexRecordCodec.decodePageDirectory(snapshot.get(directoryKey),
                  3, B0 + 1025));
        }
        assertTrue(store.commit(plan(store, blocks(B0 + 1026, 511, 1)), LARGE, false));
        assertArrayEquals(new long[]{B0 + 511, B0 + 1023, B0 + 1024},
            ServingIndexRecordCodec.decodePageDirectory(oldSnapshot.get(directoryKey),
                3, B0 + 1024));
        assertEquals(OptionalLong.of(B0 + 1024),
            old.firstChangeAfter("code", key(0), B0 + 1023, B0 + 1024));
        try (ArchiveIndexStorage.Reader snapshot = store.snapshot();
            PersistentServingKeyIndexGeneration current = view(directory, snapshot)) {
          assertArrayEquals(new long[]{B0 + 511, B0 + 1023, B0 + 1535, B0 + 1536},
              ServingIndexRecordCodec.decodePageDirectory(snapshot.get(directoryKey),
                  4, B0 + 1536));
          for (long target = B0 - 1; target <= B0 + 1536; target++) {
            OptionalLong expected = target == B0 + 1536 ? OptionalLong.empty()
                : OptionalLong.of(target + 1);
            assertEquals(expected,
                current.firstChangeAfter("code", key(0), target, B0 + 1536));
          }
          assertEquals(4, current.inspectStatistics().getStores().get("code").getPageCount());
        }
      }
    }
  }

  @Test
  public void replayRejectsMissingDirectoryInAlreadyWrittenSoloGroup() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath().resolve("single-v1");
    byte[] residueDirectory = null;
    try (ServingIndexStore store = open(directory)) {
      assertTrue(store.commit(plan(store, blocks(B0, 1024, 80)), LARGE, false));
      ServingIndexIncrementalPlan next = plan(store, blocks(B0 + 1024, 1, 80));
      assertThrows(IOException.class, () -> store.commitSolo(next, TINY, false,
          (index, count) -> {
            if (index == 1) {
              throw new IOException("interrupt before progress publication");
            }
          }));
      try (ArchiveIndexStorage.Reader snapshot = store.snapshot()) {
        for (int index = 0; index < 80; index++) {
          byte[] candidate = ServingIndexKeyCodec.keyPageDirectoryKey("code", key(index));
          if (snapshot.get(candidate) != null) {
            residueDirectory = candidate;
            break;
          }
        }
      }
    }
    assertNotNull(residueDirectory);
    try (ArchiveIndexStorage.Writer writer = ArchiveIndexStorage.openWriter(
        directory.resolve("keys"), Engine.ROCKSDB)) {
      writer.write(Collections.singletonList(ArchiveIndexStorage.delete(residueDirectory)), true);
    }
    try (ServingIndexStore store = open(directory)) {
      ServingIndexIncrementalPlan next = plan(store, blocks(B0 + 1024, 1, 80));
      assertThrows(ArchivePersistenceException.class,
          () -> store.commitSolo(next, TINY, false, ServingIndexStore.ChunkHook.NONE));
      assertEquals(B0 + 1023, store.indexedThrough());
    }
  }

  @Test
  public void soloReplayKeepsDirectoryPagesAndMetadataAtomic() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath().resolve("single-v1");
    try (ServingIndexStore store = open(directory)) {
      assertTrue(store.commit(plan(store, blocks(B0, 1024, 80)), LARGE, false));
      ServingIndexIncrementalPlan next = plan(store, blocks(B0 + 1024, 1, 80));
      assertThrows(IOException.class, () -> store.commitSolo(next, TINY, false,
          (index, count) -> {
            if (index == 1) {
              throw new IOException("interrupt after a directory-bearing key group");
            }
          }));
      assertEquals(B0 + 1023, store.indexedThrough());
    }
    try (ServingIndexStore store = open(directory)) {
      ServingIndexIncrementalPlan next = plan(store, blocks(B0 + 1024, 1, 80));
      store.commitSolo(next, TINY, false, ServingIndexStore.ChunkHook.NONE);
      try (ArchiveIndexStorage.Reader snapshot = store.snapshot();
          PersistentServingKeyIndexGeneration current = view(directory, snapshot)) {
        for (int index = 0; index < 80; index++) {
          assertEquals(OptionalLong.of(B0 + 1024), current.firstChangeAfter("code", key(index),
              B0 + 1023, B0 + 1024));
        }
      }
    }
  }

  private static ServingIndexStore open(Path directory) throws IOException {
    return ServingIndexStore.open(directory, Engine.ROCKSDB, B0, hash(B0 - 1), false, 1);
  }

  private static PersistentServingKeyIndexGeneration view(Path directory,
      ArchiveIndexStorage.Reader snapshot) throws IOException {
    return PersistentServingKeyIndexGeneration.overSnapshot(directory,
        ServingIndexStore.readDescriptor(snapshot), Engine.ROCKSDB, snapshot);
  }
}
