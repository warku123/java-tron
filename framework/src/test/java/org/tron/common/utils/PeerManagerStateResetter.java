package org.tron.common.utils;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.util.ReflectionUtils;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.peer.PeerManager;

/**
 * Test source-set utility for restoring PeerManager to a cold-start state.
 *
 * <p>{@link PeerManager#close()} disconnects visible peers but does not clear its raw static
 * peers or counters. Its static executor also remains shut down and is not rebuilt by normal
 * initialization. Tests reuse one JVM across Spring contexts, so this reset is needed before
 * another test starts.
 *
 * <p>The current BaseTest/BaseMethodTest wiring is intentionally broad to protect tests that
 * reuse Spring contexts or the JVM from unknown preceding pollution. For tests that do not use
 * PeerManager, reset is idempotent and low-impact: it typically clears an empty list and zeros
 * counters, while the executor is replaced only when dead or null and threads are created on
 * demand. If production PeerManager lifecycle becomes restart-safe, this wiring can be narrowed
 * or this utility can be removed in the future.
 */
public final class PeerManagerStateResetter {

  private static final String EXECUTOR_NAME = "peer-manager";

  private PeerManagerStateResetter() {
  }

  public static synchronized void reset() {
    List<PeerConnection> peers = getFieldValue("peers");
    if (peers != null) {
      peers.clear();
    } else {
      setFieldValue("peers",
          Collections.synchronizedList(new java.util.ArrayList<PeerConnection>()));
    }

    AtomicInteger active = PeerManager.getActivePeersCount();
    AtomicInteger passive = PeerManager.getPassivePeersCount();
    active.set(0);
    passive.set(0);

    ScheduledExecutorService executor =
        getFieldValue("executor");
    if (executor == null || executor.isShutdown() || executor.isTerminated()) {
      setFieldValue("executor",
          ExecutorServiceManager.newSingleThreadScheduledExecutor(EXECUTOR_NAME));
    }
  }

  private static <T> T getFieldValue(String fieldName) {
    Field field = ReflectionUtils.findField(PeerManager.class, fieldName);
    ReflectionUtils.makeAccessible(field);
    return (T) ReflectionUtils.getField(field, null);
  }

  private static void setFieldValue(String fieldName, Object value) {
    Field field = ReflectionUtils.findField(PeerManager.class, fieldName);
    ReflectionUtils.makeAccessible(field);
    ReflectionUtils.setField(field, null, value);
  }
}
