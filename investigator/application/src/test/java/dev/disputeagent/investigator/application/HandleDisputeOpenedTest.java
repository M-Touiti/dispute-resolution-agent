package dev.disputeagent.investigator.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.investigation.InvestigateDispute;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.llm.ContentBlock.Text;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.application.support.Fakes;
import dev.disputeagent.investigator.application.support.Fakes.FakeJson;
import dev.disputeagent.investigator.application.support.Fakes.FakeMcp;
import dev.disputeagent.investigator.application.support.Fakes.RecordingAudit;
import dev.disputeagent.investigator.application.support.Fakes.ScriptedLlm;
import dev.disputeagent.investigator.application.support.Fakes.TickingClock;
import dev.disputeagent.investigator.application.triage.Triage;
import dev.disputeagent.investigator.application.triage.TriageDispute;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.LoopBudget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class HandleDisputeOpenedTest {

    @Test
    void triageDisagreementReachesTheInvestigatorWithBothCategories() {
        var dispute = new DisputeOpened(new CaseId("DSP-000005"), "CH-000042", "CRD-000042", "TXN-000100",
                DisputeCategory.DUPLICATE_CHARGE, Instant.parse("2026-10-30T10:00:00Z"), Money.eur(1_000),
                "I never made this purchase.", List.of());
        var llm = new ScriptedLlm()
                .then(StopReason.END_TURN, new Text("triage"))
                .then(StopReason.REFUSAL);
        var json = new FakeJson().triage("triage",
                new Triage(DisputeCategory.UNAUTHORISED_TRANSACTION, "the cardholder did not make it"));
        var mcp = new FakeMcp()
                .tool(McpServer.LEDGER, "get_transaction", a -> new McpToolResult(
                        "{\"card_id\":\"CRD-000042\",\"posted_on\":\"2026-10-01\"}", false))
                .tool(McpServer.LEDGER, "search_transactions", a -> new McpToolResult("{\"items\":[]}", false))
                .tool(McpServer.LEDGER, "get_authorisations", a -> new McpToolResult("{\"items\":[]}", false))
                .tool(McpServer.POLICY, "search_policy", a -> new McpToolResult("{}", false));
        var audit = new RecordingAudit();
        var clock = new TickingClock(Instant.parse("2026-11-20T09:00:00Z"));
        var settings = Fakes.settings(new LoopBudget(8, 20, 200_000, Duration.ofMinutes(5)));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var handler = new HandleDisputeOpened(new TriageDispute(llm, json, audit, clock, settings),
                    new InvestigateDispute(llm, mcp, json, audit, clock, executor, settings), audit, clock);

            var result = handler.handle(dispute);

            assertThat(result.reportedCategory()).isEqualTo(DisputeCategory.DUPLICATE_CHARGE);
            assertThat(result.category()).isEqualTo(DisputeCategory.UNAUTHORISED_TRANSACTION);
            String caseMessage = ((Text) llm.requests.get(1).messages().getFirst().content().getFirst()).text();
            assertThat(caseMessage).contains("recorded when the dispute was opened: DUPLICATE_CHARGE",
                    "according to triage: UNAUTHORISED_TRANSACTION");
            assertThat(audit.of(AuditEvent.StateChanged.class)).extracting(AuditEvent.StateChanged::to)
                    .containsExactly(CaseState.RECEIVED, CaseState.TRIAGED, CaseState.INVESTIGATING,
                            CaseState.ESCALATED);
        }
    }
}
