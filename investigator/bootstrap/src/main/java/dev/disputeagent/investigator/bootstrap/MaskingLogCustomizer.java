package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.otel.CardNumbers;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks card numbers in every string of a structured (JSON) log line: the message, the context fields, the stack
 * trace (ADR 0008). Configured with {@code logging.structured.json.customizer}.
 */
public final class MaskingLogCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(JsonWriter.ValueProcessor.of(String.class, CardNumbers::mask));
    }
}
