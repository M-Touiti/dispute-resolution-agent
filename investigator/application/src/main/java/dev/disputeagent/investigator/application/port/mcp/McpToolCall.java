package dev.disputeagent.investigator.application.port.mcp;

import java.util.Objects;

/** A request to call one tool. {@code argumentsJson} is a JSON object. */
public record McpToolCall(McpServer server, String toolName, String argumentsJson) {

    public McpToolCall {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
    }
}
