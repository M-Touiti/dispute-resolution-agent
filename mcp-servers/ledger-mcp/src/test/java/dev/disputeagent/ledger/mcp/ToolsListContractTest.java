package dev.disputeagent.ledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the {@code tools/list} output over real Streamable HTTP, using the same SDK client the investigator uses.
 *
 * <p>An intended change: run {@code ./mvnw test -Dtest=ToolsListContractTest -Dcontract.update=true}, review the
 * diff of {@code tools-list.json}, and commit it with the change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolsListContractTest {

    private static final Path CONTRACT = Path.of("src/test/resources/contract/tools-list.json");

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @LocalServerPort
    int port;

    @Autowired
    McpJsonMapper mcpJsonMapper;

    McpSyncClient client;

    @BeforeEach
    void connect() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint(McpServerConfig.MCP_ENDPOINT)
                .build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        client.close();
    }

    @Test
    void toolsListMatchesTheContract() throws Exception {
        List<Tool> tools = client.listTools().tools().stream()
                .sorted(Comparator.comparing(Tool::name))
                .toList();
        String actual = canonical(tools);

        if (Boolean.getBoolean("contract.update")) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, actual, StandardCharsets.UTF_8);
        }
        String expected = Files.readString(CONTRACT, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void serverIdentifiesItselfAndOffersOnlyTools() {
        assertThat(client.getServerInfo().name()).isEqualTo("ledger-mcp");
        assertThat(client.getServerCapabilities().tools()).isNotNull();
        assertThat(client.getServerCapabilities().resources()).isNull();
        assertThat(client.getServerCapabilities().prompts()).isNull();
    }

    @Test
    void everyToolIsReadOnly() {
        assertThat(client.listTools().tools()).allSatisfy(tool -> {
            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.annotations().destructiveHint()).isFalse();
        });
    }

    @Test
    void inputsAreValidatedAgainstTheSchemaBeforeAnyHandlerRuns() {
        // No database in this test: an unknown property must be rejected by the SDK's schema validation.
        CallToolResult result = client.callTool(new CallToolRequest("get_card", Map.of("card", "CRD-000001")));

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text())
                .doesNotStartWith("INVALID_ARGUMENT")
                .doesNotStartWith("UNAVAILABLE");
    }

    /** Key-sorted, indented, LF line endings, trailing newline: stable across JVMs and operating systems. */
    private String canonical(List<Tool> tools) throws Exception {
        Object tree = CANONICAL.readValue(mcpJsonMapper.writeValueAsString(tools), Object.class);
        return CANONICAL.writeValueAsString(tree).replace("\r\n", "\n") + "\n";
    }
}
