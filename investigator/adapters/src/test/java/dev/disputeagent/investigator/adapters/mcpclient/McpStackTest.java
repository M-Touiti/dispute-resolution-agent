package dev.disputeagent.investigator.adapters.mcpclient;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpTool;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The client against the real servers of the compose stack: ledger-mcp (Java SDK) and policy-mcp (Python SDK), so
 * the Java client is checked against a server written with another SDK. Off by default; the compose smoke test and
 * the end-to-end test (task 1.13) cover the stack in CI.
 *
 * <pre>(cd deploy/compose && docker compose up -d --wait)
 * MCP_STACK_TEST=1 ./mvnw -pl adapters test -Dtest=McpStackTest</pre>
 */
@EnabledIfEnvironmentVariable(named = "MCP_STACK_TEST", matches = "1")
class McpStackTest {

    private final McpClientAdapter adapter = new McpClientAdapter(Map.of(
            McpServer.LEDGER, URI.create("http://localhost:8081/mcp"),
            McpServer.POLICY, URI.create("http://localhost:8082/mcp")),
            new McpClientAdapter.Settings(Duration.ofSeconds(5), Duration.ofSeconds(30), 1_000_000));

    @AfterEach
    void close() {
        adapter.close();
    }

    @Test
    void discoversBothServersAndEveryToolPassesTheStrictCheck() {
        assertThat(adapter.listTools(McpServer.LEDGER)).extracting(McpTool::name).containsExactlyInAnyOrder(
                "search_transactions", "get_transaction", "get_cardholder", "get_card", "get_merchant",
                "get_authorisations");
        assertThat(adapter.listTools(McpServer.POLICY)).extracting(McpTool::name)
                .containsExactlyInAnyOrder("search_policy", "get_policy_section");
    }

    @Test
    void callsTheJavaAndThePythonServer() {
        McpToolResult card = adapter.call(new McpToolCall(McpServer.LEDGER, "get_card", "{\"card_id\":\"CRD-000001\"}"));
        McpToolResult policy = adapter.call(new McpToolCall(McpServer.POLICY, "search_policy",
                "{\"query\":\"time limit for opening a dispute\",\"max_results\":3}"));
        McpToolResult invalid = adapter.call(new McpToolCall(McpServer.POLICY, "get_policy_section",
                "{\"section_id\":\"no-such-section\"}"));

        assertThat(card.isError()).as(card.content()).isFalse();
        assertThat(card.content()).contains("\"pan_last4\"");
        assertThat(policy.isError()).as(policy.content()).isFalse();
        assertThat(policy.content()).contains("time-limits");
        assertThat(invalid.isError()).isTrue();
        assertThat(invalid.content()).startsWith("NOT_FOUND");
    }
}
