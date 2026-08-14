package org.tron.core.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.stereotype.Component;
import org.tron.common.prometheus.MetricKeys;
import org.tron.common.prometheus.MetricLabels;
import org.tron.common.prometheus.Metrics;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.BlockCapsule;

@Slf4j(topic = "metrics")
@Component
public class MetricsService {

  private final Map<String, BlockCapsule> witnessInfo = new ConcurrentHashMap<>();

  /**
   * apply block.
   *
   * @param block BlockCapsule
   */
  public void applyBlock(BlockCapsule block) {
    try {
      long nowTime = System.currentTimeMillis();
      byte[] address = block.getWitnessAddress().toByteArray();
      String witnessAddress = Hex.toHexString(address);
      String encodeAddress = StringUtil.encode58Check(address);

      if (witnessInfo.containsKey(witnessAddress)) {
        BlockCapsule oldBlock = witnessInfo.get(witnessAddress);
        if ((!oldBlock.getBlockId().equals(block.getBlockId()))
            && oldBlock.getTimeStamp() == block.getTimeStamp()) {
          Metrics.counterInc(MetricKeys.Counter.MINER, 1,
              encodeAddress, MetricLabels.Counter.MINE_DUP);
        }
      }
      witnessInfo.put(witnessAddress, block);

      long netTime = nowTime - block.getTimeStamp();
      Metrics.histogramObserve(MetricKeys.Histogram.MINER_LATENCY,
          netTime / Metrics.MILLISECONDS_PER_SECOND, encodeAddress);

      int txCount = block.getTransactions().size();
      if (txCount > 0) {
        Metrics.counterInc(MetricKeys.Counter.TXS, txCount,
            MetricLabels.Counter.TXS_SUCCESS, MetricLabels.Counter.TXS_SUCCESS);
      }
    } catch (Exception e) {
      logger.warn("record block failed, {}, reason: {}.",
          block.getBlockId().toString(), e.getMessage());
    }
  }

}
