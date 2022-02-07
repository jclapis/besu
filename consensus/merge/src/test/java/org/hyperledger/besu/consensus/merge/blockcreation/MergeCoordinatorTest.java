/*
 * Copyright Hyperledger Besu Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.consensus.merge.blockcreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider.createInMemoryBlockchain;
import static org.hyperledger.besu.ethereum.core.InMemoryKeyValueStorageProvider.createInMemoryWorldStateArchive;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.config.experimental.MergeOptions;
import org.hyperledger.besu.consensus.merge.MergeContext;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.GenesisState;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.MiningParameters;
import org.hyperledger.besu.ethereum.eth.sync.backwardsync.BackwardsSyncContext;
import org.hyperledger.besu.ethereum.eth.transactions.sorter.AbstractPendingTransactionsSorter;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.feemarket.BaseFeeMarket;
import org.hyperledger.besu.ethereum.mainnet.feemarket.LondonFeeMarket;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;

import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class MergeCoordinatorTest implements MergeGenesisConfigHelper {

  @Mock AbstractPendingTransactionsSorter mockSorter;
  @Mock MergeContext mergeContext;

  private MergeCoordinator coordinator;
  private ProtocolContext protocolContext;

  private final ProtocolSchedule mockProtocolSchedule = getMergeProtocolSchedule();
  private final GenesisState genesisState =
      GenesisState.fromConfig(getPosGenesisConfigFile(), mockProtocolSchedule);

  private final WorldStateArchive worldStateArchive = createInMemoryWorldStateArchive();

  private final MutableBlockchain blockchain =
      spy(createInMemoryBlockchain(genesisState.getBlock()));

  private final Address suggestedFeeRecipient = Address.ZERO;
  private final Address coinbase = genesisAllocations(getPosGenesisConfigFile()).findFirst().get();
  private final BlockHeaderTestFixture headerGenerator = new BlockHeaderTestFixture();
  private final BaseFeeMarket feeMarket =
      new LondonFeeMarket(0, genesisState.getBlock().getHeader().getBaseFee());

  @Before
  public void setUp() {
    when(mergeContext.as(MergeContext.class)).thenReturn(mergeContext);
    when(mergeContext.getTerminalTotalDifficulty())
        .thenReturn(genesisState.getBlock().getHeader().getDifficulty().plus(1L));
    when(mergeContext.getTerminalPoWBlock()).thenReturn(Optional.of(terminalPowBlock()));

    protocolContext = new ProtocolContext(blockchain, worldStateArchive, mergeContext);
    var mutable = worldStateArchive.getMutable();
    genesisState.writeStateTo(mutable);
    mutable.persist(null);

    MergeOptions.setMergeEnabled(true);
    this.coordinator =
        new MergeCoordinator(
            protocolContext,
            mockProtocolSchedule,
            mockSorter,
            new MiningParameters.Builder().coinbase(coinbase).build(),
            mock(BackwardsSyncContext.class));
  }

  @Test
  public void coinbaseShouldMatchSuggestedFeeRecipient() {
    when(mergeContext.getFinalized()).thenReturn(Optional.empty());

    var payloadId =
        coordinator.preparePayload(
            genesisState.getBlock().getHeader(),
            System.currentTimeMillis() / 1000,
            Bytes32.ZERO,
            suggestedFeeRecipient);

    ArgumentCaptor<Block> block = ArgumentCaptor.forClass(Block.class);

    verify(mergeContext, atLeastOnce()).putPayloadById(eq(payloadId), block.capture());

    assertThat(block.getValue().getHeader().getCoinbase()).isEqualTo(suggestedFeeRecipient);
  }

  @Test
  public void latestValidAncestorDescendsFromTerminal() {

    BlockHeader terminalHeader = terminalPowBlock();
    coordinator.executeBlock(new Block(terminalHeader, BlockBody.empty()));

    BlockHeader parentHeader = nextBlockHeader(terminalHeader);

    Block parent = new Block(parentHeader, BlockBody.empty());
    coordinator.executeBlock(parent);

    BlockHeader childHeader = nextBlockHeader(parentHeader);
    Block child = new Block(childHeader, BlockBody.empty());
    coordinator.executeBlock(child);
    assertThat(this.coordinator.latestValidAncestorDescendsFromTerminal(child.getHeader()))
        .isTrue();
  }

  @Test
  public void latestValidAncestorDescendsFromFinalizedBlock() {

    BlockHeader terminalHeader = terminalPowBlock();
    coordinator.executeBlock(new Block(terminalHeader, BlockBody.empty()));

    BlockHeader grandParentHeader = nextBlockHeader(terminalHeader);

    Block grandParent = new Block(grandParentHeader, BlockBody.empty());
    coordinator.executeBlock(grandParent);
    when(mergeContext.getFinalized()).thenReturn(Optional.of(grandParentHeader));

    BlockHeader parentHeader = nextBlockHeader(grandParentHeader);

    Block parent = new Block(parentHeader, BlockBody.empty());
    coordinator.executeBlock(parent);

    BlockHeader childHeader = nextBlockHeader(parentHeader);
    Block child = new Block(childHeader, BlockBody.empty());
    coordinator.executeBlock(child);

    assertThat(this.coordinator.latestValidAncestorDescendsFromTerminal(child.getHeader()))
        .isTrue();
    verify(mergeContext, never()).getTerminalPoWBlock();
  }

  @Test
  public void updateForkChoiceShouldPersistFirstFinalizedBlockHash() {

    when(mergeContext.getFinalized()).thenReturn(Optional.empty());

    BlockHeader terminalHeader = terminalPowBlock();
    coordinator.executeBlock(new Block(terminalHeader, BlockBody.empty()));

    BlockHeader firstFinalizedHeader = nextBlockHeader(terminalHeader);
    Block firstFinalizedBlock = new Block(firstFinalizedHeader, BlockBody.empty());
    coordinator.executeBlock(firstFinalizedBlock);

    BlockHeader headBlockHeader = nextBlockHeader(firstFinalizedHeader);
    Block headBlock = new Block(headBlockHeader, BlockBody.empty());
    coordinator.executeBlock(headBlock);

    coordinator.updateForkChoice(headBlock.getHash(), firstFinalizedBlock.getHash());

    verify(blockchain).setFinalized(firstFinalizedBlock.getHash());
    verify(mergeContext).setFinalized(firstFinalizedHeader);
  }

  @Test
  public void updateForkChoiceShouldPersistLastFinalizedBlockHash() {
    BlockHeader terminalHeader = terminalPowBlock();
    coordinator.executeBlock(new Block(terminalHeader, BlockBody.empty()));

    BlockHeader prevFinalizedHeader = nextBlockHeader(terminalHeader);
    Block prevFinalizedBlock = new Block(prevFinalizedHeader, BlockBody.empty());
    coordinator.executeBlock(prevFinalizedBlock);

    when(mergeContext.getFinalized()).thenReturn(Optional.of(prevFinalizedHeader));

    BlockHeader lastFinalizedHeader = nextBlockHeader(prevFinalizedHeader);
    Block lastFinalizedBlock = new Block(lastFinalizedHeader, BlockBody.empty());
    coordinator.executeBlock(lastFinalizedBlock);

    BlockHeader headBlockHeader = nextBlockHeader(lastFinalizedHeader);
    Block headBlock = new Block(headBlockHeader, BlockBody.empty());
    coordinator.executeBlock(headBlock);

    coordinator.updateForkChoice(headBlock.getHash(), lastFinalizedBlock.getHash());

    verify(blockchain).setFinalized(lastFinalizedBlock.getHash());
    verify(mergeContext).setFinalized(lastFinalizedHeader);
  }

  @Test(expected = IllegalStateException.class)
  public void updateForkChoiceShouldFailIfLastFinalizedNotDescendantOfPreviousFinalized() {
    BlockHeader terminalHeader = terminalPowBlock();
    coordinator.executeBlock(new Block(terminalHeader, BlockBody.empty()));

    BlockHeader prevFinalizedHeader = nextBlockHeader(terminalHeader);
    Block prevFinalizedBlock = new Block(prevFinalizedHeader, BlockBody.empty());
    coordinator.executeBlock(prevFinalizedBlock);

    when(mergeContext.getFinalized()).thenReturn(Optional.of(prevFinalizedHeader));

    // not descendant of previous finalized block
    BlockHeader lastFinalizedHeader = disjointBlockHeader(prevFinalizedHeader);
    Block lastFinalizedBlock = new Block(lastFinalizedHeader, BlockBody.empty());
    coordinator.executeBlock(lastFinalizedBlock);

    BlockHeader headBlockHeader = nextBlockHeader(lastFinalizedHeader);
    Block headBlock = new Block(headBlockHeader, BlockBody.empty());
    coordinator.executeBlock(headBlock);

    coordinator.updateForkChoice(headBlock.getHash(), lastFinalizedBlock.getHash());

    verify(blockchain, never()).setFinalized(lastFinalizedBlock.getHash());
    verify(mergeContext, never()).setFinalized(lastFinalizedHeader);
  }

  @Test(expected = IllegalStateException.class)
  public void updateForkChoiceShouldFailIfHeadNotDescendantOfLastFinalized() {
    BlockHeader terminalHeader = terminalPowBlock();
    coordinator.executeBlock(new Block(terminalHeader, BlockBody.empty()));

    BlockHeader prevFinalizedHeader = nextBlockHeader(terminalHeader);
    Block prevFinalizedBlock = new Block(prevFinalizedHeader, BlockBody.empty());
    coordinator.executeBlock(prevFinalizedBlock);

    when(mergeContext.getFinalized()).thenReturn(Optional.of(prevFinalizedHeader));

    BlockHeader lastFinalizedHeader = nextBlockHeader(prevFinalizedHeader);
    Block lastFinalizedBlock = new Block(lastFinalizedHeader, BlockBody.empty());
    coordinator.executeBlock(lastFinalizedBlock);

    // not descendant of last finalized block
    BlockHeader headBlockHeader = disjointBlockHeader(lastFinalizedHeader);
    Block headBlock = new Block(headBlockHeader, BlockBody.empty());
    coordinator.executeBlock(headBlock);

    coordinator.updateForkChoice(headBlock.getHash(), lastFinalizedBlock.getHash());

    verify(blockchain, never()).setFinalized(lastFinalizedBlock.getHash());
    verify(mergeContext, never()).setFinalized(lastFinalizedHeader);
  }

  @Test(expected = IllegalStateException.class)
  public void updateForkChoiceShouldFailIfHeadBlockNotFound() {
    BlockHeader terminalHeader = terminalPowBlock();
    coordinator.executeBlock(new Block(terminalHeader, BlockBody.empty()));

    BlockHeader prevFinalizedHeader = nextBlockHeader(terminalHeader);
    Block prevFinalizedBlock = new Block(prevFinalizedHeader, BlockBody.empty());
    coordinator.executeBlock(prevFinalizedBlock);

    when(mergeContext.getFinalized()).thenReturn(Optional.of(prevFinalizedHeader));

    BlockHeader lastFinalizedHeader = nextBlockHeader(prevFinalizedHeader);
    Block lastFinalizedBlock = new Block(lastFinalizedHeader, BlockBody.empty());
    coordinator.executeBlock(lastFinalizedBlock);

    BlockHeader headBlockHeader = nextBlockHeader(lastFinalizedHeader);
    Block headBlock = new Block(headBlockHeader, BlockBody.empty());
    // note this block is not executed, so not known by us

    coordinator.updateForkChoice(headBlock.getHash(), lastFinalizedBlock.getHash());

    verify(blockchain, never()).setFinalized(lastFinalizedBlock.getHash());
    verify(mergeContext, never()).setFinalized(lastFinalizedHeader);
  }

  @Test(expected = IllegalStateException.class)
  public void updateForkChoiceShouldFailIfFinalizedBlockNotFound() {
    BlockHeader terminalHeader = terminalPowBlock();
    coordinator.executeBlock(new Block(terminalHeader, BlockBody.empty()));

    BlockHeader prevFinalizedHeader = nextBlockHeader(terminalHeader);
    Block prevFinalizedBlock = new Block(prevFinalizedHeader, BlockBody.empty());
    coordinator.executeBlock(prevFinalizedBlock);

    when(mergeContext.getFinalized()).thenReturn(Optional.of(prevFinalizedHeader));

    BlockHeader lastFinalizedHeader = nextBlockHeader(prevFinalizedHeader);
    Block lastFinalizedBlock = new Block(lastFinalizedHeader, BlockBody.empty());
    // note this block is not executed, so not known by us

    BlockHeader headBlockHeader = nextBlockHeader(lastFinalizedHeader);
    Block headBlock = new Block(headBlockHeader, BlockBody.empty());
    coordinator.executeBlock(headBlock);

    coordinator.updateForkChoice(headBlock.getHash(), lastFinalizedBlock.getHash());

    verify(blockchain, never()).setFinalized(lastFinalizedBlock.getHash());
    verify(mergeContext, never()).setFinalized(lastFinalizedHeader);
  }

  private BlockHeader terminalPowBlock() {
    return headerGenerator
        .difficulty(Difficulty.MAX_VALUE)
        .parentHash(genesisState.getBlock().getHash())
        .number(genesisState.getBlock().getHeader().getNumber() + 1)
        .baseFeePerGas(
            feeMarket.computeBaseFee(
                genesisState.getBlock().getHeader().getNumber() + 1,
                genesisState.getBlock().getHeader().getBaseFee().orElse(Wei.of(0x3b9aca00)),
                0,
                15000000l))
        .gasLimit(genesisState.getBlock().getHeader().getGasLimit())
        .stateRoot(genesisState.getBlock().getHeader().getStateRoot())
        .buildHeader();
  }

  private BlockHeader nextBlockHeader(final BlockHeader parentHeader) {
    return headerGenerator
        .difficulty(Difficulty.ZERO)
        .parentHash(parentHeader.getHash())
        .gasLimit(genesisState.getBlock().getHeader().getGasLimit())
        .number(parentHeader.getNumber() + 1)
        .stateRoot(genesisState.getBlock().getHeader().getStateRoot())
        .baseFeePerGas(
            feeMarket.computeBaseFee(
                genesisState.getBlock().getHeader().getNumber() + 1,
                parentHeader.getBaseFee().orElse(Wei.of(0x3b9aca00)),
                0,
                15000000l))
        .buildHeader();
  }

  private BlockHeader disjointBlockHeader(final BlockHeader disjointFromHeader) {
    Hash disjointParentHash = Hash.wrap(disjointFromHeader.getParentHash().shiftRight(1));

    return headerGenerator
        .difficulty(Difficulty.ZERO)
        .parentHash(disjointParentHash)
        .gasLimit(genesisState.getBlock().getHeader().getGasLimit())
        .number(disjointFromHeader.getNumber() + 1)
        .stateRoot(genesisState.getBlock().getHeader().getStateRoot())
        .baseFeePerGas(
            feeMarket.computeBaseFee(
                genesisState.getBlock().getHeader().getNumber() + 1,
                disjointFromHeader.getBaseFee().orElse(Wei.of(0x3b9aca00)),
                0,
                15000000l))
        .buildHeader();
  }
}
