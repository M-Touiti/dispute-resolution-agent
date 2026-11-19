package dev.disputeagent.ledger.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the MCP server onto the servlet container without Spring AI: the SDK's Streamable HTTP servlet is registered
 * directly at {@code /mcp}.
 */
@Configuration(proxyBeanMethods = false)
class McpServerConfig {

    static final String MCP_ENDPOINT = "/mcp";

    @Bean
    McpJsonMapper mcpJsonMapper() {
        return new JacksonMcpJsonMapper(JsonMapper.builder().build());
    }

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport(McpJsonMapper jsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint(MCP_ENDPOINT)
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        var registration = new ServletRegistrationBean<>(transport, MCP_ENDPOINT);
        registration.setName("mcp");
        registration.setAsyncSupported(true); // SSE responses
        return registration;
    }

    @Bean(destroyMethod = "close")
    McpSyncServer mcpServer(
            HttpServletStreamableServerTransportProvider transport,
            McpJsonMapper jsonMapper,
            LedgerToolHandlers handlers,
            @Value("${ledger-mcp.version}") String version) {
        return McpServer.sync(transport)
                .serverInfo("ledger-mcp", version)
                .instructions("Read-only access to the synthetic card ledger: cardholders, cards, merchants, "
                        + "transactions and authorisations. Results are bounded and paginated.")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .validateToolInputs(true)
                .tools(LedgerTools.specifications(jsonMapper, handlers))
                .build();
    }
}
