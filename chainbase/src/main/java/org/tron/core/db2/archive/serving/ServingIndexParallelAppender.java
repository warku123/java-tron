package org.tron.core.db2.archive.serving;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.core.db2.archive.serving.PersistentServingKeyIndexGeneration.ExactKey;
import org.tron.core.db2.archive.serving.telemetry.ServingIndexTiming;
import org.tron.core.db2.archive.storage.index.ArchiveIndexStorage;
import org.tron.core.db2.archive.storage.index.ArchiveIndexStorage.Mutation;

/**
 * Parallel per-key preparation for one serving index flush, Geth-style: distinct keys are
 * appended concurrently by bounded workers while one key's epochs stay strictly ordered on a
 * single worker. Workers only read the writer and produce complete per-key mutation groups;
 * the build thread still performs every write, so batch order, failure semantics and the
 * on-disk format are identical to the serial path.
 *
 * <p>Completed chunks stream through {@link ChunkSink} in key order while workers keep
 * preparing later chunks; a bounded queue caps in-flight mutations. Recovery mode re-checks
 * each key against a crashed flush: keys still at the old boundary append normally, keys the
 * interrupted flush already wrote verify against the replayed epochs and are skipped.
 *
 * <p>Parallelism is {@link #THREADS_PROPERTY} (default 1, the serial path) capped at the
 * available processors. The first failure stops dispatching; in-flight workers finish their
 * current key and exit before the failure propagates. In a streamed flush chunks already
 * delivered to the sink may be durable data batches guarded by the flush intent; recovery
 * replays and verifies them instead of assuming nothing was written.
 */
final class ServingIndexParallelAppender implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServingIndexParallelAppender.class);
  /** Worker threads for per-key index preparation; 1 keeps the serial path. */
  static final String THREADS_PROPERTY = "tron.archive.serving.buildThreads";
  /** Target chunk count per worker; smaller chunks balance hot and cold keys. */
  private static final int CHUNKS_PER_WORKER = 8;
  /** Upper bound of keys per chunk, keeping in-flight mutation memory bounded. */
  private static final int MAX_CHUNK_KEYS = 1024;
  private static final long CLOSE_WAIT_SECONDS = 10;

  /** Receives completed chunks in key order; the sink decides how writes batch. */
  @FunctionalInterface
  interface ChunkSink {

    void accept(List<Mutation> chunk) throws IOException;
  }

  /** Processes one key, returning its complete mutation group (empty when nothing to write). */
  @FunctionalInterface
  interface KeyProcessor {

    List<Mutation> process(ArchiveIndexStorage.Writer reader, ExactKey key, long[] epochs)
        throws IOException;
  }

  private final int parallelism;
  private ExecutorService pool;

  ServingIndexParallelAppender(int parallelism) {
    this.parallelism = Math.max(1, parallelism);
  }

  /** Serial unless the property selects bounded parallel workers; never above available CPUs. */
  static int resolveParallelism() {
    int requested = Integer.getInteger(THREADS_PROPERTY, 1);
    int usable = Runtime.getRuntime().availableProcessors();
    return Math.max(1, Math.min(requested, usable));
  }

  /**
   * Appends every grouped key's changes, in {@code changes} iteration order whatever the worker
   * count. All workers of one call share {@code durableI}, exactly like the serial loop reading
   * one durable progress value.
   */
  void appendAll(ArchiveIndexStorage.Writer reader, Map<ExactKey, long[]> changes,
      long durableI, ChunkSink sink) throws IOException {
    runKeys(reader, changes, (source, key, epochs) -> {
      List<Mutation> mutations = new ArrayList<>();
      PersistentServingKeyIndexGeneration.appendExactChanges(source, mutations, key, epochs,
          durableI);
      return mutations;
    }, sink);
  }

  /**
   * Recovery counterpart of {@link #appendAll}: a key still at or below {@code baseI} appends
   * normally; a key the interrupted flush already wrote (last epoch in
   * {@code (baseI, target]}) must match the replayed epochs exactly and is skipped; anything
   * beyond {@code target} is corruption.
   */
  void recoverAll(ArchiveIndexStorage.Writer reader, Map<ExactKey, long[]> changes,
      long baseI, long target, ChunkSink sink) throws IOException {
    runKeys(reader, changes, (source, key, epochs) ->
        PersistentServingKeyIndexGeneration.recoverExactChanges(source, key, epochs, baseI,
            target), sink);
  }

  private void runKeys(ArchiveIndexStorage.Writer reader, Map<ExactKey, long[]> changes,
      KeyProcessor processor, ChunkSink sink) throws IOException {
    if (changes.isEmpty()) {
      return;
    }
    List<Map.Entry<ExactKey, long[]>> entries = new ArrayList<>(changes.entrySet());
    int chunkKeys = Math.max(1, Math.min(entries.size() / (parallelism * CHUNKS_PER_WORKER),
        MAX_CHUNK_KEYS));
    int chunkCount = (entries.size() + chunkKeys - 1) / chunkKeys;
    if (parallelism == 1 || changes.size() < 2) {
      for (int chunk = 0; chunk < chunkCount; chunk++) {
        sink.accept(processChunk(reader, processor, entries, chunkKeys, chunk));
      }
      return;
    }
    runParallel(reader, processor, entries, chunkKeys, chunkCount, sink);
  }

  private void runParallel(ArchiveIndexStorage.Writer reader, KeyProcessor processor,
      List<Map.Entry<ExactKey, long[]>> entries, int chunkKeys, int chunkCount, ChunkSink sink)
      throws IOException {
    AtomicInteger nextChunk = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ExecutorService workers = pool();
    int workerCount = Math.min(parallelism, chunkCount);
    BlockingQueue<Object> completed = new ArrayBlockingQueue<>(workerCount * 2);
    List<Future<ServingIndexTiming>> futures = new ArrayList<>(workerCount);
    try {
      for (int worker = 0; worker < workerCount; worker++) {
        ServingIndexTiming scope = ServingIndexTiming.openWorkerScope();
        futures.add(workers.submit(() ->
            runWorker(reader, processor, entries, chunkKeys, chunkCount, nextChunk, failure,
                completed, scope)));
      }
      // Re-emit completed chunks in key order; the bounded queue caps buffered reorders.
      // Poll with a failure check: producers drop work once a failure is recorded, so the
      // consumer must never block indefinitely on a queue nobody feeds anymore.
      TreeMap<Integer, List<Mutation>> pending = new TreeMap<>();
      int emitted = 0;
      while (emitted < chunkCount && failure.get() == null) {
        Object taken = pollCompleted(completed);
        if (Thread.currentThread().isInterrupted()) {
          failure.compareAndSet(null, new InterruptedException());
          break;
        }
        if (!(taken instanceof IndexedChunk)) {
          continue; // poison or timeout: a worker exited or is still preparing
        }
        IndexedChunk chunk = (IndexedChunk) taken;
        pending.put(chunk.index, chunk.mutations);
        for (List<Mutation> ready = pending.remove(emitted); ready != null;
            ready = pending.remove(emitted)) {
          sink.accept(ready);
          emitted++;
        }
      }
      for (Future<ServingIndexTiming> future : futures) {
        future.get();
      }
    } catch (ExecutionException submitFailure) {
      // runWorker converts its own failures; this only covers submit-level errors.
      failure.compareAndSet(null, submitFailure.getCause());
    } catch (InterruptedException interrupted) {
      failure.compareAndSet(null, interrupted);
    } catch (IOException | RuntimeException sinkFailure) {
      // The sink aborted the flush; record it so workers stop and exit before propagating.
      failure.compareAndSet(null, sinkFailure);
    }
    if (awaitTerminated(futures)) {
      Thread.currentThread().interrupt();
    }
    for (Future<ServingIndexTiming> future : futures) {
      releaseScope(future);
    }
    Throwable cause = failure.get();
    if (cause != null) {
      if (cause instanceof InterruptedException) {
        throw new IOException("Serving parallel index build interrupted", cause);
      }
      rethrow(cause);
      return;
    }
  }

  /** Processes chunks until none remain or a failure is recorded; never throws. */
  private ServingIndexTiming runWorker(ArchiveIndexStorage.Writer reader, KeyProcessor processor,
      List<Map.Entry<ExactKey, long[]>> entries, int chunkKeys, int chunkCount,
      AtomicInteger nextChunk, AtomicReference<Throwable> failure, BlockingQueue<Object> completed,
      ServingIndexTiming scope) {
    if (scope != null) {
      scope.attach();
    }
    try {
      for (int chunk = nextChunk.getAndIncrement(); chunk < chunkCount
          && failure.get() == null; chunk = nextChunk.getAndIncrement()) {
        List<Mutation> mutations = processChunk(reader, processor, entries, chunkKeys, chunk);
        put(completed, new IndexedChunk(chunk, mutations), failure);
      }
    } catch (IOException | RuntimeException | Error workerFailure) {
      failure.compareAndSet(null, workerFailure);
    } finally {
      if (scope != null) {
        scope.close();
      }
      put(completed, POISON, failure);
    }
    return scope;
  }

  private List<Mutation> processChunk(ArchiveIndexStorage.Writer reader, KeyProcessor processor,
      List<Map.Entry<ExactKey, long[]>> entries, int chunkKeys, int chunk) throws IOException {
    List<Mutation> mutations = new ArrayList<>();
    int last = Math.min(entries.size(), (chunk + 1) * chunkKeys);
    for (int index = chunk * chunkKeys; index < last; index++) {
      Map.Entry<ExactKey, long[]> entry = entries.get(index);
      mutations.addAll(processor.process(reader, entry.getKey(), entry.getValue()));
    }
    return mutations;
  }

  private static final Object POISON = new Object();

  private static final class IndexedChunk {
    private final int index;
    private final List<Mutation> mutations;

    private IndexedChunk(int index, List<Mutation> mutations) {
      this.index = index;
      this.mutations = mutations;
    }
  }

  private static void put(BlockingQueue<Object> completed, Object item,
      AtomicReference<Throwable> failure) {
    try {
      // Offer with a failure check: once a failure is recorded the consumer stops draining,
      // so a blocking put here would wedge the worker and hang every future.get().
      while (!completed.offer(item, 100, TimeUnit.MILLISECONDS)) {
        if (failure.get() != null) {
          return; // drop the item; the flush fails anyway
        }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failure.compareAndSet(null, interrupted);
    }
  }

  /** Takes one completion, timing out so the caller can re-check the failure flag. */
  private static Object pollCompleted(BlockingQueue<Object> completed) {
    try {
      return completed.poll(100, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return POISON;
    }
  }

  /** Waits for every worker to exit (they stop after their current key once failure is set). */
  private static boolean awaitTerminated(List<Future<ServingIndexTiming>> futures) {
    boolean interrupted = false;
    for (Future<ServingIndexTiming> future : futures) {
      while (true) {
        try {
          future.get();
          break;
        } catch (InterruptedException waiting) {
          interrupted = true;
        } catch (ExecutionException unexpected) {
          break; // already routed through the failure reference
        }
      }
    }
    return interrupted;
  }

  private static void releaseScope(Future<ServingIndexTiming> future) {
    try {
      ServingIndexTiming scope = future.get();
      if (scope != null) {
        scope.releaseToParent();
      }
    } catch (InterruptedException waiting) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException unexpected) {
      // runWorker never throws; nothing to release.
    }
  }

  private static void rethrow(Throwable failure) throws IOException {
    if (failure instanceof IOException) {
      throw (IOException) failure;
    }
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw new IOException("Serving parallel index build failed", failure);
  }

  private synchronized ExecutorService pool() {
    if (pool == null) {
      AtomicInteger seq = new AtomicInteger();
      pool = Executors.newFixedThreadPool(parallelism, runnable -> {
        Thread thread = new Thread(runnable, "serving-index-build-" + seq.incrementAndGet());
        thread.setDaemon(true);
        return thread;
      });
      LOGGER.info("Serving index parallel build enabled: threads={}", parallelism);
    }
    return pool;
  }

  /** Stops accepting chunks and waits for workers; workers are never in flight here. */
  @Override
  public synchronized void close() {
    if (pool != null) {
      pool.shutdown();
      try {
        if (!pool.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          LOGGER.warn("Serving index build workers did not stop within {}s; forcing",
              CLOSE_WAIT_SECONDS);
          pool.shutdownNow();
        }
      } catch (InterruptedException interrupted) {
        pool.shutdownNow();
        Thread.currentThread().interrupt();
      } finally {
        pool = null;
      }
    }
  }
}
