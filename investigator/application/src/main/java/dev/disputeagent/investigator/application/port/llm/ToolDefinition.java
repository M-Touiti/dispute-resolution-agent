package dev.disputeagent.investigator.application.port.llm;

import java.util.Objects;

/**
 * A tool offered to the model. Tools are discovered from MCP servers at runtime.
 *
 * @param name            unique tool name
 * @param description     what the tool does and when to use it
 * @param inputSchemaJson JSON Schema of the input; for strict tools it sets {@code additionalProperties: false}
 *                        and lists every property in {@code required}
 * @param strict          whether the provider must validate the tool input against the schema
 */
public record ToolDefinition(String name, String description, String inputSchemaJson, boolean strict) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchemaJson, "inputSchemaJson");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
