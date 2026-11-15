package dev.disputeagent.investigator.application.port.mcp;

import java.util.Objects;

/** A tool as advertised by an MCP server. {@code inputSchemaJson} is the server's JSON Schema, unchanged. */
public record McpTool(McpServer server, String name, String description, String inputSchemaJson) {

    public McpTool {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchemaJson, "inputSchemaJson");
    }
}
