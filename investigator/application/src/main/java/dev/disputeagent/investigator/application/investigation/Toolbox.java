package dev.disputeagent.investigator.application.investigation;

import dev.disputeagent.investigator.application.port.llm.ToolDefinition;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpTool;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tools the model may use, discovered from the allowed MCP servers (ADR 0003). The write server is never on the
 * list: the model proposes actions in its decision and never receives write tools. Routing is by tool name.
 */
final class Toolbox {

    /** The servers the investigating model may reach in M1. */
    static final Set<McpServer> ALLOWED = Set.of(McpServer.LEDGER, McpServer.POLICY);

    private final Map<String, McpTool> byName;

    private Toolbox(Map<String, McpTool> byName) {
        this.byName = byName;
    }

    static Toolbox discover(McpToolPort mcp) {
        Map<String, McpTool> byName = new LinkedHashMap<>();
        List<McpServer> servers = new ArrayList<>(ALLOWED);
        servers.sort(Comparator.naturalOrder());
        for (McpServer server : servers) {
            if (server.writes()) {
                throw new IllegalStateException("a write server must never be offered to the model: " + server);
            }
            for (McpTool tool : mcp.listTools(server)) {
                McpTool previous = byName.putIfAbsent(tool.name(), tool);
                if (previous != null) {
                    throw new IllegalStateException("tool " + tool.name() + " offered by " + previous.server()
                            + " and " + tool.server());
                }
            }
        }
        if (byName.isEmpty()) {
            throw new IllegalStateException("no tools discovered");
        }
        return new Toolbox(byName);
    }

    /** Strict definitions for the request; {@code LlmRequest} sorts them by name. */
    List<ToolDefinition> definitions() {
        return byName.values().stream()
                .map(t -> new ToolDefinition(t.name(), t.description(), t.inputSchemaJson(), true))
                .toList();
    }

    Optional<McpServer> serverOf(String toolName) {
        return Optional.ofNullable(byName.get(toolName)).map(McpTool::server);
    }
}
