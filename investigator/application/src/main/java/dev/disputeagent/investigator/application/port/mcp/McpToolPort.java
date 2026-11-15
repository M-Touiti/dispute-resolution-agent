package dev.disputeagent.investigator.application.port.mcp;

import java.util.List;

/**
 * The only way the application reaches MCP tool servers. Every call passes through gating and auditing in the
 * application before it reaches this port.
 */
public interface McpToolPort {

    /** Tools currently offered by one server ({@code tools/list}). */
    List<McpTool> listTools(McpServer server);

    /**
     * Calls a tool ({@code tools/call}). A tool-level failure is returned as a result with {@code isError} set;
     * transport failures are thrown.
     */
    McpToolResult call(McpToolCall call);
}
