package dev.disputeagent.investigator.application.triage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.llm.ContentBlock.Text;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.support.Fakes;
import dev.disputeagent.investigator.application.support.Fakes.FakeJson;
import dev.disputeagent.investigator.application.support.Fakes.RecordingAudit;
import dev.disputeagent.investigator.application.support.Fakes.ScriptedLlm;
import dev.disputeagent.investigator.application.support.Fakes.TickingClock;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.investigation.LoopBudget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TriageDisputeTest {

    static final DisputeOpened DISPUTE = new DisputeOpened(new CaseId("DSP-000009"), "CH-1", "CRD-1", "TXN-1",
            DisputeCategory.DUPLICATE_CHARGE, Instant.parse("2026-10-30T10:00:00Z"), Money.eur(1_000),
            "I never made this purchase.", List.of());

    final ScriptedLlm llm = new ScriptedLlm();
    final FakeJson json = new FakeJson()
            .triage("unauthorised", new Triage(DisputeCategory.UNAUTHORISED_TRANSACTION, "never made it"));
    final RecordingAudit audit = new RecordingAudit();
    final TriageDispute triage = new TriageDispute(llm, json, audit, new TickingClock(Instant.EPOCH),
            Fakes.settings(new LoopBudget(8, 20, 200_000, Duration.ofMinutes(5))));

    @Test
    void usesTheCheapModelWithTheTriageSchemaAndNoTools() {
        llm.then(StopReason.END_TURN, new Text("unauthorised"));

        assertThat(triage.triage(DISPUTE)).isEqualTo(DisputeCategory.UNAUTHORISED_TRANSACTION);

        LlmRequest request = llm.requests.getFirst();
        assertThat(request.model()).isEqualTo("claude-haiku-5-5");
        assertThat(request.tools()).isEmpty();
        assertThat(request.outputFormat().schemaJson()).isEqualTo(TriageDispute.schema());
        assertThat(((Text) request.messages().getFirst().content().getFirst()).text())
                .contains("DUPLICATE_CHARGE", "<untrusted_cardholder_statement>");
    }

    @Test
    void fallsBackToTheRecordedCategoryOnARefusal() {
        llm.then(StopReason.REFUSAL);
        assertThat(triage.triage(DISPUTE)).isEqualTo(DisputeCategory.DUPLICATE_CHARGE);
        assertThat(audit.of(AuditEvent.TriageFellBack.class)).singleElement()
                .satisfies(f -> assertThat(f.reason()).contains("REFUSAL"));
    }

    @Test
    void fallsBackOnAnInvalidAnswer() {
        llm.then(StopReason.END_TURN, new Text("garbage"));
        assertThat(triage.triage(DISPUTE)).isEqualTo(DisputeCategory.DUPLICATE_CHARGE);
        assertThat(audit.of(AuditEvent.TriageFellBack.class)).hasSize(1);
    }

    /** The schema offers the model every category, and nothing else. */
    @Test
    void theSchemaNamesEveryCategory() {
        for (DisputeCategory category : DisputeCategory.values()) {
            assertThat(TriageDispute.schema()).contains("\"" + category.name() + "\"");
        }
    }

    @ParameterizedTest
    @EnumSource(DisputeCategory.class)
    void theModelsCategoryIsTakenWhateverWasRecorded(DisputeCategory answered) {
        for (DisputeCategory recorded : DisputeCategory.values()) {
            var dispute = new DisputeOpened(new CaseId("DSP-000009"), "CH-1", "CRD-1", "TXN-1", recorded,
                    Instant.parse("2026-10-30T10:00:00Z"), Money.eur(1_000), "Something is wrong.", List.of());
            var model = new ScriptedLlm().then(StopReason.END_TURN, new Text(answered.name()));
            var answers = new FakeJson().triage(answered.name(), new Triage(answered, "the statement says so"));
            var triage = new TriageDispute(model, answers, new RecordingAudit(), new TickingClock(Instant.EPOCH),
                    Fakes.settings(new LoopBudget(8, 20, 200_000, Duration.ofMinutes(5))));

            assertThat(triage.triage(dispute)).as(recorded + " answered " + answered).isEqualTo(answered);
            assertThat(((Text) model.requests.getFirst().messages().getFirst().content().getFirst()).text())
                    .contains(recorded.name());
        }
    }

    @ParameterizedTest
    @EnumSource(DisputeCategory.class)
    void aFallBackKeepsTheRecordedCategory(DisputeCategory recorded) {
        var dispute = new DisputeOpened(new CaseId("DSP-000009"), "CH-1", "CRD-1", "TXN-1", recorded,
                Instant.parse("2026-10-30T10:00:00Z"), Money.eur(1_000), "Something is wrong.", List.of());
        llm.then(StopReason.END_TURN, new Text("garbage"));
        assertThat(triage.triage(dispute)).isEqualTo(recorded);
    }

    @Test
    void fallsBackWhenTheModelIsUnreachable() {
        llm.thenThrow(new IllegalStateException("503"));
        assertThat(triage.triage(DISPUTE)).isEqualTo(DisputeCategory.DUPLICATE_CHARGE);
        assertThat(audit.of(AuditEvent.ModelCallFailed.class)).hasSize(1);
        assertThat(audit.of(AuditEvent.TriageFellBack.class)).hasSize(1);
    }
}
