package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.approval.McpActionPort;
import dev.disputeagent.investigator.adapters.mcpclient.McpClientAdapter;
import dev.disputeagent.investigator.adapters.otel.ObservedActionPort;
import dev.disputeagent.investigator.adapters.otel.ObservedMcpToolPort;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The MCP clients (ADR 0003). The investigation's client knows only the ledger and policy servers: the model never
 * reaches the actions server. The investigator calls actions-mcp itself, after approval, through a second client that
 * knows only that server and is given to nothing but the action port (ADR 0005). Off with
 * {@code investigator.mcp.client=none} when a test supplies its own ports.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "investigator.mcp.client", havingValue = "sdk", matchIfMissing = true)
class McpConfiguration {

    @Bean(destroyMethod = "close")
    McpToolPort mcpToolPort(InvestigatorProperties properties, ObservationRegistry observations) {
        var mcp = properties.mcp();
        return new ObservedMcpToolPort(new McpClientAdapter(
                Map.of(McpServer.LEDGER, mcp.ledgerUrl(), McpServer.POLICY, mcp.policyUrl()),
                new McpClientAdapter.Settings(mcp.connectTimeout(), mcp.requestTimeout(), mcp.maxResponseBytes())),
                observations);
    }

    /** Not a McpToolPort bean: the actions-only client exists inside the action port and nowhere else. */
    @Bean(destroyMethod = "close")
    ObservedActionPort actionPort(InvestigatorProperties properties, ObservationRegistry observations) {
        var mcp = properties.mcp();
        return new ObservedActionPort(new McpActionPort(new McpClientAdapter(Map.of(McpServer.ACTIONS, mcp.actionsUrl()),
                new McpClientAdapter.Settings(mcp.connectTimeout(), mcp.requestTimeout(), mcp.maxResponseBytes()))),
                observations);
    }
}
