package org.tron.core.db2.archive.serving;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.tron.core.db2.archive.serving.ServingIndexStoreTest.blocks;
import static org.tron.core.db2.archive.serving.ServingIndexStoreTest.hash;
import static org.tron.core.db2.archive.serving.ServingIndexStoreTest.key;
import static org.tron.core.db2.archive.serving.ServingIndexStoreTest.plan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.core.db2.archive.ArchivePersistenceException;
import org.tron.core.db2.archive.serving.PersistentServingKeyIndexGeneration.ExactKey;
import org.tron.core.db2.archive.serving.format.ServingIndexKeyCodec;
import org.tron.core.db2.archive.serving.format.ServingIndexRecordCodec;
import org.tron.core.db2.archive.serving.format.ServingIndexRecordCodec.PageDescriptor;
import org.tron.core.db2.archive.storage.index.ArchiveIndexStorage;
import org.tron.core.db2.stateroot.format.PathStateStoreManifest.Engine;

public class ServingIndexDirectoryTest {
  private static final long B0 = 101;
  // Consecutive heights from B0 fill the first byte-budgeted page with exactly 4074
  // entries: restart anchors cost two bytes from the second segment on, and appends
  // stop once the encoded data area passes the Geth-style estimate.
  private static final int FIRST_PAGE_ENTRIES = 4074;

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void pagedKeyHasItsDirectoryFromTheFirstPage() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath().resolve("single-v1");
    byte[] directoryKey = ServingIndexKeyCodec.keyPageDirectoryKey("code", key(0));
    try (ServingIndexStore store = open(directory)) {
      store.commit(plan(store, blocks(B0, 5, 1)), false);
      try (ArchiveIndexStorage.Reader snapshot = store.snapshot();
          PersistentServingKeyIndexGeneration current = view(directory, snapshot)) {
        assertEquals(3, snapshot.get(ServingIndexKeyCodec.keyMetaKey("code", key(0)))[0]);
        List<PageDescriptor> pages = ServingIndexRecordCodec.decodePageDirectory(
            snapshot.get(directoryKey), 5, B0 + 4);
        assertEquals(1, pages.size());
        assertEquals(5, pages.get(0).entries());
        assertEquals(B0 + 4, pages.get(0).maxEpoch());
        assertEquals(0, pages.get(0).pageId());
        for (long target = B0 - 1; target <= B0 + 4; target++) {
          OptionalLong expected = target == B0 + 4 ? OptionalLong.empty()
              : OptionalLong.of(target + 1);
          assertEquals(expected, current.firstChangeAfter("code", key(0), target, B0 + 4));
        }
      }
    }
    // A directory-required KeyMeta must never silently fall back after directory loss.
    try (ArchiveIndexStorage.Writer writer = ArchiveIndexStorage.openWriter(
        directory.resolve("keys"), Engine.ROCKSDB)) {
      writer.write(Collections.singletonList(ArchiveIndexStorage.delete(directoryKey)), true);
    }
    try (ServingIndexStore store = open(directory);
         ArchiveIndexStorage.Reader snapshot = store.snapshot();
         PersistentServingKeyIndexGeneration current = view(directory, snapshot)) {
      assertThrows(ArchivePersistenceException.class,
          () -> current.firstChangeAfter("code", key(0), B0, B0 + 4));
    }
  }

  @Test
  public void pagesRollOnEncodedBytesAndTheDirectoryTracksRealDimensions() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath().resolve("single-v1");
    byte[] directoryKey = ServingIndexKeyCodec.keyPageDirectoryKey("code", key(0));
    try (ServingIndexStore store = open(directory)) {
      store.commit(plan(store, blocks(B0, 5000, 1)), false);
      try (ArchiveIndexStorage.Reader snapshot = store.snapshot();
           PersistentServingKeyIndexGeneration current = view(directory, snapshot)) {
        List<PageDescriptor> pages = ServingIndexRecordCodec.decodePageDirectory(
            snapshot.get(directoryKey), 5000, B0 + 4999);
        assertEquals(2, pages.size());
        assertEquals(FIRST_PAGE_ENTRIES, pages.get(0).entries());
        assertEquals(5000 - FIRST_PAGE_ENTRIES, pages.get(1).entries());
        assertEquals(B0 + FIRST_PAGE_ENTRIES - 1, pages.get(0).maxEpoch());
        assertEquals(B0 + 4999, pages.get(1).maxEpoch());
        assertEquals(2, current.inspectStatistics().getStores().get("code").getPageCount());
        for (long target = B0 - 1; target <= B0 + 4999; target++) {
          OptionalLong expected = target == B0 + 4999 ? OptionalLong.empty()
              : OptionalLong.of(target + 1);
          assertEquals(expected, current.firstChangeAfter("code", key(0), target, B0 + 4999));
        }
      }
    }
  }

  @Test
  public void appendsToAFullTailKeepItAndStartTheNextPage() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath().resolve("single-v1");
    byte[] directoryKey = ServingIndexKeyCodec.keyPageDirectoryKey("code", key(0));
    byte[] fullPage;
    try (ServingIndexStore store = open(directory)) {
      store.commit(plan(store, blocks(B0, FIRST_PAGE_ENTRIES, 1)), false);
      try (ArchiveIndexStorage.Reader oldSnapshot = store.snapshot();
           PersistentServingKeyIndexGeneration old = view(directory, oldSnapshot)) {
        fullPage = oldSnapshot.get(ServingIndexKeyCodec.keyPageKey("code", key(0), 0));
        List<PageDescriptor> oldPages = ServingIndexRecordCodec.decodePageDirectory(
            oldSnapshot.get(directoryKey), FIRST_PAGE_ENTRIES, B0 + FIRST_PAGE_ENTRIES - 1);
        assertEquals(1, oldPages.size());
        store.commit(plan(store, blocks(B0 + FIRST_PAGE_ENTRIES, 1, 1)), false);
        // The pre-append snapshot still answers from its own single-page directory.
        assertEquals(OptionalLong.of(B0 + FIRST_PAGE_ENTRIES - 1),
            old.firstChangeAfter("code", key(0), B0 + FIRST_PAGE_ENTRIES - 2,
                B0 + FIRST_PAGE_ENTRIES - 1));
        try (ArchiveIndexStorage.Reader snapshot = store.snapshot();
             PersistentServingKeyIndexGeneration current = view(directory, snapshot)) {
          // The full page is immutable: identical bytes, and no rewrite was needed.
          assertArrayEquals(fullPage,
              snapshot.get(ServingIndexKeyCodec.keyPageKey("code", key(0), 0)));
          List<PageDescriptor> pages = ServingIndexRecordCodec.decodePageDirectory(
              snapshot.get(directoryKey), FIRST_PAGE_ENTRIES + 1, B0 + FIRST_PAGE_ENTRIES);
          assertEquals(2, pages.size());
          assertEquals(FIRST_PAGE_ENTRIES, pages.get(0).entries());
          assertEquals(1, pages.get(1).entries());
          assertEquals(B0 + FIRST_PAGE_ENTRIES, pages.get(1).maxEpoch());
          assertEquals(OptionalLong.of(B0 + FIRST_PAGE_ENTRIES),
              current.firstChangeAfter("code", key(0), B0 + FIRST_PAGE_ENTRIES - 1,
                  B0 + FIRST_PAGE_ENTRIES));
          assertEquals(2, current.inspectStatistics().getStores().get("code").getPageCount());
        }
      }
    }
  }

  @Test
  public void batchingNeverChangesThePersistedBytes() throws Exception {
    Path whole = temporaryFolder.newFolder().toPath().resolve("single-v1");
    Path split = temporaryFolder.newFolder().toPath().resolve("single-v1");
    try (ServingIndexStore store = open(whole)) {
      store.commit(plan(store, blocks(B0, 5000, 1)), false);
    }
    try (ServingIndexStore store = open(split)) {
      for (int start = 0; start < 5000; start += 50) {
        store.commit(plan(store, blocks(B0 + start, 50, 1)), false);
      }
      assertEquals(B0 + 4999, store.indexedThrough());
    }
    try (ServingIndexStore wholeStore = open(whole);
         ServingIndexStore splitStore = open(split);
         ArchiveIndexStorage.Reader wholeSnapshot = wholeStore.snapshot();
         ArchiveIndexStorage.Reader splitSnapshot = splitStore.snapshot()) {
      assertArrayEquals(wholeSnapshot.get(ServingIndexKeyCodec.keyPageKey("code", key(0), 0)),
          splitSnapshot.get(ServingIndexKeyCodec.keyPageKey("code", key(0), 0)));
      assertArrayEquals(wholeSnapshot.get(ServingIndexKeyCodec.keyPageKey("code", key(0), 1)),
          splitSnapshot.get(ServingIndexKeyCodec.keyPageKey("code", key(0), 1)));
      assertArrayEquals(
          wholeSnapshot.get(ServingIndexKeyCodec.keyPageDirectoryKey("code", key(0))),
          splitSnapshot.get(ServingIndexKeyCodec.keyPageDirectoryKey("code", key(0))));
      assertArrayEquals(wholeSnapshot.get(ServingIndexKeyCodec.keyMetaKey("code", key(0))),
          splitSnapshot.get(ServingIndexKeyCodec.keyMetaKey("code", key(0))));
    }
  }

  @Test
  public void replayRejectsMissingDirectoryInAlreadyWrittenSoloGroup() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath().resolve("single-v1");
    long solo = seedInterruptedSolo(directory);
    try (ArchiveIndexStorage.Writer writer = ArchiveIndexStorage.openWriter(
        directory.resolve("keys"), Engine.ROCKSDB)) {
      byte[] directoryKey = ServingIndexKeyCodec.keyPageDirectoryKey("code", key(0));
      writer.write(Collections.singletonList(ArchiveIndexStorage.delete(directoryKey)), true);
    }
    try (ServingIndexStore store = open(directory)) {
      assertThrows(ArchivePersistenceException.class,
          () -> store.commit(plan(store, blocks(solo, 1, 2)), false));
      assertEquals(solo - 1, store.indexedThrough());
    }
  }

  @Test
  public void legacySoloReplayPreservesRolledPageAndAppendsRemainingEpochs() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath().resolve("single-v1");
    long solo = seedInterruptedSolo(directory);
    try (ServingIndexStore store = open(directory)) {
      assertEquals(solo - 1, store.indexedThrough());
      store.commit(plan(store, blocks(solo, 2, 2)), false);
    }
    try (ServingIndexStore store = open(directory)) {
      assertEquals(solo + 1, store.indexedThrough());
      assertEquals(FIRST_PAGE_ENTRIES + 4, store.keyChanges());
      try (ArchiveIndexStorage.Reader snapshot = store.snapshot();
           PersistentServingKeyIndexGeneration current = view(directory, snapshot)) {
        List<PageDescriptor> pages = ServingIndexRecordCodec.decodePageDirectory(snapshot.get(
            ServingIndexKeyCodec.keyPageDirectoryKey("code", key(0))),
            FIRST_PAGE_ENTRIES + 2, solo + 1);
        assertEquals(2, pages.size());
        assertEquals(FIRST_PAGE_ENTRIES, pages.get(0).entries());
        assertEquals(2, pages.get(1).entries());
        for (int index = 0; index < 2; index++) {
          assertEquals(OptionalLong.of(solo),
              current.firstChangeAfter("code", key(index), solo - 1, solo + 1));
          assertEquals(OptionalLong.of(solo + 1),
              current.firstChangeAfter("code", key(index), solo, solo + 1));
        }
      }
    }
  }

  private static long seedInterruptedSolo(Path directory) throws IOException {
    long solo = B0 + FIRST_PAGE_ENTRIES;
    try (ServingIndexStore store = open(directory)) {
      store.commit(plan(store, blocks(B0, FIRST_PAGE_ENTRIES, 1)), false);
    }
    try (ArchiveIndexStorage.Writer writer = ArchiveIndexStorage.openWriter(
        directory.resolve("keys"), Engine.ROCKSDB)) {
      List<ArchiveIndexStorage.Mutation> mutations = new ArrayList<>();
      PersistentServingKeyIndexGeneration.appendExactChanges(writer, mutations,
          new ExactKey("code", key(0)), Collections.singletonList(solo), solo - 1);
      // Rolled page, directory and KeyMeta were durable, but coverage and progress were not.
      writer.write(mutations, true);
    }
    return solo;
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
