package dev.disputeagent.investigator.adapters.mcpclient;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real MCP server on the SDK's servlet Streamable HTTP transport, in embedded Tomcat: the same server stack as
 * ledger-mcp and actions-mcp, without Spring. Tools are added before {@link #start}.
 */
final class InProcessMcpServer implements AutoCloseable {

    static final String STRICT_SCHEMA = """
            {"type":"object","properties":{"card_id":{"type":"string","description":"Card id."}},\
            "required":["card_id"],"additionalProperties":false}""";

    private final McpJsonMapper json = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    private final List<SyncToolSpecification> tools = new ArrayList<>();
    private Tomcat tomcat;
    private McpSyncServer server;

    InProcessMcpServer tool(String name, String schema, Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
        tools.add(SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder(name, json, schema).description(name + " description").build())
                .callHandler((exchange, request) -> handler.apply(request.arguments()))
                .build());
        return this;
    }

    static McpSchema.CallToolResult text(String text, boolean isError) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(isError).build();
    }

    URI start(Path baseDir) throws LifecycleException {
        var transport = HttpServletStreamableServerTransportProvider.builder().jsonMapper(json).mcpEndpoint("/mcp")
                .build();
        server = McpServer.sync(transport)
                .serverInfo("in-process", "0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools)
                .build();
        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addContext("", null);
        Wrapper servlet = Tomcat.addServlet(context, "mcp", transport);
        servlet.setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        tomcat.start();
        return URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/mcp");
    }

    @Override
    public void close() throws LifecycleException {
        if (server != null) {
            server.closeGracefully();
        }
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }
}
