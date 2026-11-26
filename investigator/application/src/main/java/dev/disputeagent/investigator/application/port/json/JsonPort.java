package dev.disputeagent.investigator.application.port.json;

import dev.disputeagent.investigator.application.triage.Triage;
import dev.disputeagent.investigator.domain.decision.Decision;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON for the application, which may not depend on a JSON library (module rules). The adapter validates structured
 * outputs against their schemas with the same validator the MCP servers use.
 */
public interface JsonPort {

    /** Validates the model's final answer against {@link Decision#jsonSchema()} and maps it. */
    Parsed<Decision> decision(String json);

    /** Validates the triage answer against the triage schema and maps it. */
    Parsed<Triage> triage(String json);

    /**
     * Every string value of {@code field} anywhere in the document, in document order; empty when the text is not
     * JSON. Used to read ids, page tokens and dates out of tool results.
     */
    List<String> valuesOf(String json, String field);

    /**
     * The integer {@code numberField} of the first object anywhere in the document whose {@code idField} is the string
     * {@code id}; empty when there is none or the text is not JSON. Used to read amounts out of tool results.
     */
    Optional<Long> numberIn(String json, String idField, String id, String numberField);

    /** A JSON object with these members, in key order. Values are strings, numbers or booleans. */
    String object(Map<String, ?> members);
}
