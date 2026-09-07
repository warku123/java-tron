package org.tron.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.Collections;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.LocalWitnesses;
import org.tron.common.utils.PublicMethod;
import org.tron.consensus.base.Param;
import org.tron.consensus.base.Param.Miner;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ConsensusService;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ValidateSignatureException;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;
import org.tron.protos.contract.BalanceContract.TransferContract;
import org.tron.protos.contract.ExchangeContract.ExchangeCreateContract;
import org.tron.protos.contract.ExchangeContract.ExchangeInjectContract;
import org.tron.protos.contract.ExchangeContract.ExchangeTransactionContract;
import org.tron.protos.contract.ExchangeContract.ExchangeWithdrawContract;

/**
 * Regression tests for the CLOSE_EXCHANGE entrance gate in {@link Manager}.
 *
 * Covers:
 *  - DynamicPropertiesStore CLOSE_EXCHANGE default (missing key -> 0) and round trip.
 *  - pushTransaction truth table: level 0/1/2 x {Create, Inject, Transaction, Withdraw}
 *    plus a non-exchange contract.
 *  - The legacy 4.8.0.1 ExchangeTransaction predicate is untouched (level 0 + harden 0 still
 *    rejects with the legacy message) and allowHardenExchangeCalculation cannot bypass the
 *    new gate.
 *  - generateBlock skips closed exchange contracts but keeps packing unrelated transactions.
 *  - The block validation path (processBlock -> rejectExchangeTransaction) rejects blocks
 *    containing closed exchange contracts.
 */
@Slf4j
public class CloseExchangeGateTest extends BaseTest {

  private static final String ACCOUNT_NAME_FIRST = "ownerF";
  private static final String ACCOUNT_NAME_SECOND = "ownerS";
  private static final String OWNER_ADDRESS_FIRST;
  private static final String OWNER_ADDRESS_SECOND;
  /**
   * Valid signing key for this test. config-test.conf ships an empty localwitness list, so
   * Args.getLocalWitnesses().getPrivateKey() returns null and any ECKey.fromPrivate(...) built
   * from it yields null (NPE in packedTrx/buildLocalWitnessBlock). Mirrors the established
   * fixture pattern in ManagerTest.afterInit.
   *
   * <p>The key doubles as the owner key of OWNER_ADDRESS_SECOND: the packing
   * (generateBlock) and block-apply paths run the full processTransaction pipeline,
   * which validates transaction signatures, so executable transactions must be signed by
   * the owner key itself.
   */
  private static final String OWNER_PRIVATE_KEY = PublicMethod.getRandomPrivateKey();
  private static boolean consensusStarted = false;
  /**
   * Salt for executable transactions: identical raw bytes produce an identical transaction
   * id, which would be rejected by validateDup when a transaction is packed/executed twice
   * within the same Spring context. Kept small: the salt is added to transaction amounts,
   * which must stay far below the fixture account balances for them to be executable.
   */
  private static final java.util.concurrent.atomic.AtomicLong TRX_NONCE =
      new java.util.concurrent.atomic.AtomicLong(System.nanoTime() % 100_000L);

  @Resource
  private ConsensusService consensusService;

  static {
    Args.setParam(new String[]{"--output-directory", dbPath()}, TestConstants.TEST_CONF);
    OWNER_ADDRESS_FIRST =
        Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049abc";
    OWNER_ADDRESS_SECOND =
        Wallet.getAddressPreFixString() + ByteArray.toHexString(
            PublicMethod.getAddressByteByPrivateKey(OWNER_PRIVATE_KEY))
            .substring(Wallet.getAddressPreFixString().length());
  }

  @Before
  public void initTest() {
    // Gate level must always start from a known value; the Spring context is shared
    // between test methods of this class.
    dbManager.getDynamicPropertiesStore().saveCloseExchange(0);
    dbManager.getDynamicPropertiesStore().saveAllowHardenExchangeCalculation(0);
    // Provide a valid local witness key (see OWNER_PRIVATE_KEY javadoc).
    LocalWitnesses localWitnesses = new LocalWitnesses();
    localWitnesses.setPrivateKeys(Arrays.asList(OWNER_PRIVATE_KEY));
    localWitnesses.initWitnessAccountAddress(null, true);
    Args.setLocalWitnesses(localWitnesses);
    registerLocalWitnessForConsensus();
    AccountCapsule ownerAccountFirstCapsule =
        new AccountCapsule(
            ByteString.copyFromUtf8(ACCOUNT_NAME_FIRST),
            ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS_FIRST)),
            AccountType.Normal,
            10000_000_000L);
    AccountCapsule ownerAccountSecondCapsule =
        new AccountCapsule(
            ByteString.copyFromUtf8(ACCOUNT_NAME_SECOND),
            ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS_SECOND)),
            AccountType.Normal,
            20000_000_000L);
    dbManager.getAccountStore()
        .put(ownerAccountFirstCapsule.getAddress().toByteArray(), ownerAccountFirstCapsule);
    dbManager.getAccountStore()
        .put(ownerAccountSecondCapsule.getAddress().toByteArray(), ownerAccountSecondCapsule);
  }

  // --------------------------------------------------------------------------------------------
  // DynamicPropertiesStore
  // --------------------------------------------------------------------------------------------

  @Test
  public void closeExchangeDefaultsToZeroAndRoundTrips() {
    assertEquals(0, dbManager.getDynamicPropertiesStore().getCloseExchange());
    assertFalse(dbManager.getDynamicPropertiesStore().allowHardenExchangeCalculation());

    dbManager.getDynamicPropertiesStore().saveCloseExchange(1);
    assertEquals(1, dbManager.getDynamicPropertiesStore().getCloseExchange());
    dbManager.getDynamicPropertiesStore().saveCloseExchange(2);
    assertEquals(2, dbManager.getDynamicPropertiesStore().getCloseExchange());
  }

  // --------------------------------------------------------------------------------------------
  // pushTransaction truth table
  // --------------------------------------------------------------------------------------------

  @Test
  public void pushTransactionLevel0RejectsNothingNewAndKeepsLegacyBehavior() {
    dbManager.getDynamicPropertiesStore().saveCloseExchange(0);

    // Level 0: the new gate never fires; every exchange contract passes the gate stage.
    assertPushPassesGate(buildTrx(ContractType.ExchangeCreateContract));
    assertPushPassesGate(buildTrx(ContractType.ExchangeInjectContract));
    assertPushPassesGate(buildTrx(ContractType.ExchangeWithdrawContract));

    // ExchangeTransaction is still handled by the untouched legacy 4.8.0.1 predicate.
    ContractValidateException e = assertThrows(ContractValidateException.class,
        () -> dbManager.pushTransaction(buildTrx(ContractType.ExchangeTransactionContract)));
    assertEquals("ExchangeTransactionContract is rejected", e.getMessage());

    // Non-exchange contract: unaffected.
    assertPushPassesGate(buildTransferTrx());
  }

  @Test
  public void pushTransactionLevel1RejectsCreateInjectTransactionButNotWithdraw() {
    dbManager.getDynamicPropertiesStore().saveCloseExchange(1);

    assertGateRejects(ContractType.ExchangeCreateContract, 1);
    assertGateRejects(ContractType.ExchangeInjectContract, 1);
    // with harden=0 the untouched legacy 4.8.0.1 predicate fires before the new gate and
    // rejects with its own message; the new gate message for this type is asserted in
    // hardenModeCannotBypassCloseGate.
    assertGateRejects(ContractType.ExchangeTransactionContract, 1);

    // Withdraw requires level 2, so it passes the gate at level 1.
    assertPushPassesGate(buildTrx(ContractType.ExchangeWithdrawContract));

    // Non-exchange contract: unaffected.
    assertPushPassesGate(buildTransferTrx());
  }

  @Test
  public void pushTransactionLevel2RejectsAllExchangeContracts() {
    dbManager.getDynamicPropertiesStore().saveCloseExchange(2);

    assertGateRejects(ContractType.ExchangeCreateContract, 2);
    assertGateRejects(ContractType.ExchangeInjectContract, 2);
    assertGateRejects(ContractType.ExchangeTransactionContract, 2);
    assertGateRejects(ContractType.ExchangeWithdrawContract, 2);

    // Non-exchange contract: unaffected even at the highest level.
    assertPushPassesGate(buildTransferTrx());
  }

  @Test
  public void hardenModeCannotBypassCloseGate() {
    // allowHardenExchangeCalculation=1 disables the legacy ExchangeTransaction predicate
    // (isExchangeTransaction returns false), but the new gate must still fire.
    dbManager.getDynamicPropertiesStore().saveAllowHardenExchangeCalculation(1);

    dbManager.getDynamicPropertiesStore().saveCloseExchange(1);
    assertGateRejects(ContractType.ExchangeTransactionContract, 1);

    dbManager.getDynamicPropertiesStore().saveCloseExchange(2);
    assertGateRejects(ContractType.ExchangeWithdrawContract, 2);

    // Level 0 + harden 1: neither legacy predicate nor new gate applies.
    dbManager.getDynamicPropertiesStore().saveCloseExchange(0);
    assertPushPassesGate(buildTrx(ContractType.ExchangeTransactionContract));
  }

  private void assertGateRejects(ContractType type, int level) {
    ContractValidateException e = assertThrows(ContractValidateException.class,
        () -> dbManager.pushTransaction(buildTrx(type)));
    if (type == ContractType.ExchangeTransactionContract
        && !dbManager.getDynamicPropertiesStore().allowHardenExchangeCalculation()) {
      // legacy 4.8.0.1 predicate runs before the new gate and rejects with its own message
      assertEquals("ExchangeTransactionContract is rejected", e.getMessage());
    } else {
      assertEquals(type + " is rejected by exchange close level " + level, e.getMessage());
    }
  }

  private void assertPushPassesGate(TransactionCapsule trx) {
    // The gate sits before signature validation, so an unsigned transaction that passes
    // the gate reaches the signature stage and fails there - not with a gate message.
    Exception e = assertThrows(Exception.class, () -> dbManager.pushTransaction(trx));
    assertFalse("transaction must pass the close-exchange gate, but got: " + e.getMessage(),
        String.valueOf(e.getMessage()).contains("is rejected by exchange close level"));
  }

  private TransactionCapsule buildTrx(ContractType exchangeType) {
    ByteString owner = ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS_SECOND));
    switch (exchangeType) {
      case ExchangeCreateContract:
        return new TransactionCapsule(ExchangeCreateContract.newBuilder()
            .setOwnerAddress(owner)
            .setFirstTokenId(ByteString.copyFrom("_".getBytes()))
            .setFirstTokenBalance(100_000_000L + TRX_NONCE.incrementAndGet())
            .setSecondTokenId(ByteString.copyFrom("1".getBytes()))
            .setSecondTokenBalance(100_000_000L)
            .build(), ContractType.ExchangeCreateContract);
      case ExchangeInjectContract:
        return new TransactionCapsule(ExchangeInjectContract.newBuilder()
            .setOwnerAddress(owner)
            .setExchangeId(1)
            .setTokenId(ByteString.copyFrom("_".getBytes()))
            .setQuant(1)
            .build(), ContractType.ExchangeInjectContract);
      case ExchangeTransactionContract:
        return new TransactionCapsule(ExchangeTransactionContract.newBuilder()
            .setOwnerAddress(owner)
            .setExchangeId(1)
            .setTokenId(ByteString.copyFrom("_".getBytes()))
            .setQuant(1)
            .setExpected(1)
            .build(), ContractType.ExchangeTransactionContract);
      case ExchangeWithdrawContract:
        return new TransactionCapsule(ExchangeWithdrawContract.newBuilder()
            .setOwnerAddress(owner)
            .setExchangeId(1)
            .setTokenId(ByteString.copyFrom("_".getBytes()))
            .setQuant(1)
            .build(), ContractType.ExchangeWithdrawContract);
      default:
        throw new IllegalArgumentException("unsupported type " + exchangeType);
    }
  }

  private TransactionCapsule buildTransferTrx() {
    return new TransactionCapsule(TransferContract.newBuilder()
        .setAmount(10 + TRX_NONCE.incrementAndGet())
        .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS_SECOND)))
        .setToAddress(ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS_FIRST)))
        .build(), ContractType.TransferContract);
  }

  // --------------------------------------------------------------------------------------------
  // generateBlock: closed contracts are skipped, unrelated ones are packed
  // --------------------------------------------------------------------------------------------

  @Test
  public void generateBlockSkipsClosedExchangeButPacksUnrelated() throws Exception {
    prepareExchangeFixture();
    ensureHeadAdvanced();
    dbManager.getDynamicPropertiesStore().saveCloseExchange(1);

    dbManager.getPendingTransactions()
        .add(packedTrx(buildTrx(ContractType.ExchangeCreateContract)));
    dbManager.getPendingTransactions().add(packedTrx(buildTransferTrx()));

    BlockCapsule block = generateBlockWithRandomWitness();

    assertNotNull(block);
    assertEquals("closed ExchangeCreateContract must be skipped when packing",
        0, countContract(block, ContractType.ExchangeCreateContract));
    assertEquals("unrelated TransferContract must still be packed",
        1, countContract(block, ContractType.TransferContract));
  }

  @Test
  public void generateBlockPacksExchangeCreateAtLevel0() throws Exception {
    prepareExchangeFixture();
    ensureHeadAdvanced();
    dbManager.getDynamicPropertiesStore().saveCloseExchange(0);

    dbManager.getPendingTransactions()
        .add(packedTrx(buildTrx(ContractType.ExchangeCreateContract)));
    dbManager.getPendingTransactions().add(packedTrx(buildTransferTrx()));

    BlockCapsule block = generateBlockWithRandomWitness();

    assertNotNull(block);
    assertEquals("level 0 must keep the legacy behavior: ExchangeCreateContract is packable",
        1, countContract(block, ContractType.ExchangeCreateContract));
    assertEquals(1, countContract(block, ContractType.TransferContract));
  }

  /**
   * Executable transactions reference the current head via tapos, so at least one real
   * block must exist below genesis.
   */
  private void ensureHeadAdvanced() throws Exception {
    if (chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber() < 1) {
      dbManager.pushBlock(buildLocalWitnessBlock());
    }
  }

  /**
   * The packing loop runs processTransaction, which validates tapos and signatures, so a
   * transaction must be referenced to the current head, carry a valid expiration window and
   * be signed to be executable (and therefore packable).
   */
  private TransactionCapsule packedTrx(TransactionCapsule trx) {
    chainBaseManager.setBlockReference(trx);
    trx.setExpiration(chainBaseManager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp()
        + 60_000L);
    trx.sign(ByteArray.fromHexString(Args.getLocalWitnesses().getPrivateKey()));
    return trx;
  }

  /**
   * ExchangeCreateContract must be executable in the packing path (owner account exists,
   * holds enough TRX and enough of the V2 asset "1"), otherwise the block-packing
   * assertions above would be vacuous.
   */
  private void prepareExchangeFixture() {
    dbManager.getDynamicPropertiesStore().saveAllowSameTokenName(1);
    dbManager.getDynamicPropertiesStore().saveLatestExchangeNum(0);

    AssetIssueCapsule assetIssueCapsule = new AssetIssueCapsule(
        AssetIssueContract.newBuilder()
            .setName(ByteString.copyFrom("1".getBytes()))
            .setId(String.valueOf(1L))
            .build());
    dbManager.getAssetIssueV2Store().put(assetIssueCapsule.createDbV2Key(), assetIssueCapsule);

    byte[] owner = ByteArray.fromHexString(OWNER_ADDRESS_SECOND);
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(owner);
    accountCapsule.setBalance(200_000_000_000L);
    accountCapsule.addAssetV2("1".getBytes(), 200_000_000L);
    dbManager.getAccountStore().put(owner, accountCapsule);
  }

  private BlockCapsule generateBlockWithRandomWitness() {
    String key = PublicMethod.getRandomPrivateKey();
    byte[] privateKey = ByteArray.fromHexString(key);
    ECKey ecKey = ECKey.fromPrivate(privateKey);
    WitnessCapsule witnessCapsule = new WitnessCapsule(
        ByteString.copyFrom(ecKey.getAddress()));
    Param param = Param.getInstance();
    Miner miner = param.new Miner(privateKey, witnessCapsule.getAddress(),
        witnessCapsule.getAddress());
    return dbManager.generateBlock(miner, 1533529947843L, System.currentTimeMillis() + 1000);
  }

  private int countContract(BlockCapsule block, ContractType type) {
    return (int) block.getTransactions().stream()
        .filter(trx -> trx.getInstance().getRawData().getContract(0).getType() == type)
        .count();
  }

  // --------------------------------------------------------------------------------------------
  // Block validation path (processBlock -> rejectExchangeTransaction)
  // --------------------------------------------------------------------------------------------

  @Test
  public void pushBlockRejectsClosedExchangeContractInBlock() throws Exception {
    dbManager.getDynamicPropertiesStore().saveCloseExchange(1);

    // advance the chain so the packed transaction has a valid reference block
    dbManager.pushBlock(buildLocalWitnessBlock());

    TransactionCapsule trx = packedTrx(buildTrx(ContractType.ExchangeCreateContract));
    BlockCapsule block = buildLocalWitnessBlock(trx);

    ContractValidateException e = assertThrows(ContractValidateException.class,
        () -> dbManager.pushBlock(block));
    assertEquals("ExchangeCreateContract is rejected by exchange close level 1", e.getMessage());
  }

  @Test
  public void pushBlockWithoutClosedContractsStillWorksAtLevel2() throws Exception {
    dbManager.getDynamicPropertiesStore().saveCloseExchange(2);

    long headBefore = dbManager.getChainBaseManager().getDynamicPropertiesStore()
        .getLatestBlockHeaderNumber();
    dbManager.pushBlock(buildLocalWitnessBlock());

    TransactionCapsule trx = packedTrx(buildTransferTrx());
    dbManager.pushBlock(buildLocalWitnessBlock(trx));

    // both blocks must be accepted: the empty one and the one carrying the transfer
    assertEquals(headBefore + 2,
        dbManager.getChainBaseManager().getDynamicPropertiesStore()
            .getLatestBlockHeaderNumber());
  }

  /**
   * Registers the local witness as the only scheduled witness (pattern borrowed from
   * {@link ManagerForTest}) so that blocks signed by the local key pass DPoS validBlock
   * regardless of the slot, and starts the consensus service once so Manager.pushBlock
   * does not hit a null consensusInterface. The schedule is written directly (never
   * read) because at genesis the store has no active_witnesses key yet.
   */
  private void registerLocalWitnessForConsensus() {
    byte[] witnessAddressBytes = PublicMethod.getAddressByteByPrivateKey(OWNER_PRIVATE_KEY);
    ByteString witnessAddress = ByteString.copyFrom(witnessAddressBytes);
    chainBaseManager.getWitnessScheduleStore()
        .saveActiveWitnesses(Collections.singletonList(witnessAddress));
    dbManager.getWitnessStore().put(witnessAddressBytes, new WitnessCapsule(witnessAddress));
    // Only create the account if absent: tests may have funded this same address
    // (it is also OWNER_ADDRESS), and overwriting it would wipe the funding.
    if (!dbManager.getAccountStore().has(witnessAddressBytes)) {
      dbManager.getAccountStore().put(witnessAddressBytes,
          new AccountCapsule(Protocol.Account.newBuilder().setAddress(witnessAddress).build()));
    }
    if (!consensusStarted) {
      consensusService.start();
      consensusStarted = true;
    }
  }

  private BlockCapsule buildLocalWitnessBlock(TransactionCapsule... trxs) {
    registerLocalWitnessForConsensus();
    byte[] privateKey = ByteArray.fromHexString(Args.getLocalWitnesses().getPrivateKey());
    ByteString witnessAddress = ByteString.copyFrom(ECKey.fromPrivate(privateKey).getAddress());

    long number = dbManager.getChainBaseManager().getDynamicPropertiesStore()
        .getLatestBlockHeaderNumber() + 1;
    BlockCapsule blockCapsule = new BlockCapsule(
        number,
        dbManager.getChainBaseManager().getDynamicPropertiesStore()
            .getLatestBlockHeaderHash(),
        dbManager.getChainBaseManager().getDynamicPropertiesStore()
            .getLatestBlockHeaderTimestamp() + 3000L,
        witnessAddress);
    for (TransactionCapsule trx : trxs) {
      blockCapsule.addTransaction(trx);
    }
    // generatedByMyself skips preValidateTransactionSign, so the gate under test is the
    // first per-transaction check in the block validation path.
    blockCapsule.generatedByMyself = true;
    blockCapsule.setMerkleRoot();
    blockCapsule.sign(privateKey);
    return blockCapsule;
  }
}
