package dev.disputeagent.investigator.adapters.mcpclient;

import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpTool;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link McpToolPort} on the MCP Java SDK's Streamable HTTP client (ADR 0003): one client per configured server,
 * connected on first use, tools discovered once ({@code tools/list}, every page) and kept for the client's life.
 *
 * <ul>
 *   <li><b>Allowlist.</b> Only the servers given at construction exist for this adapter; in M1 the bootstrap gives
 *       the ledger and policy servers and never the actions server.</li>
 *   <li><b>Strict schemas.</b> A discovered tool whose input schema is not strict-compatible fails discovery.</li>
 *   <li><b>Bounded.</b> A request timeout per call and a maximum response size.</li>
 *   <li><b>Failures.</b> A tool-level error is a result with {@code isError}; a transport failure is thrown (the
 *       application turns it into an {@code UNAVAILABLE} tool result) and drops the client, so the next call
 *       reconnects with a fresh session.</li>
 * </ul>
 */
public final class McpClientAdapter implements McpToolPort, AutoCloseable {

    /** Applied to every server: connection and per-request timeouts, and the largest response accepted. */
    public record Settings(Duration connectTimeout, Duration requestTimeout, int maxResponseBytes) {
        public Settings {
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (maxResponseBytes <= 0) {
                throw new IllegalArgumentException("maxResponseBytes must be positive");
            }
        }
    }

    private static final TypeRef<Map<String, Object>> OBJECT = new TypeRef<>() {
    };

    private final Map<McpServer, URI> endpoints;
    private final Settings settings;
    private final McpJsonMapper json = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    private final ConcurrentMap<McpServer, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentMap<McpServer, List<McpTool>> tools = new ConcurrentHashMap<>();

    public McpClientAdapter(Map<McpServer, URI> endpoints, Settings settings) {
        this.endpoints = new EnumMap<>(endpoints);
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public List<McpTool> listTools(McpServer server) {
        return tools.computeIfAbsent(server, this::discover);
    }

    @Override
    public McpToolResult call(McpToolCall call) {
        McpSyncClient client = client(call.server());
        Map<String, Object> arguments;
        try {
            arguments = json.readValue(call.argumentsJson(), OBJECT);
        } catch (IOException e) {
            return new McpToolResult("INVALID_INPUT: the arguments are not a JSON object", true);
        }
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(McpSchema.CallToolRequest.builder(call.toolName()).arguments(arguments).build());
        } catch (RuntimeException e) {
            drop(call.server(), client);
            throw e;
        }
        return new McpToolResult(text(result), Boolean.TRUE.equals(result.isError()));
    }

    @Override
    public void close() {
        clients.values().forEach(McpSyncClient::closeGracefully);
        clients.clear();
    }

    private List<McpTool> discover(McpServer server) {
        McpSyncClient client = client(server);
        List<McpTool> found = new ArrayList<>();
        String cursor = null;
        do {
            McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
            for (McpSchema.Tool tool : page.tools()) {
                List<String> problems = StrictSchemas.violations(tool.inputSchema());
                if (!problems.isEmpty()) {
                    throw new IllegalStateException(server + " tool " + tool.name()
                            + " cannot be offered as a strict tool: " + String.join("; ", problems));
                }
                found.add(new McpTool(server, tool.name(), tool.description() == null ? "" : tool.description(),
                        write(tool.inputSchema())));
            }
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isEmpty());
        return List.copyOf(found);
    }

    private McpSyncClient client(McpServer server) {
        URI endpoint = endpoints.get(server);
        if (endpoint == null) {
            throw new IllegalStateException(server + " is not a server this investigator may reach");
        }
        return clients.computeIfAbsent(server, s -> connect(endpoint));
    }

    private McpSyncClient connect(URI endpoint) {
        var transport = HttpClientStreamableHttpTransport.builder(endpoint.getScheme() + "://" + endpoint.getAuthority())
                .endpoint(endpoint.getPath())
                .jsonMapper(json)
                .connectTimeout(settings.connectTimeout())
                .maxResponseSize(settings.maxResponseBytes())
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(settings.requestTimeout())
                .initializationTimeout(settings.requestTimeout())
                .clientInfo(new McpSchema.Implementation("dispute-investigator", "0.1.0"))
                .build();
        client.initialize();
        return client;
    }

    private void drop(McpServer server, McpSyncClient client) {
        if (clients.remove(server, client)) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // the session is already broken; the next call reconnects
            }
        }
    }

    private String text(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        return result.content().stream()
                .map(c -> c instanceof McpSchema.TextContent t ? t.text() : "[non-text content omitted]")
                .collect(Collectors.joining("\n"));
    }

    private String write(Map<String, Object> schema) {
        try {
            return json.writeValueAsString(schema);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
