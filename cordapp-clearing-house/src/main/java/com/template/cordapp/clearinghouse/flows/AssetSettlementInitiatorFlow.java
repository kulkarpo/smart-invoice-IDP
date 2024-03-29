package com.template.cordapp.clearinghouse.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableSet;
import com.template.cordapp.common.flows.IdentitySyncFlow;
import com.template.cordapp.common.flows.ReceiveTransactionUnVerifiedFlow;
import com.template.cordapp.contract.AssetTransferContract;
import com.template.cordapp.flows.AbstractAssetSettlementFlow;

import java.security.PublicKey;
import java.security.SignatureException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import com.template.cordapp.state.Asset;

import com.template.cordapp.state.AssetTransfer;
import kotlin.collections.CollectionsKt;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.CommandWithParties;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.TransactionState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.*;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;
import net.corda.core.transactions.LedgerTransaction;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import org.jetbrains.annotations.NotNull;

import static com.template.cordapp.state.RequestStatus.TRANSFERRED;

/**
 * Create new transaction to process the received transaction to settle [AssetTransfer] request.
 * It accepts the [AssetTransfer] state's [linearId] as input to start this flow and collects the [Cash] and [Asset] input and output states from counter-party.
 * For demo:
 * 1. Clearing House set requestStatus to [RequestStatus.TRANSFERRED] if everything is okay
 * (i.e. by offline verifying the data of [AssetTransfer.asset] is valid).
 * <p>
 * On successful completion of a flow, [Asset] state ownership is transferred to `Buyer` party
 * and [Cash] tokens equals to [Asset.purchaseCost] is transferred to `Seller` party.
 */

@StartableByRPC
public final class AssetSettlementInitiatorFlow extends AbstractAssetSettlementFlow {

    private final UniqueIdentifier linearId;

    private final ProgressTracker.Step INITIALISING = new ProgressTracker.Step("Performing initial steps");
    private final ProgressTracker.Step BUILDING = new ProgressTracker.Step("Building and verifying transaction");
    private final ProgressTracker.Step SIGNING = new ProgressTracker.Step("Signing transaction");
    private final ProgressTracker.Step COLLECT_STATES = new ProgressTracker.Step("Collect Asset and Cash states from counterparty.");
    private final ProgressTracker.Step FINALISING = new ProgressTracker.Step("Finalising transaction") {
        @Override
        public ProgressTracker childProgressTracker() {
            return FinalityFlow.Companion.tracker();
        }
    };

    private static final ProgressTracker.Step IDENTITY_SYNC = new ProgressTracker.Step("Sync identities with counter parties.") {
        @Override
        public ProgressTracker childProgressTracker() {
            return IdentitySyncFlow.Send.Companion.tracker();
        }

    };

    private final ProgressTracker.Step COLLECTING = new ProgressTracker.Step("Collecting counter-party signature.") {
        @Override
        public ProgressTracker childProgressTracker() {
            return CollectSignaturesFlow.Companion.tracker();
        }
    };

    final ProgressTracker progressTracker = new ProgressTracker(
            INITIALISING,
            BUILDING,
            COLLECT_STATES,
            IDENTITY_SYNC,
            SIGNING,
            COLLECTING,
            FINALISING
    );

    @Override
    public ProgressTracker getProgressTracker() {
        return this.progressTracker;
    }


    public AssetSettlementInitiatorFlow(UniqueIdentifier linearId) {
        this.linearId = linearId;
    }

    @Suspendable
    @NotNull
    public SignedTransaction call() throws FlowException {

        Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

        progressTracker.setCurrentStep(INITIALISING);

        StateAndRef<AssetTransfer> input = this.loadState(this.getServiceHub(), this.linearId, AssetTransfer.class);

        List participants = input.getState().getData().getParticipants();

        Asset asset = input.getState().getData().getAsset();

        AbstractParty securitySeller = input.getState().getData().getSecuritySeller();
        AbstractParty securityBuyer = input.getState().getData().getSecurityBuyer();

        AssetTransfer assetTransfer = new AssetTransfer(asset,
                securitySeller,
                securityBuyer,
                this.getOurIdentity(),
                TRANSFERRED,
                participants,
                linearId);


        List<PublicKey> requiredSigners = Arrays.asList(
                assetTransfer.getSecurityBuyer().getOwningKey(),
                assetTransfer.getSecuritySeller().getOwningKey(),
                getOurIdentity().getOwningKey());

        final Command<AssetTransferContract.Commands.SettleRequest> command = new Command(
                new AssetTransferContract.Commands.SettleRequest(), requiredSigners);


        progressTracker.setCurrentStep(BUILDING);
        TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addInputState(input)
                .addOutputState(assetTransfer, AssetTransferContract.ASSET_TRANSFER_CONTRACT_ID)
                .addCommand(command)
                .setTimeWindow(getServiceHub().getClock().instant(), Duration.ofSeconds(60));

        progressTracker.setCurrentStep(COLLECT_STATES);

        SignedTransaction tempPtx = getServiceHub().signInitialTransaction(txBuilder);

        FlowSession securitySellerSession = this.initiateFlow(this.resolveIdentity(this.getServiceHub(), assetTransfer.getSecuritySeller()));
        subFlow(new SendTransactionFlow(securitySellerSession, tempPtx));

        SignedTransaction assetPtx = subFlow((new ReceiveTransactionUnVerifiedFlow(securitySellerSession)));

        FlowSession securityBuyerSession = this.initiateFlow(this.resolveIdentity(this.getServiceHub(), assetTransfer.getSecurityBuyer()));
        subFlow((new SendTransactionFlow(securityBuyerSession, tempPtx)));

        securityBuyerSession.send(txBuilder.getLockId());

        this.subFlow(((new net.corda.confidential.IdentitySyncFlow.Receive(securityBuyerSession))));

        SignedTransaction cashPtx = this.subFlow((new ReceiveTransactionUnVerifiedFlow(securityBuyerSession)));

        LedgerTransaction assetLtx = null;
        try {
            assetLtx = assetPtx.toLedgerTransaction(this.getServiceHub(), false);
        } catch (SignatureException e) {
            e.printStackTrace();
        }

        Iterable assetInputs = assetLtx.getInputs();
        Iterable assetOutputs = assetLtx.getOutputs();
        Iterable assetCommands = assetLtx.getCommands();
        Iterator inputsIterator = assetInputs.iterator();
        Iterator outputsIterator = assetOutputs.iterator();
        Iterator commandsIterator = assetCommands.iterator();

        Object element;
        while (inputsIterator.hasNext()) {
            element = inputsIterator.next();
            StateAndRef it = (StateAndRef) element;
            txBuilder.addInputState(it);
        }

        while (outputsIterator.hasNext()) {
            element = outputsIterator.next();
            TransactionState it = (TransactionState) element;
            txBuilder.addOutputState(it);
        }

        while (commandsIterator.hasNext()) {
            element = commandsIterator.next();
            CommandWithParties it = (CommandWithParties) element;
            txBuilder.addCommand(new Command(it.getValue(), it.getSigners()));
        }

        LedgerTransaction cashLtx = null;

        try {
            cashLtx = cashPtx.toLedgerTransaction(this.getServiceHub(), false);
        } catch (SignatureException e) {
            e.printStackTrace();
        }


        Iterable cashInputs = cashLtx.getInputs();
        Iterable cashOutputs = cashLtx.getOutputs();
        Iterable cashCommands = cashLtx.getCommands();
        Iterator inputsIteratorc = cashInputs.iterator();
        Iterator outputsIteratorc = cashOutputs.iterator();
        Iterator commandsIteratorc = cashCommands.iterator();

        while (inputsIteratorc.hasNext()) {
            element = inputsIteratorc.next();
            StateAndRef it = (StateAndRef) element;
            txBuilder.addInputState(it);
        }

        while (outputsIteratorc.hasNext()) {
            element = outputsIteratorc.next();
            TransactionState it = (TransactionState) element;
            txBuilder.addOutputState(it);
        }

        while (commandsIteratorc.hasNext()) {
            element = commandsIteratorc.next();
            CommandWithParties it = (CommandWithParties) element;
            txBuilder.addCommand(new Command(it.getValue(), it.getSigners()));
        }

        ImmutableSet<FlowSession> otherPartySession = ImmutableSet.of(securityBuyerSession, securitySellerSession);

        progressTracker.setCurrentStep(IDENTITY_SYNC);
        this.subFlow(new IdentitySyncFlow.Send(otherPartySession,
                txBuilder.toWireTransaction(getServiceHub()),
                IDENTITY_SYNC.childProgressTracker()));

        progressTracker.setCurrentStep(SIGNING);
        SignedTransaction signedTx = getServiceHub().signInitialTransaction(txBuilder);

        progressTracker.setCurrentStep(COLLECTING);
        final SignedTransaction fullySignedTx = subFlow(
                new CollectSignaturesFlow(signedTx, otherPartySession, CollectionsKt.listOf(assetTransfer.getClearingHouse().getOwningKey()), CollectSignaturesFlow.tracker()));

        progressTracker.setCurrentStep(FINALISING);
        return subFlow(new FinalityFlow(fullySignedTx, FINALISING.childProgressTracker()));

    }
}




