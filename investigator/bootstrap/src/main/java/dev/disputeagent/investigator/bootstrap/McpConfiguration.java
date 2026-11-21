package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.mcpclient.McpClientAdapter;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The MCP client (ADR 0003). Only the ledger and policy servers are given to it: the model never reaches the actions
 * server, and from M2 the investigator calls it itself after approval. Off with {@code investigator.mcp.client=none}
 * when a test supplies its own tool port.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "investigator.mcp.client", havingValue = "sdk", matchIfMissing = true)
class McpConfiguration {

    @Bean(destroyMethod = "close")
    McpToolPort mcpToolPort(InvestigatorProperties properties) {
        var mcp = properties.mcp();
        return new McpClientAdapter(
                Map.of(McpServer.LEDGER, mcp.ledgerUrl(), McpServer.POLICY, mcp.policyUrl()),
                new McpClientAdapter.Settings(mcp.connectTimeout(), mcp.requestTimeout(), mcp.maxResponseBytes()));
    }
}
