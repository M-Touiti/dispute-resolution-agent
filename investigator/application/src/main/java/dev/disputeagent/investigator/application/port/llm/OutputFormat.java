package dev.disputeagent.investigator.application.port.llm;

import java.util.Objects;

/**
 * Structured output: the final text block must be JSON valid against {@code schemaJson}. Used for the decision,
 * instead of forced tool choice or prefill.
 */
public record OutputFormat(String schemaJson) {

    public OutputFormat {
        Objects.requireNonNull(schemaJson, "schemaJson");
    }
}
