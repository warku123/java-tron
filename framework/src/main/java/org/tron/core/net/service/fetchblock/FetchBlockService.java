package org.tron.core.net.service.fetchblock;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.Metrics;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.message.adv.FetchInvDataMessage;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerConnection;
import org.tron.protos.Protocol.Inventory.InventoryType;

@Slf4j(topic = "net")
@Component
public class FetchBlockService {

  /**
   * Throwaway decision-trace switch for the phase1 instrumentation branch. Enable with
   * -Dfetch.trace=true; off by default so behavior and log volume are unchanged.
   */
  private static final boolean TRACE = Boolean.getBoolean("fetch.trace");

  private static final Logger traceLogger = LoggerFactory.getLogger("fetch-trace");

  @Autowired
  private TronNetDelegate tronNetDelegate;

  @Autowired
  private ChainBaseManager chainBaseManager;

  private FetchBlockInfo fetchBlockInfo = null;

  private final long fetchTimeOut = CommonParameter.getInstance().fetchBlockTimeout;

  private static final double BLOCK_FETCH_LEFT_TIME_PERCENT = 0.5;

  private final String esName = "fetch-block";

  private final ScheduledExecutorService fetchBlockWorkerExecutor =
      ExecutorServiceManager.newSingleThreadScheduledExecutor(esName);

  public void init() {
    fetchBlockWorkerExecutor.scheduleWithFixedDelay(() -> {
      try {
        fetchBlockProcess(fetchBlockInfo);
      } catch (Exception e) {
        logger.error("FetchBlockWorkerSchedule thread error", e);
      }
    }, 0L, 50L, TimeUnit.MILLISECONDS);
  }

  public void close() {
    ExecutorServiceManager.shutdownAndAwaitTermination(fetchBlockWorkerExecutor, esName);
  }

  public void fetchBlock(List<Sha256Hash> sha256HashList, PeerConnection peer) {
    if (sha256HashList.size() > 0) {
      logger.info("Begin fetch block {} from {}",
          new BlockCapsule.BlockId(sha256HashList.get(0)).getString(),
          peer.getInetAddress());
    }
    if (null != fetchBlockInfo) {
      return;
    }
    sha256HashList.stream().filter(sha256Hash -> new BlockCapsule.BlockId(sha256Hash).getNum()
        == chainBaseManager.getHeadBlockNum() + 1)
        .findFirst().ifPresent(sha256Hash -> {
          long now = System.currentTimeMillis();
          fetchBlockInfo = new FetchBlockInfo(sha256Hash, peer, now);
          Metrics.counterInc(MetricKeys.Counter.BLOCK_FETCH_ARMED, 1);
          logger.info("Set fetchBlockInfo, block: {}, peer: {}, time: {}", sha256Hash,
              peer.getInetAddress(), now);
        });
  }


  public void blockFetchSuccess(Sha256Hash sha256Hash) {
    logger.info("Fetch block success, {}", new BlockCapsule.BlockId(sha256Hash).getString());
    FetchBlockInfo fetchBlockInfoTemp = this.fetchBlockInfo;
    if (null == fetchBlockInfoTemp || !fetchBlockInfoTemp.getHash().equals(sha256Hash)) {
      return;
    }
    this.fetchBlockInfo = null;
  }

  private void fetchBlockProcess(FetchBlockInfo fetchBlock) {
    if (null == fetchBlock) {
      return;
    }
    Item item = new Item(fetchBlock.getHash(), InventoryType.BLOCK);
    Optional<PeerConnection> optionalPeerConnection = tronNetDelegate.getActivePeer().stream()
        .filter(PeerConnection::isIdle)
        .filter(filterPeer -> !filterPeer.equals(fetchBlock.getPeer()))
        .filter(filterPeer -> filterPeer.getAdvInvReceive().getIfPresent(item) != null)
        // Seeded estimates are clamped to the fetch timeout; the unseeded channel-latency
        // fallback is not, but min() ordering and the saturation gate keep it safe.
        .min(Comparator.comparingDouble(this::getPeerLatency));

    // Branch recorded by shouldFetchBlock via the out-param; boolean logic and the
    // shouldFetchBlock-then-checkAndPutAdvInvRequest short-circuit are unchanged.
    String decision;
    PeerConnection target = null;
    if (optionalPeerConnection.isPresent()) {
      PeerConnection firstPeer = optionalPeerConnection.get();
      String[] branch = new String[1];
      if (shouldFetchBlock(firstPeer, fetchBlock, branch)
          && firstPeer.checkAndPutAdvInvRequest(item, System.currentTimeMillis())) {
        firstPeer.sendMessage(new FetchInvDataMessage(Collections.singletonList(item.getHash()),
            item.getType()));
        Metrics.counterInc(MetricKeys.Counter.BLOCK_FETCH_SECONDARY, 1);
        this.fetchBlockInfo = null;
        target = firstPeer;
      }
      decision = branch[0];
    } else {
      if (System.currentTimeMillis() - fetchBlock.getTime() >= fetchTimeOut) {
        logger.info("Clear fetchBlockInfo due to fetch block {} timeout {}ms",
                fetchBlock.getHash(), fetchTimeOut);
        this.fetchBlockInfo = null;
        decision = "timeout-clear";
      } else {
        decision = "no-candidate";
      }
    }
    if (TRACE) {
      traceDecision(fetchBlock, optionalPeerConnection, decision, target);
    }
  }

  private boolean shouldFetchBlock(PeerConnection newPeer, FetchBlockInfo fetchBlock,
      String[] branch) {
    double newPeerLatency = getPeerLatency(newPeer);
    double oldPeerLatency = getPeerLatency(fetchBlock.getPeer());
    long oldPeerSpendTime = System.currentTimeMillis() - fetchBlock.getTime();
    // Switch unconditionally on a hard timeout: an unseeded or saturated old peer must not
    // permanently wedge fetchBlockInfo.
    if (oldPeerSpendTime >= fetchTimeOut) {
      branch[0] = "hard-timeout";
      return true;
    }

    // Require a strictly better peer for the latency saturation gate to prevent 500v500 flapping.
    if (oldPeerLatency >= fetchTimeOut && newPeerLatency < oldPeerLatency) {
      branch[0] = "saturation-gate";
      return true;
    }

    double oldPeerLeftTime = oldPeerLatency - oldPeerSpendTime;
    branch[0] = newPeerLatency < oldPeerLeftTime * BLOCK_FETCH_LEFT_TIME_PERCENT
        && oldPeerSpendTime + newPeerLatency < fetchTimeOut
        ? "comparison-pass" : "comparison-fail";
    return "comparison-pass".equals(branch[0]);
  }

  private void traceDecision(FetchBlockInfo fetchBlock, Optional<PeerConnection> candidate,
      String decision, PeerConnection target) {
    StringBuilder sb = new StringBuilder(224);
    sb.append("decision=").append(decision)
        .append(" hash=").append(fetchBlock.getHash().toString(), 0, 8)
        .append(" oldPeer=").append(fetchBlock.getPeer().getInetAddress())
        .append(" spendTimeMs=").append(System.currentTimeMillis() - fetchBlock.getTime())
        .append(" oldEstimateMs=").append(getPeerLatency(fetchBlock.getPeer()));
    if (candidate.isPresent()) {
      sb.append(" candidate=").append(candidate.get().getInetAddress())
          .append(" candidateEstimateMs=").append(getPeerLatency(candidate.get()));
    } else {
      sb.append(" candidate=-");
    }
    sb.append(" sent=").append(target != null);
    if (target != null) {
      sb.append(" target=").append(target.getInetAddress());
    }
    traceLogger.info(sb.toString());
  }

  private double getPeerLatency(PeerConnection peerConnection) {
    return peerConnection.getFetchLatency();
  }

  private static class FetchBlockInfo {

    @Getter
    @Setter
    private PeerConnection peer;

    @Getter
    @Setter
    private Sha256Hash hash;

    @Getter
    @Setter
    private long time;

    public FetchBlockInfo(Sha256Hash hash, PeerConnection peer, long time) {
      this.peer = peer;
      this.hash = hash;
      this.time = time;
    }

  }

}
