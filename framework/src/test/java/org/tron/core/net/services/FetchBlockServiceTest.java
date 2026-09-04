package org.tron.core.net.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.service.fetchblock.FetchBlockService;
import org.tron.p2p.connection.Channel;
import org.tron.protos.Protocol.Inventory.InventoryType;

public class FetchBlockServiceTest extends BaseMethodTest {

  private FetchBlockService service;
  private TronNetDelegate tronNetDelegate;

  @Override
  protected void afterInit() {
    service = context.getBean(FetchBlockService.class);
    tronNetDelegate = mock(TronNetDelegate.class);
    ReflectUtils.setFieldValue(service, "tronNetDelegate", tronNetDelegate);
  }

  /**
   * Verify that fetchBlockProcess selects the idle peer with the lowest avg latency
   * (excluding the peer we are already fetching from) and sends it a FetchInvDataMessage.
   *
   * <p>Covers the migrated getPeerLatency / shouldFetchBlock path that replaced the
   * legacy Dropwizard per-IP histogram P75 selection.
   */
  @Test
  public void testSelectLowestLatencyPeer() throws Exception {
    InetSocketAddress oldAddr = new InetSocketAddress("127.0.0.1", 10001);
    InetSocketAddress newAddr = new InetSocketAddress("127.0.0.2", 10001);

    Channel oldChannel = mock(Channel.class);
    when(oldChannel.getInetSocketAddress()).thenReturn(oldAddr);
    when(oldChannel.getInetAddress()).thenReturn(oldAddr.getAddress());
    when(oldChannel.getAvgLatency()).thenReturn(200L);
    doNothing().when(oldChannel).send(any(byte[].class));

    Channel newChannel = mock(Channel.class);
    when(newChannel.getInetSocketAddress()).thenReturn(newAddr);
    when(newChannel.getInetAddress()).thenReturn(newAddr.getAddress());
    when(newChannel.getAvgLatency()).thenReturn(50L);
    doNothing().when(newChannel).send(any(byte[].class));

    PeerConnection oldPeer = context.getBean(PeerConnection.class);
    oldPeer.setChannel(oldChannel);
    oldPeer.updateFetchLatency(200L);
    PeerConnection newPeer = context.getBean(PeerConnection.class);
    newPeer.setChannel(newChannel);
    newPeer.updateFetchLatency(50L);

    Sha256Hash hash = Sha256Hash.wrap(ByteString.copyFrom(new byte[32]));
    Item item = new Item(hash, InventoryType.BLOCK);

    // both peers advertise having the block
    oldPeer.getAdvInvReceive().put(item, System.currentTimeMillis());
    newPeer.getAdvInvReceive().put(item, System.currentTimeMillis());

    List<PeerConnection> activePeers = new ArrayList<>();
    activePeers.add(oldPeer);
    activePeers.add(newPeer);
    when(tronNetDelegate.getActivePeer()).thenReturn(activePeers);

    // seed fetchBlockInfo via reflection (private static nested class)
    Class<?> fetchBlockInfoClass = Class.forName(
        "org.tron.core.net.service.fetchblock.FetchBlockService$FetchBlockInfo");
    Constructor<?> constructor = fetchBlockInfoClass.getDeclaredConstructor(
        Sha256Hash.class, PeerConnection.class, long.class);
    constructor.setAccessible(true);
    Object fetchBlockInfo = constructor.newInstance(
        hash, oldPeer, System.currentTimeMillis());
    ReflectUtils.setFieldValue(service, "fetchBlockInfo", fetchBlockInfo);

    // invoke private fetchBlockProcess
    Method method = FetchBlockService.class.getDeclaredMethod(
        "fetchBlockProcess", fetchBlockInfoClass);
    method.setAccessible(true);
    method.invoke(service, fetchBlockInfo);

    // new peer (lowest latency) should receive the fetch request
    verify(newChannel).send(any(byte[].class));
    // old peer should not be re-requested
    verify(oldChannel, never()).send(any(byte[].class));
    // fetchBlockInfo should be cleared after successful dispatch
    Assert.assertNull(ReflectUtils.getFieldObject(service, "fetchBlockInfo"));
  }

  /**
   * A 600ms seed is clamped to 500ms, and >= timeout triggers fast-switch.
   */
  @Test
  public void testSwitchOnOldPeerTimeout() throws Exception {
    InetSocketAddress oldAddr = new InetSocketAddress("127.0.0.3", 10001);
    InetSocketAddress newAddr = new InetSocketAddress("127.0.0.4", 10001);

    Channel oldChannel = mock(Channel.class);
    when(oldChannel.getInetSocketAddress()).thenReturn(oldAddr);
    when(oldChannel.getInetAddress()).thenReturn(oldAddr.getAddress());
    // seed above timeout; estimator clamps this to default fetchBlockTimeout (500)
    when(oldChannel.getAvgLatency()).thenReturn(600L);
    doNothing().when(oldChannel).send(any(byte[].class));

    Channel newChannel = mock(Channel.class);
    when(newChannel.getInetSocketAddress()).thenReturn(newAddr);
    when(newChannel.getInetAddress()).thenReturn(newAddr.getAddress());
    when(newChannel.getAvgLatency()).thenReturn(50L);
    doNothing().when(newChannel).send(any(byte[].class));

    PeerConnection oldPeer = context.getBean(PeerConnection.class);
    ReflectUtils.setFieldValue(oldPeer, "channel", oldChannel);
    oldPeer.updateFetchLatency(600L);

    PeerConnection newPeer = context.getBean(PeerConnection.class);
    newPeer.setChannel(newChannel);
    newPeer.updateFetchLatency(50L);

    Sha256Hash hash = Sha256Hash.wrap(ByteString.copyFrom(new byte[32]));
    Item item = new Item(hash, InventoryType.BLOCK);

    newPeer.getAdvInvReceive().put(item, System.currentTimeMillis());

    List<PeerConnection> activePeers = new ArrayList<>();
    activePeers.add(oldPeer);
    activePeers.add(newPeer);
    when(tronNetDelegate.getActivePeer()).thenReturn(activePeers);

    Class<?> fetchBlockInfoClass = Class.forName(
        "org.tron.core.net.service.fetchblock.FetchBlockService$FetchBlockInfo");
    Constructor<?> constructor = fetchBlockInfoClass.getDeclaredConstructor(
        Sha256Hash.class, PeerConnection.class, long.class);
    constructor.setAccessible(true);
    Object fetchBlockInfo = constructor.newInstance(
        hash, oldPeer, System.currentTimeMillis());
    ReflectUtils.setFieldValue(service, "fetchBlockInfo", fetchBlockInfo);

    Method method = FetchBlockService.class.getDeclaredMethod(
        "fetchBlockProcess", fetchBlockInfoClass);
    method.setAccessible(true);
    method.invoke(service, fetchBlockInfo);

    verify(newChannel).send(any(byte[].class));
    Assert.assertNull(ReflectUtils.getFieldObject(service, "fetchBlockInfo"));
  }

  @Test
  public void testSwitchOnHardTimeoutWhenOldPeerUnseeded() throws Exception {
    PeerConnection oldPeer = context.getBean(PeerConnection.class);
    PeerConnection candidate = context.getBean(PeerConnection.class);
    Channel oldChannel = mock(Channel.class);
    Channel candidateChannel = mock(Channel.class);
    when(oldChannel.getAvgLatency()).thenReturn(0L);
    when(candidateChannel.getAvgLatency()).thenReturn(50L);
    ReflectUtils.setFieldValue(oldPeer, "channel", oldChannel);
    ReflectUtils.setFieldValue(candidate, "channel", candidateChannel);
    candidate.updateFetchLatency(50L);
    doNothing().when(candidateChannel).send(any(byte[].class));

    Sha256Hash hash = Sha256Hash.wrap(ByteString.copyFrom(new byte[32]));
    Item item = new Item(hash, InventoryType.BLOCK);
    candidate.getAdvInvReceive().put(item, System.currentTimeMillis());
    when(tronNetDelegate.getActivePeer()).thenReturn(Arrays.asList(oldPeer, candidate));

    Class<?> fetchBlockInfoClass = Class.forName(
        "org.tron.core.net.service.fetchblock.FetchBlockService$FetchBlockInfo");
    Constructor<?> constructor = fetchBlockInfoClass.getDeclaredConstructor(
        Sha256Hash.class, PeerConnection.class, long.class);
    constructor.setAccessible(true);
    Object fetchBlockInfo = constructor.newInstance(
        hash, oldPeer, System.currentTimeMillis() - 600);
    ReflectUtils.setFieldValue(service, "fetchBlockInfo", fetchBlockInfo);
    Method method = FetchBlockService.class.getDeclaredMethod(
        "fetchBlockProcess", fetchBlockInfoClass);
    method.setAccessible(true);
    method.invoke(service, fetchBlockInfo);

    verify(candidateChannel).send(any(byte[].class));
    Assert.assertNull(ReflectUtils.getFieldObject(service, "fetchBlockInfo"));
  }

  /**
   * When the old peer is unseeded, its fetch latency is 0 and the candidate latency is 144
   * ((50 * 9 + 999) / 10), so failover must not happen while the fetch is still within timeout.
   */
  @Test
  public void testNoSwitchWhenOldPeerLatencyUnknown() throws Exception {
    InetSocketAddress oldAddr = new InetSocketAddress("127.0.0.5", 10001);
    InetSocketAddress newAddr = new InetSocketAddress("127.0.0.6", 10001);

    Channel oldChannel = mock(Channel.class);
    when(oldChannel.getInetSocketAddress()).thenReturn(oldAddr);
    when(oldChannel.getInetAddress()).thenReturn(oldAddr.getAddress());
    // old peer latency unknown
    when(oldChannel.getAvgLatency()).thenReturn(0L);
    doNothing().when(oldChannel).send(any(byte[].class));

    Channel newChannel = mock(Channel.class);
    when(newChannel.getInetSocketAddress()).thenReturn(newAddr);
    when(newChannel.getInetAddress()).thenReturn(newAddr.getAddress());
    when(newChannel.getAvgLatency()).thenReturn(50L);
    doNothing().when(newChannel).send(any(byte[].class));

    PeerConnection oldPeer = context.getBean(PeerConnection.class);
    oldPeer.setChannel(oldChannel);

    PeerConnection newPeer = context.getBean(PeerConnection.class);
    newPeer.setChannel(newChannel);
    newPeer.updateFetchLatency(999L);

    Sha256Hash hash = Sha256Hash.wrap(ByteString.copyFrom(new byte[32]));
    Item item = new Item(hash, InventoryType.BLOCK);

    oldPeer.getAdvInvReceive().put(item, System.currentTimeMillis());
    newPeer.getAdvInvReceive().put(item, System.currentTimeMillis());

    List<PeerConnection> activePeers = new ArrayList<>();
    activePeers.add(oldPeer);
    activePeers.add(newPeer);
    when(tronNetDelegate.getActivePeer()).thenReturn(activePeers);

    Class<?> fetchBlockInfoClass = Class.forName(
        "org.tron.core.net.service.fetchblock.FetchBlockService$FetchBlockInfo");
    Constructor<?> constructor = fetchBlockInfoClass.getDeclaredConstructor(
        Sha256Hash.class, PeerConnection.class, long.class);
    constructor.setAccessible(true);
    Object fetchBlockInfo = constructor.newInstance(
        hash, oldPeer, System.currentTimeMillis());
    ReflectUtils.setFieldValue(service, "fetchBlockInfo", fetchBlockInfo);

    Method method = FetchBlockService.class.getDeclaredMethod(
        "fetchBlockProcess", fetchBlockInfoClass);
    method.setAccessible(true);
    method.invoke(service, fetchBlockInfo);

    // no failover: candidate peer must not receive a fetch request
    verify(newChannel, never()).send(any(byte[].class));
    // in-flight fetchBlockInfo stays pending
    Assert.assertNotNull(ReflectUtils.getFieldObject(service, "fetchBlockInfo"));
  }

  /**
   * Both peers are unseeded, so zero latency suppresses failover.
   */
  @Test
  public void testNoSwitchWhenBothPeersUnseeded() throws Exception {
    InetSocketAddress oldAddr = new InetSocketAddress("127.0.0.7", 10001);
    InetSocketAddress newAddr = new InetSocketAddress("127.0.0.8", 10001);

    Channel oldChannel = mock(Channel.class);
    when(oldChannel.getInetSocketAddress()).thenReturn(oldAddr);
    when(oldChannel.getInetAddress()).thenReturn(oldAddr.getAddress());
    when(oldChannel.getAvgLatency()).thenReturn(200L);
    doNothing().when(oldChannel).send(any(byte[].class));

    Channel newChannel = mock(Channel.class);
    when(newChannel.getInetSocketAddress()).thenReturn(newAddr);
    when(newChannel.getInetAddress()).thenReturn(newAddr.getAddress());
    // candidate remains unseeded, so its fetch latency is zero
    when(newChannel.getAvgLatency()).thenReturn(0L);
    doNothing().when(newChannel).send(any(byte[].class));

    PeerConnection oldPeer = context.getBean(PeerConnection.class);
    oldPeer.setChannel(oldChannel);
    PeerConnection newPeer = context.getBean(PeerConnection.class);
    newPeer.setChannel(newChannel);

    Sha256Hash hash = Sha256Hash.wrap(ByteString.copyFrom(new byte[32]));
    Item item = new Item(hash, InventoryType.BLOCK);

    oldPeer.getAdvInvReceive().put(item, System.currentTimeMillis());
    newPeer.getAdvInvReceive().put(item, System.currentTimeMillis());

    List<PeerConnection> activePeers = new ArrayList<>();
    activePeers.add(oldPeer);
    activePeers.add(newPeer);
    when(tronNetDelegate.getActivePeer()).thenReturn(activePeers);

    Class<?> fetchBlockInfoClass = Class.forName(
        "org.tron.core.net.service.fetchblock.FetchBlockService$FetchBlockInfo");
    Constructor<?> constructor = fetchBlockInfoClass.getDeclaredConstructor(
        Sha256Hash.class, PeerConnection.class, long.class);
    constructor.setAccessible(true);
    Object fetchBlockInfo = constructor.newInstance(
        hash, oldPeer, System.currentTimeMillis());
    ReflectUtils.setFieldValue(service, "fetchBlockInfo", fetchBlockInfo);

    Method method = FetchBlockService.class.getDeclaredMethod(
        "fetchBlockProcess", fetchBlockInfoClass);
    method.setAccessible(true);
    method.invoke(service, fetchBlockInfo);

    // no failover: both unseeded latencies are zero
    verify(newChannel, never()).send(any(byte[].class));
    Assert.assertNotNull(ReflectUtils.getFieldObject(service, "fetchBlockInfo"));
  }

  @Test
  public void testSwitchToUnseededCandidateWhenOldPeerSeeded() throws Exception {
    PeerConnection oldPeer = context.getBean(PeerConnection.class);
    PeerConnection candidate = context.getBean(PeerConnection.class);
    Channel oldChannel = mock(Channel.class);
    Channel candidateChannel = mock(Channel.class);
    when(oldChannel.getAvgLatency()).thenReturn(200L);
    when(candidateChannel.getAvgLatency()).thenReturn(0L);
    ReflectUtils.setFieldValue(oldPeer, "channel", oldChannel);
    oldPeer.updateFetchLatency(200L);
    ReflectUtils.setFieldValue(candidate, "channel", candidateChannel);
    doNothing().when(candidateChannel).send(any(byte[].class));

    Sha256Hash hash = Sha256Hash.wrap(ByteString.copyFrom(new byte[32]));
    Item item = new Item(hash, InventoryType.BLOCK);
    candidate.getAdvInvReceive().put(item, System.currentTimeMillis());
    when(tronNetDelegate.getActivePeer()).thenReturn(Arrays.asList(oldPeer, candidate));

    Class<?> infoClass = Class.forName(
        "org.tron.core.net.service.fetchblock.FetchBlockService$FetchBlockInfo");
    Constructor<?> constructor = infoClass.getDeclaredConstructor(
        Sha256Hash.class, PeerConnection.class, long.class);
    constructor.setAccessible(true);
    Object info = constructor.newInstance(hash, oldPeer, System.currentTimeMillis());
    ReflectUtils.setFieldValue(service, "fetchBlockInfo", info);
    Method method = FetchBlockService.class.getDeclaredMethod("fetchBlockProcess", infoClass);
    method.setAccessible(true);
    method.invoke(service, info);

    verify(candidateChannel).send(any(byte[].class));
    Assert.assertNull(ReflectUtils.getFieldObject(service, "fetchBlockInfo"));
  }

  @Test
  public void testFirstFetchLatencySampleSeedsFromChannel() {
    Channel channel = mock(Channel.class);
    when(channel.getAvgLatency()).thenReturn(123L);
    PeerConnection peer = new PeerConnection();
    ReflectUtils.setFieldValue(peer, "channel", channel);

    // First call blends channel prior with measured fetch sample.
    peer.updateFetchLatency(999L);

    Assert.assertEquals((123L * 9 + 999L) / 10, peer.getFetchLatency());
  }

  @Test
  public void testFetchLatencyUsesEwma() {
    Channel channel = mock(Channel.class);
    when(channel.getAvgLatency()).thenReturn(100L);
    PeerConnection peer = new PeerConnection();
    ReflectUtils.setFieldValue(peer, "channel", channel);

    peer.updateFetchLatency(100L);
    peer.updateFetchLatency(200L);

    Assert.assertEquals(110L, peer.getFetchLatency());
  }

  @Test
  public void testFetchLatencyIsClamped() {
    Channel channel = mock(Channel.class);
    when(channel.getAvgLatency()).thenReturn(100L);
    PeerConnection peer = new PeerConnection();
    ReflectUtils.setFieldValue(peer, "channel", channel);

    peer.updateFetchLatency(100L);
    peer.updateFetchLatency(9999L);

    Assert.assertEquals(500L, peer.getFetchLatency());
  }

  @Test
  public void testFetchLatencyIsIsolatedAcrossConnections() {
    Channel firstChannel = mock(Channel.class);
    when(firstChannel.getAvgLatency()).thenReturn(100L);
    PeerConnection first = new PeerConnection();
    ReflectUtils.setFieldValue(first, "channel", firstChannel);
    first.updateFetchLatency(9999L);

    Channel secondChannel = mock(Channel.class);
    when(secondChannel.getAvgLatency()).thenReturn(50L);
    PeerConnection second = new PeerConnection();
    ReflectUtils.setFieldValue(second, "channel", secondChannel);

    Assert.assertEquals(0L, second.getFetchLatency());
  }
}
