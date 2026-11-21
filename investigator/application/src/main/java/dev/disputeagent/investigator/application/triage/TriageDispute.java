package dev.disputeagent.investigator.application.triage;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.investigation.InvestigateDispute;
import dev.disputeagent.investigator.application.investigation.InvestigationSettings;
import dev.disputeagent.investigator.application.investigation.Resources;
import dev.disputeagent.investigator.application.investigation.Untrusted;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditEvent.Stage;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.json.JsonPort;
import dev.disputeagent.investigator.application.port.json.Parsed;
import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.LlmMessage;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.OutputFormat;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.SystemBlock;
import dev.disputeagent.investigator.domain.DisputeCategory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Classifies the cardholder's statement on the cheap model, as structured output. Triage informs the investigation
 * and never blocks it: on a refusal, an invalid answer or an unreachable model, the category recorded on the dispute
 * is used and the fallback is audited. When triage and the record disagree, the investigator gets both.
 */
public final class TriageDispute {

    private static final String SYSTEM_PROMPT = Resources.text(TriageDispute.class, "system-prompt.md");
    private static final String SCHEMA = Resources.text(TriageDispute.class, "triage.schema.json");

    private final LlmPort llm;
    private final JsonPort json;
    private final AuditLog audit;
    private final Clock clock;
    private final InvestigationSettings settings;

    public TriageDispute(LlmPort llm, JsonPort json, AuditLog audit, Clock clock, InvestigationSettings settings) {
        this.llm = llm;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
    }

    public static String schema() {
        return SCHEMA;
    }

    /** The category to investigate under: the triaged one, or the reported one if triage could not decide. */
    public DisputeCategory triage(DisputeOpened dispute) {
        String user = "Category recorded when the dispute was opened: " + dispute.category() + "\n\n"
                + Untrusted.wrap("untrusted_cardholder_statement", Map.of(), dispute.cardholderStatement());
        var request = new LlmRequest(settings.triageModel(), settings.triageEffort(), settings.triageMaxTokens(),
                List.of(new SystemBlock(SYSTEM_PROMPT, true)), List.of(LlmMessage.userText(user)), List.of(),
                new OutputFormat(SCHEMA));
        Instant start = clock.instant();
        LlmResponse response;
        try {
            response = llm.send(request);
        } catch (RuntimeException e) {
            audit.record(new AuditEvent.ModelCallFailed(dispute.disputeId(), start, Stage.TRIAGE,
                    settings.triageModel(), InvestigateDispute.failure(e), Duration.between(start, clock.instant())));
            return fallBack(dispute, "triage model call failed: " + InvestigateDispute.failure(e));
        }
        audit.record(new AuditEvent.ModelCalled(dispute.disputeId(), start, Stage.TRIAGE, settings.triageModel(),
                response.model(), response.id(), response.stopReason(), response.usage(), response.cost(),
                Duration.between(start, clock.instant())));
        if (response.stopReason() != StopReason.END_TURN) {
            return fallBack(dispute, "stop_reason " + response.stopReason());
        }
        String text = response.content().stream()
                .filter(ContentBlock.Text.class::isInstance).map(b -> ((ContentBlock.Text) b).text())
                .reduce((first, second) -> second).orElse("");
        Parsed<Triage> parsed = json.triage(text);
        if (!parsed.ok()) {
            return fallBack(dispute, "invalid answer: " + String.join("; ", parsed.errors()));
        }
        return parsed.value().category();
    }

    private DisputeCategory fallBack(DisputeOpened dispute, String reason) {
        audit.record(new AuditEvent.TriageFellBack(dispute.disputeId(), clock.instant(), reason));
        return dispute.category();
    }
}
