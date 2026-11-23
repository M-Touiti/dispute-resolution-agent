package dev.disputeagent.actions.mcp;

import dev.disputeagent.actions.ActionsDatabase;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ActionsDatabase.register(registry);
    }

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
        assertThat(client.getServerInfo().name()).isEqualTo("actions-mcp");
        assertThat(client.getServerCapabilities().tools()).isNotNull();
        assertThat(client.getServerCapabilities().resources()).isNull();
        assertThat(client.getServerCapabilities().prompts()).isNull();
    }

    @Test
    void everyToolIsAWriteAndOnlyCloseIsDestructive() {
        assertThat(client.listTools().tools()).allSatisfy(tool -> {
            assertThat(tool.annotations().readOnlyHint()).as(tool.name()).isFalse();
            assertThat(tool.annotations().idempotentHint()).as(tool.name()).isFalse();
            assertThat(tool.annotations().destructiveHint()).as(tool.name())
                    .isEqualTo(tool.name().equals("close_dispute"));
        });
    }

    @Test
    void aWellFormedCallWithAMadeUpTokenIsRejectedAndChangesNothing() {
        CallToolResult result = client.callTool(call("issue_provisional_credit", Map.of(
                "dispute_id", "DSP-000001",
                "amount_minor", 4250,
                "currency", "EUR",
                ActionTools.APPROVAL_TOKEN, "any-token")));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).startsWith("APPROVAL_REJECTED: MALFORMED:").endsWith("No action was taken.");
    }

    @Test
    void aCallWithoutAnApprovalTokenFailsSchemaValidation() {
        CallToolResult result = client.callTool(call("close_dispute", Map.of(
                "dispute_id", "DSP-000001",
                "reason", "Opened after the time limit.",
                "policy_section_ids", List.of("time-limits"))));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).doesNotStartWith("APPROVAL_REJECTED").contains(ActionTools.APPROVAL_TOKEN);
    }

    @Test
    void anEvidenceKindOutsideTheEnumFailsSchemaValidation() {
        CallToolResult result = client.callTool(call("request_merchant_evidence", Map.of(
                "dispute_id", "DSP-000001",
                "evidence", List.of("SELFIE"),
                "note", "Show delivery.",
                ActionTools.APPROVAL_TOKEN, "any-token")));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).as("refused by the schema, before any token check").doesNotStartWith("APPROVAL_REJECTED");
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    /** Key-sorted, indented, LF line endings, trailing newline: stable across JVMs and operating systems. */
    private String canonical(List<Tool> tools) throws Exception {
        Object tree = CANONICAL.readValue(mcpJsonMapper.writeValueAsString(tools), Object.class);
        return CANONICAL.writeValueAsString(tree).replace("\r\n", "\n") + "\n";
    }

    /** {@code tools/call} with the SDK 2.2 builder (the two-argument constructor is deprecated). */
    private static CallToolRequest call(String tool, Map<String, Object> arguments) {
        return CallToolRequest.builder(tool).arguments(arguments).build();
    }
}
