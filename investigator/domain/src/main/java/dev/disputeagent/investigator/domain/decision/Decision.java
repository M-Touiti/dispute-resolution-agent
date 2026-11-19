package dev.disputeagent.investigator.domain.decision;

import dev.disputeagent.investigator.domain.DecisionOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * The agent's final answer for a case: produced as structured output against {@link #jsonSchema()}, parsed by an
 * adapter, then checked by the {@code PolicyGuard} before anything else happens.
 *
 * <p>The record holds what the model said. Structural validity is the schema's job; whether the decision is safe
 * and consistent with the case is the guard's.
 */
public record Decision(
        DecisionOutcome outcome,
        Confidence confidence,
        String rationale,
        List<String> citedEvidenceIds,
        List<String> citedPolicySections,
        List<ProposedAction> proposedActions) {

    private static final String SCHEMA_RESOURCE = "decision.schema.json";

    public Decision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(rationale, "rationale");
        citedEvidenceIds = List.copyOf(citedEvidenceIds);
        citedPolicySections = List.copyOf(citedPolicySections);
        proposedActions = List.copyOf(proposedActions);
    }

    /**
     * The JSON Schema the model's final answer must match, sent as {@code output_config.format}. It stays inside the
     * structured-output subset: no numeric or length constraints, {@code additionalProperties: false} everywhere.
     */
    public static String jsonSchema() {
        try (InputStream in = Decision.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
