package dev.disputeagent.ledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.ledger.LedgerDatabase;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The six tools over real Streamable HTTP against PostgreSQL loaded with the generated seed. Expected values come
 * from the generated CSV files, not from hard-coded ids, so the tests follow the generator.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerToolsDatabaseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        LedgerDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    McpSyncClient client;
    List<Map<String, String>> transactions;
    List<Map<String, String>> authorisations;

    @BeforeAll
    void connect() throws IOException {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint(McpServerConfig.MCP_ENDPOINT)
                .build();
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
        transactions = csv("transactions.csv");
        authorisations = csv("authorisations.csv");
    }

    @AfterAll
    void disconnect() {
        client.close();
    }

    // Lookups ----------------------------------------------------------------------------------------------------

    @Test
    void getCardReturnsTheLastFourDigitsAndNoCardNumber() throws IOException {
        Map<String, String> card = csv("cards.csv").getFirst();

        JsonNode result = ok("get_card", Map.of("card_id", card.get("card_id")));

        assertThat(result.propertyNames()).containsExactlyInAnyOrder(
                "card_id", "cardholder_id", "pan_last4", "product", "status", "issued_on", "expiry");
        assertThat(result.get("pan_last4").asString()).isEqualTo(card.get("pan_last4")).hasSize(4);
        assertThat(result.get("issued_on").asString()).isEqualTo(card.get("issued_on"));
    }

    @Test
    void getCardholderNeverReturnsTheEmailAddress() throws IOException {
        Map<String, String> holder = csv("cardholders.csv").getFirst();

        JsonNode result = ok("get_cardholder", Map.of("cardholder_id", holder.get("cardholder_id")));

        assertThat(result.propertyNames()).doesNotContain("email");
        assertThat(result.get("family_name").asString()).isEqualTo(holder.get("family_name"));
        assertThat(result.get("customer_since").asString()).isEqualTo(holder.get("customer_since"));
    }

    @Test
    void getMerchantReturnsTheMerchantRecord() throws IOException {
        Map<String, String> merchant = csv("merchants.csv").getFirst();

        JsonNode result = ok("get_merchant", Map.of("merchant_id", merchant.get("merchant_id")));

        assertThat(result.get("statement_descriptor").asString()).isEqualTo(merchant.get("statement_descriptor"));
        assertThat(result.get("ecommerce").asBoolean()).isEqualTo(Boolean.parseBoolean(merchant.get("ecommerce")));
    }

    @Test
    void getTransactionReturnsARefundWithTheTransactionItRefunds() {
        Map<String, String> refund = transactions.stream().filter(t -> t.get("kind").equals("REFUND")).findFirst()
                .orElseThrow();

        JsonNode result = ok("get_transaction", Map.of("transaction_id", refund.get("transaction_id")));

        assertThat(result.get("related_transaction_id").asString()).isEqualTo(refund.get("related_transaction_id"));
        assertThat(result.get("amount_minor").asLong()).isEqualTo(Long.parseLong(refund.get("amount_minor")));
        assertThat(result.get("transaction_at").asString()).isEqualTo(refund.get("transaction_at"));
        assertThat(result.get("posted_on").asString()).isEqualTo(refund.get("posted_on"));
        assertThat(result.get("auth_id").isNull()).isTrue();
    }

    // Lists and pagination ---------------------------------------------------------------------------------------

    @Test
    void searchTransactionsPagesThroughEveryTransactionOnTheCardNewestFirst() {
        String card = busiestCard();
        List<String> expected = newestFirst(transactions, "transaction_at", "transaction_id",
                t -> t.get("card_id").equals(card));

        List<String> seen = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            Map<String, Object> args = new HashMap<>(Map.of("card_id", card, "page_size", 7));
            if (token != null) {
                args.put("page_token", token);
            }
            JsonNode page = ok("search_transactions", args);
            page.get("items").forEach(t -> seen.add(t.get("transaction_id").asString()));
            token = page.get("next_page_token").isNull() ? null : page.get("next_page_token").asString();
            pages++;
        } while (token != null);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo((expected.size() + 6) / 7);
    }

    @Test
    void searchTransactionsDateFilterIsInclusiveInUtc() {
        Map<String, String> sample = transactions.get(transactions.size() / 2);
        String card = sample.get("card_id");
        String day = sample.get("transaction_at").substring(0, 10);
        List<String> expected = newestFirst(transactions, "transaction_at", "transaction_id",
                t -> t.get("card_id").equals(card) && t.get("transaction_at").startsWith(day));

        JsonNode page = ok("search_transactions", Map.of("card_id", card, "from_date", day, "to_date", day));

        assertThat(ids(page, "transaction_id")).containsExactlyElementsOf(expected).isNotEmpty();
    }

    @Test
    void searchTransactionsFiltersByKind() {
        Map<String, String> refund = transactions.stream().filter(t -> t.get("kind").equals("REFUND")).findFirst()
                .orElseThrow();
        String card = refund.get("card_id");
        List<String> expected = newestFirst(transactions, "transaction_at", "transaction_id",
                t -> t.get("card_id").equals(card) && t.get("kind").equals("REFUND"));

        JsonNode page = ok("search_transactions", Map.of("card_id", card, "kind", "REFUND"));

        assertThat(ids(page, "transaction_id")).containsExactlyElementsOf(expected);
    }

    @Test
    void getAuthorisationsIncludesDeclinesAndFiltersByIdAndResponse() {
        Map<String, String> declined = authorisations.stream()
                .filter(a -> a.get("response").equals("DECLINED")).findFirst().orElseThrow();
        String card = declined.get("card_id");

        JsonNode one = ok("get_authorisations", Map.of("card_id", card, "auth_id", declined.get("auth_id")));
        JsonNode declines = ok("get_authorisations", Map.of("card_id", card, "response", "DECLINED",
                "page_size", 50));

        assertThat(ids(one, "auth_id")).containsExactly(declined.get("auth_id"));
        JsonNode auth = one.get("items").get(0);
        assertThat(auth.get("decline_reason").asString()).isEqualTo(declined.get("decline_reason"));
        assertThat(auth.get("fraud_flag").asBoolean()).isEqualTo(Boolean.parseBoolean(declined.get("fraud_flag")));
        assertThat(ids(declines, "auth_id")).containsExactlyElementsOf(newestFirst(authorisations, "requested_at",
                "auth_id", a -> a.get("card_id").equals(card) && a.get("response").equals("DECLINED")));
    }

    // Errors -----------------------------------------------------------------------------------------------------

    static List<Object[]> invalidCalls() {
        String otherToolsToken = ToolArguments.pageToken("get_authorisations", Instant.EPOCH, "AUTH-000001");
        return List.of(
                new Object[] {"get_card", Map.of("card_id", "card-1"), "INVALID_ARGUMENT: card_id must look like"},
                new Object[] {"get_card", Map.of("card_id", "CRD-999999"), "NOT_FOUND: no card CRD-999999"},
                new Object[] {"get_transaction", Map.of("transaction_id", "TXN-1"), "INVALID_ARGUMENT"},
                new Object[] {"search_transactions", Map.of("card_id", "CRD-999999"), "NOT_FOUND"},
                new Object[] {"search_transactions", Map.of("card_id", "CRD-000001", "page_size", 0),
                        "INVALID_ARGUMENT: page_size"},
                new Object[] {"search_transactions", Map.of("card_id", "CRD-000001", "page_size", 51),
                        "INVALID_ARGUMENT: page_size"},
                new Object[] {"search_transactions",
                        Map.of("card_id", "CRD-000001", "from_date", "2026-10-02", "to_date", "2026-10-01"),
                        "INVALID_ARGUMENT: from_date must not be after to_date"},
                new Object[] {"search_transactions", Map.of("card_id", "CRD-000001", "page_token", otherToolsToken),
                        "INVALID_ARGUMENT: page_token"},
                new Object[] {"search_transactions", Map.of("card_id", "CRD-000001", "page_token", "not-a-token"),
                        "INVALID_ARGUMENT: page_token"});
    }

    @ParameterizedTest
    @MethodSource("invalidCalls")
    void badArgumentsReturnAToolErrorTheModelCanCorrect(String tool, Map<String, Object> args, String prefix) {
        CallToolResult result = client.callTool(new CallToolRequest(tool, args));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).startsWith(prefix);
    }

    // The database role ------------------------------------------------------------------------------------------

    @Test
    void theServerConnectsWithARoleThatCannotWrite() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger.cards SET status = 'BLOCKED'").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theRoleItselfHasNoWritePrivilegeAndNoAccessToDisputes() {
        // Independent of the read-only connection flag: the grants alone forbid it.
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("ledger_reader");
        assertThat(privilege("has_table_privilege('ledger.cards', 'UPDATE')")).isFalse();
        assertThat(privilege("has_table_privilege('ledger.transactions', 'INSERT')")).isFalse();
        assertThat(privilege("has_table_privilege('ledger.transactions', 'SELECT')")).isTrue();
        assertThat(privilege("has_schema_privilege('intake', 'USAGE')")).isFalse();
    }

    @Test
    void theServerConnectsWithARoleThatCannotReadDisputes() {
        assertThatThrownBy(() -> jdbc.sql("SELECT count(*) FROM intake.disputes").query(Long.class).single())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("permission denied for schema intake");
    }

    private boolean privilege(String check) {
        return jdbc.sql("SELECT " + check).query(Boolean.class).single();
    }

    // Helpers ----------------------------------------------------------------------------------------------------

    private JsonNode ok(String tool, Map<String, Object> args) {
        CallToolRequest request = CallToolRequest.builder().name(tool).arguments(args).build();
        CallToolResult result = client.callTool(request);
        assertThat(result.isError()).as(text(result)).isNotEqualTo(Boolean.TRUE);
        return JSON.readTree(text(result));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static List<String> ids(JsonNode page, String field) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(n -> out.add(n.get(field).asString()));
        return out;
    }

    private String busiestCard() {
        Map<String, Integer> counts = new HashMap<>();
        transactions.forEach(t -> counts.merge(t.get("card_id"), 1, Integer::sum));
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow().getKey();
    }

    private static List<String> newestFirst(List<Map<String, String>> rows, String at, String id,
            Predicate<Map<String, String>> filter) {
        return rows.stream().filter(filter)
                .sorted(Comparator.<Map<String, String>, Instant>comparing(r -> Instant.parse(r.get(at)))
                        .thenComparing(r -> r.get(id)).reversed())
                .map(r -> r.get(id))
                .toList();
    }

    /** Reads a generated CSV (RFC 4180 quoting, no embedded line breaks in these files). */
    static List<Map<String, String>> csv(String name) throws IOException {
        List<String> lines = Files.readAllLines(LedgerDatabase.DATA.resolve(name), StandardCharsets.UTF_8);
        List<String> header = fields(lines.getFirst());
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            List<String> values = fields(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), values.get(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> fields(String line) {
        List<String> out = new ArrayList<>();
        var current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                current.append('"');
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }
}
