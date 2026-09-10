package org.tron.common.utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.util.ReflectionUtils;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.peer.PeerManager;
import org.tron.protos.Protocol.ReasonCode;

/**
 * Test source-set utility for restoring PeerManager to a cold-start state.
 *
 * <p>{@link PeerManager#close()} disconnects visible peers but does not clear its raw static
 * peers or counters. Its static executor also remains shut down and is not rebuilt by normal
 * initialization. Tests reuse one JVM across Spring contexts, so this reset is needed before
 * another test starts.
 *
 * <p>The executor is drained first because {@code check()} is not synchronized: it iterates a
 * snapshot of the peers list and then removes entries and decrements the counters. A reset
 * that only cleared the list and counters while an old {@code check()} task was still running
 * or queued would let that task decrement the freshly zeroed counters afterwards, leaving
 * negative counts. {@code synchronized} on reset alone cannot prevent this, so the old
 * executor must be shut down and awaited before any state is touched.
 *
 * <p>The peers list is only cleared after a best-effort disconnect of its live entries.
 * There is no dedicated teardown path, and {@link PeerManager#close()} may fail midway, so
 * the raw list can still hold peers whose channels are alive; a bare {@code clear()} would
 * orphan those connections. Each peer is disconnected individually and defensively (null
 * channel tolerated, per-peer try/catch) so that one broken entry cannot abort the cleanup.
 *
 * <p>For tests that exercise p2p, reset now terminates any already scheduled
 * {@code check()}/{@code logPeerStats()} tasks and installs a fresh executor (its thread is
 * created lazily, and no task is scheduled again until the next {@code init()}). This is safe
 * for tests: {@code check()} only removes peers whose channel has been half-disconnected for
 * over 60 seconds and {@code logPeerStats()} only logs metrics, and no test depends on
 * either. Reset also does not interlock with a concurrently running {@code init()} or
 * {@code close()}; the sequential test lifecycle keeps that window out of reach.
 *
 * <p>The current BaseTest/BaseMethodTest wiring is intentionally broad to protect tests that
 * reuse Spring contexts or the JVM from unknown preceding pollution. For tests that do not use
 * PeerManager, reset is idempotent and low-impact: it typically clears an empty list and zeros
 * counters, while the executor swap is cheap (an idle old executor stops immediately and the
 * fresh executor only creates its thread on demand). If production PeerManager lifecycle
 * becomes restart-safe, this wiring can be narrowed
 * or this utility can be removed in the future.
 */
public final class PeerManagerStateResetter {

  private static final String EXECUTOR_NAME = "peer-manager";

  private PeerManagerStateResetter() {
  }

  public static synchronized void reset() {
    // 1) Drain the old executor first: let running/queued check() tasks die out so they
    // cannot interleave with the list/counter reset below.
    ScheduledExecutorService executor = getFieldValue("executor");
    if (executor != null && !executor.isShutdown()) {
      ExecutorServiceManager.shutdownAndAwaitTermination(executor, EXECUTOR_NAME);
    }
    // 2) Unconditionally install a fresh executor (the old one may be shut down or null);
    // its thread is created lazily.
    setFieldValue("executor",
        ExecutorServiceManager.newSingleThreadScheduledExecutor(EXECUTOR_NAME));

    // 3) Release residual live connections before clearing the raw list.
    List<PeerConnection> peers = getFieldValue("peers");
    if (peers == null) {
      setFieldValue("peers", Collections.synchronizedList(new ArrayList<PeerConnection>()));
    } else {
      for (PeerConnection peer : new ArrayList<>(peers)) {
        try {
          if (!peer.isDisconnect()) {
            peer.disconnect(ReasonCode.PEER_QUITING);
            if (peer.getChannel() != null) {
              peer.getChannel().close();
            }
          }
        } catch (Exception e) {
          // best effort: a single corrupted leftover peer must not fail the reset
        }
      }
      peers.clear();
    }

    // 4) Zero the counters; old tasks can no longer decrement them at this point.
    AtomicInteger active = PeerManager.getActivePeersCount();
    AtomicInteger passive = PeerManager.getPassivePeersCount();
    active.set(0);
    passive.set(0);
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
