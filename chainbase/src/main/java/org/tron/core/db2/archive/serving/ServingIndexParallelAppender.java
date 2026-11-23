package org.tron.core.db2.archive.serving;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Parallel per-key preparation for one serving index flush, Geth-style: workers claim the
 * next unclaimed key from a shared cursor, so distinct keys are appended concurrently while
 * one key's epochs stay strictly ordered on a single worker. Workers only read the writer
 * and produce complete per-key mutation groups; the build thread still performs every write,
 * so failure semantics and the on-disk format are identical to the serial path.
 *
 * <p>Finished key groups stream through {@link ChunkSink} in completion order while workers
 * keep preparing later keys; a bounded queue caps in-flight groups. Only a key's own epochs
 * must stay ordered, so a slow key never delays already-finished keys. Recovery mode
 * re-checks each key against a crashed flush: keys still at the old boundary append normally,
 * keys the interrupted flush already wrote verify against the replayed epochs and are
 * skipped.
 *
 * <p>Parallelism is {@link #THREADS_PROPERTY} (default 1, the serial path) capped at the
 * available processors. The first failure stops dispatching; in-flight workers finish their
 * current key and exit before the failure propagates. In a streamed flush groups already
 * delivered to the sink may be durable data batches guarded by the flush intent; recovery
 * replays and verifies them instead of assuming nothing was written.
 */
final class ServingIndexParallelAppender implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServingIndexParallelAppender.class);
  /** Worker threads for per-key index preparation; 1 keeps the serial path. */
  static final String THREADS_PROPERTY = "tron.archive.serving.buildThreads";
  private static final long CLOSE_WAIT_SECONDS = 10;

  /** Receives one key's complete mutation group in completion order; the sink batches writes. */
  @FunctionalInterface
  interface ChunkSink {

    void accept(List<Mutation> group) throws IOException;
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
   * Appends every grouped key's changes exactly once; groups reach the sink in the workers'
   * completion order, which is safe because only a key's own epochs must stay ordered. All
   * workers of one call share {@code durableI}, exactly like the serial loop reading one
   * durable progress value.
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
    if (parallelism == 1 || entries.size() < 2) {
      for (Map.Entry<ExactKey, long[]> entry : entries) {
        sink.accept(processor.process(reader, entry.getKey(), entry.getValue()));
      }
      return;
    }
    runParallel(reader, processor, entries, sink);
  }

  /**
   * One key per claim, one key per delivery: workers take the next unclaimed key from a
   * shared cursor, and the build thread consumes finished groups in completion order. Only a
   * key's own epochs must stay ordered, so no re-sequencing buffer exists and a slow key
   * never delays already-finished keys.
   */
  private void runParallel(ArchiveIndexStorage.Writer reader, KeyProcessor processor,
      List<Map.Entry<ExactKey, long[]>> entries, ChunkSink sink) throws IOException {
    AtomicInteger nextKey = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ExecutorService workers = pool();
    int workerCount = Math.min(parallelism, entries.size());
    BlockingQueue<Object> completed = new ArrayBlockingQueue<>(workerCount * 2);
    List<Future<ServingIndexTiming>> futures = new ArrayList<>(workerCount);
    try {
      for (int worker = 0; worker < workerCount; worker++) {
        ServingIndexTiming scope = ServingIndexTiming.openWorkerScope();
        futures.add(workers.submit(() ->
            runWorker(reader, processor, entries, nextKey, failure, completed, scope)));
      }
      // Consume finished key groups in completion order. Poll with a failure check:
      // producers drop work once a failure is recorded, so the consumer must never block
      // indefinitely on a queue nobody feeds anymore.
      int emitted = 0;
      while (emitted < entries.size() && failure.get() == null) {
        Object taken = pollCompleted(completed);
        if (Thread.currentThread().isInterrupted()) {
          failure.compareAndSet(null, new InterruptedException());
          break;
        }
        if (!(taken instanceof List)) {
          continue; // poison or timeout: a worker exited or is still preparing
        }
        @SuppressWarnings("unchecked")
        List<Mutation> group = (List<Mutation>) taken;
        sink.accept(group);
        emitted++;
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

  /** Claims and processes keys until none remain or a failure is recorded; never throws. */
  private ServingIndexTiming runWorker(ArchiveIndexStorage.Writer reader, KeyProcessor processor,
      List<Map.Entry<ExactKey, long[]>> entries, AtomicInteger nextKey,
      AtomicReference<Throwable> failure, BlockingQueue<Object> completed,
      ServingIndexTiming scope) {
    if (scope != null) {
      scope.attach();
    }
    try {
      for (int index = nextKey.getAndIncrement(); index < entries.size()
          && failure.get() == null; index = nextKey.getAndIncrement()) {
        Map.Entry<ExactKey, long[]> entry = entries.get(index);
        put(completed, processor.process(reader, entry.getKey(), entry.getValue()), failure);
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

  private static final Object POISON = new Object();

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
