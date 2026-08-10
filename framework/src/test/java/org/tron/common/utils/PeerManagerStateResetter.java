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

/** Resets PeerManager's process-wide test state without changing production code. */
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
