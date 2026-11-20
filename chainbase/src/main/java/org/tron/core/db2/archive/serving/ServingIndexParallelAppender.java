package org.tron.core.db2.archive.serving;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Parallel per-key preparation for one serving index commit, Geth-style: distinct keys are
 * appended concurrently by bounded workers while one key's epochs stay strictly ordered on a
 * single worker. Workers only read the writer and produce per-chunk mutation lists; the build
 * thread still performs the single atomic sync write, so batch order, failure semantics and the
 * on-disk format are identical to the serial path.
 *
 * <p>Parallelism is {@link #THREADS_PROPERTY} (default 1, the serial path) capped at the
 * available processors. The first failure stops dispatching; in-flight workers finish their
 * current key and exit before the failure propagates, so nothing is written on failure.
 */
final class ServingIndexParallelAppender implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServingIndexParallelAppender.class);
  /** Worker threads for per-key index preparation; 1 keeps the serial path. */
  static final String THREADS_PROPERTY = "tron.archive.serving.buildThreads";
  /** Target chunk count per worker; smaller chunks balance hot and cold keys. */
  private static final int CHUNKS_PER_WORKER = 8;
  private static final long CLOSE_WAIT_SECONDS = 10;

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
   * Appends every grouped key's changes into {@code batch}, in {@code changeMap} iteration
   * order whatever the worker count. All workers of one call share {@code durableI}, exactly
   * like the serial loop reading one durable progress value.
   */
  void appendAll(ArchiveIndexStorage.Writer reader, Map<ExactKey, List<Long>> changeMap,
      List<Mutation> batch, long durableI) throws IOException {
    if (parallelism == 1 || changeMap.size() < 2) {
      for (Map.Entry<ExactKey, List<Long>> entry : changeMap.entrySet()) {
        PersistentServingKeyIndexGeneration.appendExactChanges(reader, batch, entry.getKey(),
            entry.getValue(), durableI);
      }
      return;
    }
    appendInParallel(reader, changeMap, batch, durableI);
  }

  private void appendInParallel(ArchiveIndexStorage.Writer reader,
      Map<ExactKey, List<Long>> changeMap, List<Mutation> batch, long durableI)
      throws IOException {
    List<Map.Entry<ExactKey, List<Long>>> entries = new ArrayList<>(changeMap.entrySet());
    int chunkSize = Math.max(1, entries.size() / (parallelism * CHUNKS_PER_WORKER));
    int chunkCount = (entries.size() + chunkSize - 1) / chunkSize;
    @SuppressWarnings("unchecked")
    List<Mutation>[] chunks = new List[chunkCount];
    AtomicInteger nextChunk = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    ExecutorService workers = pool();
    int workerCount = Math.min(parallelism, chunkCount);
    List<Future<ServingIndexTiming>> futures = new ArrayList<>(workerCount);
    try {
      for (int worker = 0; worker < workerCount; worker++) {
        ServingIndexTiming scope = ServingIndexTiming.openWorkerScope();
        futures.add(workers.submit(() ->
            runWorker(reader, entries, chunkSize, chunks, nextChunk, failure, durableI, scope)));
      }
      for (Future<ServingIndexTiming> future : futures) {
        future.get();
      }
    } catch (ExecutionException submitFailure) {
      // runWorker converts its own failures; this only covers submit-level errors.
      failure.compareAndSet(null, submitFailure.getCause());
    } catch (InterruptedException interrupted) {
      failure.compareAndSet(null, interrupted);
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
    for (List<Mutation> chunk : chunks) {
      batch.addAll(chunk);
    }
  }

  /** Processes chunks until none remain or a failure is recorded; never throws. */
  private ServingIndexTiming runWorker(ArchiveIndexStorage.Writer reader,
      List<Map.Entry<ExactKey, List<Long>>> entries, int chunkSize, List<Mutation>[] chunks,
      AtomicInteger nextChunk, AtomicReference<Throwable> failure, long durableI,
      ServingIndexTiming scope) {
    if (scope != null) {
      scope.attach();
    }
    try {
      for (int chunk = nextChunk.getAndIncrement(); chunk < chunks.length
          && failure.get() == null; chunk = nextChunk.getAndIncrement()) {
        List<Mutation> mutations = new ArrayList<>();
        int last = Math.min(entries.size(), (chunk + 1) * chunkSize);
        for (int index = chunk * chunkSize; index < last; index++) {
          Map.Entry<ExactKey, List<Long>> entry = entries.get(index);
          PersistentServingKeyIndexGeneration.appendExactChanges(reader, mutations,
              entry.getKey(), entry.getValue(), durableI);
        }
        chunks[chunk] = mutations;
      }
    } catch (IOException | RuntimeException | Error workerFailure) {
      failure.compareAndSet(null, workerFailure);
    } finally {
      if (scope != null) {
        scope.close();
      }
    }
    return scope;
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
