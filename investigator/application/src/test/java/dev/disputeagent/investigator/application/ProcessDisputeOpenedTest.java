package dev.disputeagent.investigator.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.application.approval.RequestApproval;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.investigation.InvestigateDispute;
import dev.disputeagent.investigator.application.port.events.OutgoingEvent;
import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.application.support.Fakes;
import dev.disputeagent.investigator.application.support.Fakes.FakeJson;
import dev.disputeagent.investigator.application.support.Fakes.FakeMcp;
import dev.disputeagent.investigator.application.support.Fakes.RecordingAudit;
import dev.disputeagent.investigator.application.support.Fakes.ScriptedLlm;
import dev.disputeagent.investigator.application.support.Fakes.TickingClock;
import dev.disputeagent.investigator.application.support.MemoryCases;
import dev.disputeagent.investigator.application.triage.TriageDispute;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import dev.disputeagent.investigator.domain.investigation.LoopBudget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The case and its events are saved together; nothing is published on the consumer's path (ADR 0008). */
class ProcessDisputeOpenedTest {

    static final DisputeOpened DISPUTE = new DisputeOpened(new CaseId("DSP-000007"), "CH-000042", "CRD-000042",
            "TXN-000100", DisputeCategory.UNAUTHORISED_TRANSACTION, Instant.parse("2026-10-30T10:00:00Z"),
            Money.eur(1_000), "I never made this payment.", List.of());

    static final Decision APPROVE = new Decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, Confidence.HIGH,
            "Card not present, no 3-D Secure, fraud flag.", List.of("TXN-000100", "AUTH-000100"),
            List.of("unauthorised-card-not-present"),
            List.of(new ProposedAction.IssueProvisionalCredit(Money.eur(1_000))));

    static final dev.disputeagent.investigator.application.replay.ReplayPolicy POLICY =
            new dev.disputeagent.investigator.application.replay.ReplayPolicy(5, Duration.ofSeconds(30),
                    Duration.ofMinutes(10), Duration.ofMinutes(15));

    final ScriptedLlm llm = new ScriptedLlm();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final MemoryCases store = new MemoryCases();
    final RecordingAudit audit = new RecordingAudit();
    final TickingClock clock = new TickingClock(Instant.parse("2026-11-20T09:00:00Z"));

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    ProcessDisputeOpened process() {
        return process(POLICY);
    }

    ProcessDisputeOpened process(dev.disputeagent.investigator.application.replay.ReplayPolicy policy) {
        var mcp = new FakeMcp()
                .tool(McpServer.LEDGER, "get_transaction", a -> new McpToolResult(
                        "{\"transaction_id\":\"TXN-000100\",\"card_id\":\"CRD-000042\",\"auth_id\":\"AUTH-000100\","
                                + "\"posted_on\":\"2026-10-01\",\"amount_minor\":1000}", false))
                .tool(McpServer.LEDGER, "search_transactions", a -> new McpToolResult(
                        "{\"items\":[{\"transaction_id\":\"TXN-000100\",\"auth_id\":\"AUTH-000100\"}]}", false))
                .tool(McpServer.LEDGER, "get_authorisations", a -> new McpToolResult(
                        "{\"items\":[{\"auth_id\":\"AUTH-000100\",\"fraud_flag\":true}]}", false))
                .tool(McpServer.POLICY, "search_policy", a -> new McpToolResult(
                        "{\"results\":[{\"section_id\":\"unauthorised-card-not-present\"}]}", false));
        var settings = Fakes.settings(new LoopBudget(8, 20, 200_000, Duration.ofMinutes(5)));
        var json = new FakeJson().decision("decision-approve", APPROVE);
        var handle = new HandleDisputeOpened(new TriageDispute(llm, json, audit, clock, settings),
                new InvestigateDispute(llm, mcp, json, audit, clock, executor, settings, Fakes.SKILLS), audit, clock);
        return new ProcessDisputeOpened(handle, new RequestApproval(audit, clock), store, policy, clock);
    }

    void decides() {
        llm.then(StopReason.REFUSAL) // triage falls back to the reported category
                .then(StopReason.TOOL_USE, new ContentBlock.ToolUse("t1", "search_policy", "{\"query\":\"cnp\"}"))
                .then(StopReason.END_TURN, new ContentBlock.Text("decision-approve"));
    }

    @Test
    void aDecidedCaseIsSavedWaitingForApprovalWithBothEvents() {
        decides();

        var result = process().process(DISPUTE);

        assertThat(result.outcome().state()).as("the investigation's outcome").isEqualTo(CaseState.DECIDED);
        assertThat(store.stateOf(DISPUTE.disputeId())).isEqualTo(CaseState.AWAITING_APPROVAL);
        assertThat(store.outbox).hasSize(2);
        assertThat(store.outbox.get(0)).isEqualTo(new OutgoingEvent.Decided(result.outcome()));
        assertThat(store.outbox.get(1)).isInstanceOfSatisfying(OutgoingEvent.ApprovalRequested.class, e -> {
            assertThat(e.disputeId()).isEqualTo(DISPUTE.disputeId());
            assertThat(e.request().action()).isEqualTo(APPROVE.proposedActions().getFirst());
        });
    }

    @Test
    void aRedeliveredDisputeIsNeitherInvestigatedNorEmittedAgain() {
        decides();
        var process = process();
        var first = process.process(DISPUTE);

        var second = process.process(DISPUTE);

        assertThat(second.redelivery()).isTrue();
        assertThat(second.outcome()).isEqualTo(first.outcome());
        assertThat(store.outbox).as("the outbox already holds the events").hasSize(2);
        assertThat(llm.requests).hasSize(3); // no new model call
    }

    @Test
    void aParkedCaseIsSavedWithItsEventAndEmitsNothing() {
        llm.then(StopReason.REFUSAL) // triage falls back
                .thenThrow(new dev.disputeagent.investigator.application.port.llm.LlmCallException(
                        dev.disputeagent.investigator.application.port.llm.LlmCallException.Kind.OVERLOADED,
                        "529", null));

        var result = process().process(DISPUTE);

        assertThat(result.state()).isEqualTo(CaseState.AWAITING_RETRY);
        assertThat(result.outcome()).as("no outcome yet").isNull();
        assertThat(store.stateOf(DISPUTE.disputeId())).isEqualTo(CaseState.AWAITING_RETRY);
        assertThat(store.parkedEvents).containsEntry(DISPUTE.disputeId(), DISPUTE);
        assertThat(store.outbox).as("nothing to publish until it is decided").isEmpty();

        var redelivered = process().process(DISPUTE);
        assertThat(redelivered.redelivery()).isTrue();
        assertThat(redelivered.state()).isEqualTo(CaseState.AWAITING_RETRY);
    }

    // Replay of parked cases (ADR 0008) -------------------------------------------------------------------------

    void overloaded() {
        llm.then(StopReason.REFUSAL)
                .thenThrow(new dev.disputeagent.investigator.application.port.llm.LlmCallException(
                        dev.disputeagent.investigator.application.port.llm.LlmCallException.Kind.OVERLOADED,
                        "529", null));
    }

    dev.disputeagent.investigator.application.replay.SweepParkedCases sweep(
            dev.disputeagent.investigator.application.replay.ReplayPolicy policy, boolean modelAvailable) {
        return new dev.disputeagent.investigator.application.replay.SweepParkedCases(store, policy, audit, clock,
                () -> modelAvailable);
    }

    List<OutgoingEvent.Replay> replaysSent() {
        return store.outbox.stream().filter(OutgoingEvent.Replay.class::isInstance)
                .map(OutgoingEvent.Replay.class::cast).toList();
    }

    @Test
    void aParkedCaseIsReplayedWhenDueOnceAndDecidedByTheReplay() {
        overloaded();
        var process = process();
        process.process(DISPUTE);
        var sweep = sweep(POLICY, true);

        assertThat(sweep.sweep(10).replayed()).as("not due yet").isZero();
        clock.advance(Duration.ofSeconds(31));
        assertThat(sweep.sweep(10).replayed()).isEqualTo(1);
        assertThat(replaysSent()).singleElement().satisfies(r -> assertThat(r.event()).isEqualTo(DISPUTE));
        assertThat(sweep.sweep(10).replayed()).as("held while the replay runs").isZero();

        decides();
        var replayed = process.processReplay(DISPUTE);

        assertThat(replayed.state()).isEqualTo(CaseState.AWAITING_APPROVAL);
        assertThat(replayed.outcome().state()).isEqualTo(CaseState.DECIDED);
        var stored = store.find(DISPUTE.disputeId()).orElseThrow().disputeCase();
        assertThat(stored.replays()).isEqualTo(1);
        assertThat(stored.history()).extracting(dev.disputeagent.investigator.domain.investigation.DisputeCase.StateChange::to)
                .containsSubsequence(CaseState.AWAITING_RETRY, CaseState.RECEIVED, CaseState.DECIDED,
                        CaseState.AWAITING_APPROVAL);
        assertThat(store.outbox).filteredOn(e -> !(e instanceof OutgoingEvent.Replay))
                .hasSize(2).first().isInstanceOf(OutgoingEvent.Decided.class);
        assertThat(audit.of(dev.disputeagent.investigator.application.port.audit.AuditEvent.CaseReplayed.class))
                .singleElement().satisfies(r -> assertThat(r.replay()).isEqualTo(1));

        var duplicate = process.processReplay(DISPUTE);
        assertThat(duplicate.redelivery()).as("the case is no longer parked").isTrue();
        assertThat(store.outbox).filteredOn(e -> !(e instanceof OutgoingEvent.Replay)).hasSize(2);
    }

    @Test
    void aReplayThatParksAgainWaitsTwiceAsLong() {
        overloaded();
        var process = process();
        process.process(DISPUTE);
        clock.advance(Duration.ofSeconds(31));
        sweep(POLICY, true).sweep(10);

        overloaded();
        var again = process.processReplay(DISPUTE);

        assertThat(again.state()).isEqualTo(CaseState.AWAITING_RETRY);
        assertThat(store.find(DISPUTE.disputeId()).orElseThrow().disputeCase().replays()).isEqualTo(1);
        var wait = Duration.between(clock.instant(), store.nextAttempts.get(DISPUTE.disputeId()));
        assertThat(wait).isBetween(Duration.ofSeconds(50), Duration.ofSeconds(60));
    }

    @Test
    void afterTheLastReplayTheSweepEscalatesTheCase() {
        var twice = new dev.disputeagent.investigator.application.replay.ReplayPolicy(2, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        var process = process(twice);
        overloaded();
        process.process(DISPUTE);
        for (int i = 0; i < 2; i++) {
            clock.advance(Duration.ofSeconds(5));
            assertThat(sweep(twice, true).sweep(10).replayed()).isEqualTo(1);
            overloaded();
            process.processReplay(DISPUTE);
        }
        clock.advance(Duration.ofSeconds(5));

        var swept = sweep(twice, true).sweep(10);

        assertThat(swept.escalated()).isEqualTo(1);
        var c = store.find(DISPUTE.disputeId()).orElseThrow();
        assertThat(c.disputeCase().state()).isEqualTo(CaseState.ESCALATED);
        assertThat(c.disputeCase().escalationReason()).contains(EscalationReason.DEPENDENCY_UNAVAILABLE);
        assertThat(store.outbox).filteredOn(OutgoingEvent.Decided.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((OutgoingEvent.Decided) e).outcome().state())
                        .isEqualTo(CaseState.ESCALATED));
    }

    @Test
    void whileTheModelIsUnavailableTheSweepHoldsCasesBack() {
        overloaded();
        process().process(DISPUTE);
        clock.advance(Duration.ofSeconds(31));

        var swept = sweep(POLICY, false).sweep(10);

        assertThat(swept.heldBack()).isEqualTo(1);
        assertThat(replaysSent()).isEmpty();
    }

    @Test
    void aReplayForACaseNeverParkedIsIgnored() {
        var result = process().processReplay(DISPUTE);

        assertThat(result.redelivery()).isTrue();
        assertThat(result.state()).isNull();
        assertThat(llm.requests).isEmpty();
    }

    @Test
    void anEscalatedCaseIsSavedWithItsOutcomeAndNoRequest() {
        llm.then(StopReason.REFUSAL).then(StopReason.REFUSAL); // triage falls back, the investigation escalates

        var result = process().process(DISPUTE);

        assertThat(result.redelivery()).isFalse();
        assertThat(result.outcome().state()).isEqualTo(CaseState.ESCALATED);
        assertThat(result.outcome().escalationReason()).isEqualTo(EscalationReason.MODEL_REFUSED);
        assertThat(store.stateOf(DISPUTE.disputeId())).isEqualTo(CaseState.ESCALATED);
        assertThat(store.outbox).containsExactly(new OutgoingEvent.Decided(result.outcome()));
    }
}
