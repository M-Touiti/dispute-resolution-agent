package dev.disputeagent.investigator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.investigation.InvestigateDispute;
import dev.disputeagent.investigator.application.port.events.DecisionPublisher;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.application.support.Fakes;
import dev.disputeagent.investigator.application.support.Fakes.FakeJson;
import dev.disputeagent.investigator.application.support.Fakes.FakeMcp;
import dev.disputeagent.investigator.application.support.Fakes.RecordingAudit;
import dev.disputeagent.investigator.application.support.Fakes.ScriptedLlm;
import dev.disputeagent.investigator.application.support.Fakes.TickingClock;
import dev.disputeagent.investigator.application.triage.TriageDispute;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import dev.disputeagent.investigator.domain.investigation.LoopBudget;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProcessDisputeOpenedTest {

    static final DisputeOpened DISPUTE = new DisputeOpened(new CaseId("DSP-000007"), "CH-000042", "CRD-000042",
            "TXN-000100", DisputeCategory.UNAUTHORISED_TRANSACTION, Instant.parse("2026-10-30T10:00:00Z"),
            Money.eur(1_000), "I never made this payment.", List.of());

    final ScriptedLlm llm = new ScriptedLlm();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final Map<CaseId, CaseOutcome> stored = new HashMap<>();
    final List<CaseOutcome> published = new ArrayList<>();
    final CaseStore store = new CaseStore() {
        @Override
        public Optional<CaseOutcome> findOutcome(CaseId id) {
            return Optional.ofNullable(stored.get(id));
        }

        @Override
        public void save(DisputeCase finished, CaseOutcome outcome) {
            stored.put(finished.id(), outcome);
        }
    };
    DecisionPublisher publisher = published::add;

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    ProcessDisputeOpened process() {
        var mcp = new FakeMcp()
                .tool(McpServer.LEDGER, "get_transaction", a -> new McpToolResult(
                        "{\"card_id\":\"CRD-000042\",\"posted_on\":\"2026-10-01\"}", false))
                .tool(McpServer.LEDGER, "search_transactions", a -> new McpToolResult("{\"items\":[]}", false))
                .tool(McpServer.LEDGER, "get_authorisations", a -> new McpToolResult("{\"items\":[]}", false));
        var audit = new RecordingAudit();
        var clock = new TickingClock(Instant.parse("2026-11-20T09:00:00Z"));
        var settings = Fakes.settings(new LoopBudget(8, 20, 200_000, Duration.ofMinutes(5)));
        var json = new FakeJson();
        var handle = new HandleDisputeOpened(new TriageDispute(llm, json, audit, clock, settings),
                new InvestigateDispute(llm, mcp, json, audit, clock, executor, settings, Fakes.SKILLS), audit, clock);
        return new ProcessDisputeOpened(handle, store, o -> publisher.publish(o));
    }

    @Test
    void aNewDisputeIsInvestigatedSavedThenPublished() {
        llm.then(StopReason.REFUSAL).then(StopReason.REFUSAL); // triage falls back, the investigation escalates

        var result = process().process(DISPUTE);

        assertThat(result.redelivery()).isFalse();
        assertThat(result.outcome().state()).isEqualTo(CaseState.ESCALATED);
        assertThat(result.outcome().escalationReason()).isEqualTo(EscalationReason.MODEL_REFUSED);
        assertThat(stored).containsKey(DISPUTE.disputeId());
        assertThat(published).containsExactly(result.outcome());
    }

    @Test
    void aRedeliveredDisputeIsNotInvestigatedAgainButIsPublishedAgain() {
        llm.then(StopReason.REFUSAL).then(StopReason.REFUSAL);
        var process = process();
        var first = process.process(DISPUTE);

        var second = process.process(DISPUTE);

        assertThat(second.redelivery()).isTrue();
        assertThat(second.outcome()).isEqualTo(first.outcome());
        assertThat(llm.requests).hasSize(2); // no new model call
        assertThat(published).hasSize(2);
    }

    @Test
    void aFailedPublishAfterTheSaveIsCompletedByTheRedelivery() {
        llm.then(StopReason.REFUSAL).then(StopReason.REFUSAL);
        publisher = o -> {
            throw new IllegalStateException("broker down");
        };
        var process = process();

        assertThatThrownBy(() -> process.process(DISPUTE)).hasMessage("broker down");
        assertThat(stored).containsKey(DISPUTE.disputeId());

        publisher = published::add;
        var redelivered = process.process(DISPUTE);

        assertThat(redelivered.redelivery()).isTrue();
        assertThat(published).containsExactly(redelivered.outcome());
        assertThat(llm.requests).hasSize(2);
    }
}
