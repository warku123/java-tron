package org.tron.common.utils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.peer.PeerManager;

/** Resets PeerManager's process-wide test state without changing production code. */
public final class PeerManagerStateResetter {

  private static final String EXECUTOR_NAME = "peer-manager";

  private PeerManagerStateResetter() {
  }

  public static synchronized void reset() {
    List<PeerConnection> peers = ReflectUtils.getFieldValue(PeerManager.class, "peers");
    if (peers != null) {
      peers.clear();
    } else {
      ReflectUtils.setFieldValue(PeerManager.class, "peers",
          Collections.synchronizedList(new java.util.ArrayList<PeerConnection>()));
    }

    AtomicInteger active = PeerManager.getActivePeersCount();
    AtomicInteger passive = PeerManager.getPassivePeersCount();
    active.set(0);
    passive.set(0);

    ScheduledExecutorService executor =
        ReflectUtils.getFieldValue(PeerManager.class, "executor");
    if (executor == null || executor.isShutdown() || executor.isTerminated()) {
      ReflectUtils.setFieldValue(PeerManager.class, "executor",
          ExecutorServiceManager.newSingleThreadScheduledExecutor(EXECUTOR_NAME));
    }
  }
}
