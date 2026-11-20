package dev.disputeagent.investigator.adapters.mcpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpTool;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class McpClientAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final McpClientAdapter.Settings SETTINGS =
            new McpClientAdapter.Settings(Duration.ofSeconds(2), Duration.ofSeconds(2), 1_000_000);

    @TempDir
    Path tomcatDir;

    private final InProcessMcpServer ledger = new InProcessMcpServer()
            .tool("get_card", InProcessMcpServer.STRICT_SCHEMA,
                    args -> InProcessMcpServer.text("{\"card_id\":\"" + args.get("card_id") + "\",\"pan_last4\":\"0271\"}",
                            false))
            .tool("get_merchant", InProcessMcpServer.STRICT_SCHEMA,
                    args -> InProcessMcpServer.text("NOT_FOUND: no merchant", true))
            .tool("slow", InProcessMcpServer.STRICT_SCHEMA, args -> {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return InProcessMcpServer.text("too late", false);
            });
    private McpClientAdapter adapter;

    @AfterEach
    void stop() throws Exception {
        if (adapter != null) {
            adapter.close();
        }
        ledger.close();
    }

    private McpClientAdapter adapter(Map<McpServer, URI> endpoints) {
        adapter = new McpClientAdapter(endpoints, SETTINGS);
        return adapter;
    }

    @Test
    void discoversTheToolsWithTheirSchemaUnchanged() throws Exception {
        URI uri = ledger.start(tomcatDir);

        List<McpTool> tools = adapter(Map.of(McpServer.LEDGER, uri)).listTools(McpServer.LEDGER);

        assertThat(tools).extracting(McpTool::name).containsExactlyInAnyOrder("get_card", "get_merchant", "slow");
        McpTool card = tools.stream().filter(t -> t.name().equals("get_card")).findFirst().orElseThrow();
        assertThat(card.server()).isEqualTo(McpServer.LEDGER);
        assertThat(card.description()).isEqualTo("get_card description");
        assertThat(JSON.readTree(card.inputSchemaJson())).isEqualTo(JSON.readTree(InProcessMcpServer.STRICT_SCHEMA));
    }

    @Test
    void discoveryRunsOncePerServer() throws Exception {
        var adapter = adapter(Map.of(McpServer.LEDGER, ledger.start(tomcatDir)));

        assertThat(adapter.listTools(McpServer.LEDGER)).isSameAs(adapter.listTools(McpServer.LEDGER));
    }

    @Test
    void callsAToolWithItsArgumentsAndReturnsTheText() throws Exception {
        var adapter = adapter(Map.of(McpServer.LEDGER, ledger.start(tomcatDir)));

        McpToolResult result = adapter.call(new McpToolCall(McpServer.LEDGER, "get_card", "{\"card_id\":\"CRD-000042\"}"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("{\"card_id\":\"CRD-000042\",\"pan_last4\":\"0271\"}");
    }

    @Test
    void aToolErrorIsAResultNotAnException() throws Exception {
        var adapter = adapter(Map.of(McpServer.LEDGER, ledger.start(tomcatDir)));

        McpToolResult result = adapter.call(new McpToolCall(McpServer.LEDGER, "get_merchant",
                "{\"card_id\":\"x\"}"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).isEqualTo("NOT_FOUND: no merchant");
    }

    @Test
    void parallelCallsShareOneSession() throws Exception {
        var adapter = adapter(Map.of(McpServer.LEDGER, ledger.start(tomcatDir)));
        List<Callable<McpToolResult>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String card = "CRD-%06d".formatted(i);
            calls.add(() -> adapter.call(new McpToolCall(McpServer.LEDGER, "get_card",
                    "{\"card_id\":\"" + card + "\"}")));
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<McpToolResult>> results = executor.invokeAll(calls);
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get().content()).contains("CRD-%06d".formatted(i));
            }
        }
    }

    @Test
    void aSlowToolTimesOutAndTheNextCallReconnects() throws Exception {
        var adapter = adapter(Map.of(McpServer.LEDGER, ledger.start(tomcatDir)));

        assertThatThrownBy(() -> adapter.call(new McpToolCall(McpServer.LEDGER, "slow", "{\"card_id\":\"x\"}")))
                .isInstanceOf(RuntimeException.class);
        assertThat(adapter.call(new McpToolCall(McpServer.LEDGER, "get_card", "{\"card_id\":\"CRD-1\"}")).isError())
                .isFalse();
    }

    @Test
    void aServerNotOnTheAllowlistIsNeverContacted() throws Exception {
        var adapter = adapter(Map.of(McpServer.LEDGER, ledger.start(tomcatDir)));

        assertThatThrownBy(() -> adapter.listTools(McpServer.ACTIONS)).hasMessageContaining("may reach");
        assertThatThrownBy(() -> adapter.call(new McpToolCall(McpServer.ACTIONS, "issue_provisional_credit", "{}")))
                .hasMessageContaining("ACTIONS is not a server this investigator may reach");
    }

    @Test
    void aToolWhoseSchemaIsNotStrictCompatibleFailsDiscovery() throws Exception {
        String loose = "{\"type\":\"object\",\"properties\":{\"page_size\":{\"type\":\"integer\",\"maximum\":50}},"
                + "\"additionalProperties\":false}";
        var server = new InProcessMcpServer().tool("search", loose, args -> InProcessMcpServer.text("", false));
        try {
            var adapter = adapter(Map.of(McpServer.POLICY, server.start(tomcatDir)));

            assertThatThrownBy(() -> adapter.listTools(McpServer.POLICY))
                    .hasMessageContaining("search cannot be offered as a strict tool")
                    .hasMessageContaining("'maximum' at /properties/page_size");
        } finally {
            server.close();
        }
    }

    @Test
    void anUnreachableServerThrows() {
        var adapter = adapter(Map.of(McpServer.LEDGER, URI.create("http://127.0.0.1:1/mcp")));

        assertThatThrownBy(() -> adapter.listTools(McpServer.LEDGER)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void strictSchemaRulesAcceptThePropertyNamedLikeAKeyword() {
        Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("pattern", Map.of("type", "string")));

        assertThat(StrictSchemas.violations(schema)).isEmpty();
    }
}
