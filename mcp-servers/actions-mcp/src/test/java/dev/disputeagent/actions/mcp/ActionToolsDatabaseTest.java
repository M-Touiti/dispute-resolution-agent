package dev.disputeagent.actions.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.actions.ActionsDatabase;
import dev.disputeagent.actions.TestTokens;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three tools over real Streamable HTTP against PostgreSQL with the seed, as the investigator will call them: a
 * token signed for exact arguments, verified here, used once, inside the business limits (ADR 0005).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionToolsDatabaseTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        ActionsDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    McpSyncClient client;
    List<Map<String, Object>> disputes;
    int next;

    record Dispute(String id, long claimedMinor) {
    }

    @BeforeAll
    void connect() {
        client = client();
        disputes = jdbc.sql("SELECT dispute_id, claimed_amount_minor FROM intake.disputes ORDER BY dispute_id")
                .query().listOfRows();
    }

    @AfterAll
    void disconnect() {
        client.close();
    }

    McpSyncClient client() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint(McpServerConfig.MCP_ENDPOINT).build();
        var c = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        c.initialize();
        return c;
    }

    /** A dispute of the seed no other test touches. */
    synchronized Dispute fresh() {
        Map<String, Object> row = disputes.get(next++);
        return new Dispute((String) row.get("dispute_id"), ((Number) row.get("claimed_amount_minor")).longValue());
    }

    static Map<String, Object> credit(Dispute d, long amount) {
        return Map.of("dispute_id", d.id(), "amount_minor", amount, "currency", "EUR");
    }

    static Map<String, Object> close(Dispute d) {
        return Map.of("dispute_id", d.id(), "reason", "Chip and PIN with the card present.",
                "policy_section_ids", List.of("unauthorised-chip-and-pin"));
    }

    static Map<String, Object> evidence(Dispute d, List<String> items) {
        return Map.of("dispute_id", d.id(), "evidence", items, "note", "Show the signed delivery record.");
    }

    /** Calls the tool with a fresh token signed for exactly these arguments. */
    CallToolResult approved(McpSyncClient c, String tool, Map<String, Object> arguments) {
        return call(c, tool, arguments, TestTokens.sign(tool, arguments, "reviewer-1", UUID.randomUUID(), Instant.now()));
    }

    static CallToolResult call(McpSyncClient c, String tool, Map<String, Object> arguments, String token) {
        Map<String, Object> withToken = new HashMap<>(arguments);
        withToken.put("approval_token", token);
        return c.callTool(CallToolRequest.builder(tool).arguments(withToken).build());
    }

    static String text(CallToolResult r) {
        return ((TextContent) r.content().getFirst()).text();
    }

    long rows(Dispute d) {
        return jdbc.sql("SELECT count(*) FROM actions.actions WHERE dispute_id = :id").param("id", d.id())
                .query(Long.class).single();
    }

    // -------------------------------------------------------------------------------------------------------------

    @Test
    void aCreditIsCarriedOutOnceAndARetryWithTheSameTokenGetsTheSameAnswer() {
        Dispute d = fresh();
        String token = TestTokens.sign("issue_provisional_credit", credit(d, d.claimedMinor()), "reviewer-1",
                UUID.randomUUID(), Instant.now());

        CallToolResult first = call(client, "issue_provisional_credit", credit(d, d.claimedMinor()), token);
        CallToolResult retry = call(client, "issue_provisional_credit", credit(d, d.claimedMinor()), token);

        assertThat(first.isError()).as(text(first)).isFalse();
        JsonNode result = JSON.readTree(text(first));
        assertThat(result.get("status").stringValue()).isEqualTo("DONE");
        assertThat(result.get("dispute_id").stringValue()).isEqualTo(d.id());
        assertThat(retry.isError()).isFalse();
        assertThat(text(retry)).as("the stored answer, byte for byte").isEqualTo(text(first));
        assertThat(rows(d)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT amount_minor FROM actions.actions WHERE dispute_id = :id").param("id", d.id())
                .query(Long.class).single()).isEqualTo(d.claimedMinor());
    }

    @Test
    void creditsAddUpToTheDisputedAmountAndNoFurther() {
        Dispute d = fresh();
        long half = d.claimedMinor() / 2;

        assertThat(approved(client, "issue_provisional_credit", credit(d, half)).isError()).isFalse();
        CallToolResult over = approved(client, "issue_provisional_credit", credit(d, d.claimedMinor() - half + 1));
        CallToolResult rest = approved(client, "issue_provisional_credit", credit(d, d.claimedMinor() - half));

        assertThat(over.isError()).isTrue();
        assertThat(text(over)).startsWith("APPROVAL_REJECTED: LIMIT_EXCEEDED:").endsWith("No action was taken.");
        assertThat(rest.isError()).as(text(rest)).isFalse();
        assertThat(rows(d)).isEqualTo(2);
    }

    @Test
    void aClosedDisputeTakesNoFurtherActionButAnsweresItsOwnRetry() {
        Dispute d = fresh();
        String closeToken = TestTokens.sign("close_dispute", close(d), "reviewer-1", UUID.randomUUID(),
                Instant.now());

        CallToolResult closed = call(client, "close_dispute", close(d), closeToken);
        CallToolResult credit = approved(client, "issue_provisional_credit", credit(d, 1));
        CallToolResult ask = approved(client, "request_merchant_evidence", evidence(d, List.of("RECEIPT")));
        CallToolResult retry = call(client, "close_dispute", close(d), closeToken);

        assertThat(closed.isError()).as(text(closed)).isFalse();
        assertThat(text(credit)).startsWith("APPROVAL_REJECTED: DISPUTE_CLOSED:");
        assertThat(text(ask)).startsWith("APPROVAL_REJECTED: DISPUTE_CLOSED:");
        assertThat(retry.isError()).as("the token was used for this very closure").isFalse();
        assertThat(text(retry)).isEqualTo(text(closed));
        assertThat(rows(d)).isEqualTo(1);
    }

    @Test
    void anUnknownDisputeIsRefused() {
        Dispute ghost = new Dispute("DSP-999999", 100);

        CallToolResult r = approved(client, "issue_provisional_credit", credit(ghost, 100));

        assertThat(text(r)).startsWith("APPROVAL_REJECTED: UNKNOWN_DISPUTE:");
        assertThat(rows(ghost)).isZero();
    }

    @Test
    void argumentsChangedAfterApprovalChangeNothing() {
        Dispute d = fresh();
        String token = TestTokens.sign("issue_provisional_credit", credit(d, 100), "reviewer-1", UUID.randomUUID(),
                Instant.now());

        CallToolResult r = call(client, "issue_provisional_credit", credit(d, 200), token);

        assertThat(text(r)).startsWith("APPROVAL_REJECTED: ARGUMENTS_MISMATCH:");
        assertThat(rows(d)).isZero();
    }

    @Test
    void aTokenSignedByAnyOtherKeyOrExpiredChangesNothing() {
        Dispute d = fresh();
        String expired = TestTokens.sign("issue_provisional_credit", credit(d, 100), "reviewer-1", UUID.randomUUID(),
                Instant.now().minusSeconds(400));
        String forged = expired.substring(0, expired.lastIndexOf('.') + 1) + "A".repeat(86);

        assertThat(text(call(client, "issue_provisional_credit", credit(d, 100), expired)))
                .startsWith("APPROVAL_REJECTED: EXPIRED:");
        assertThat(text(call(client, "issue_provisional_credit", credit(d, 100), forged)))
                .startsWith("APPROVAL_REJECTED: BAD_SIGNATURE:");
        assertThat(rows(d)).isZero();
    }

    @Test
    void evidenceListedTwiceIsRefused() {
        Dispute d = fresh();

        CallToolResult r = approved(client, "request_merchant_evidence", evidence(d, List.of("RECEIPT", "RECEIPT")));
        CallToolResult ok = approved(client, "request_merchant_evidence",
                evidence(d, List.of("RECEIPT", "PROOF_OF_DELIVERY")));

        assertThat(text(r)).startsWith("APPROVAL_REJECTED: INVALID_ARGUMENTS:");
        assertThat(ok.isError()).as(text(ok)).isFalse();
        assertThat(rows(d)).isEqualTo(1);
    }

    @Test
    void twoApprovalsForOneDisputeAtTheSameTimeRunOneAfterTheOther() throws Exception {
        Dispute d = fresh();
        long sixtyPercent = d.claimedMinor() * 6 / 10;
        List<McpSyncClient> clients = List.of(client(), client(), client(), client());
        var start = new CountDownLatch(1);
        List<Future<CallToolResult>> calls = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (McpSyncClient c : clients) {
                String token = TestTokens.sign("issue_provisional_credit", credit(d, sixtyPercent), "reviewer-1",
                        UUID.randomUUID(), Instant.now());
                calls.add(pool.submit(() -> {
                    start.await();
                    return call(c, "issue_provisional_credit", credit(d, sixtyPercent), token);
                }));
            }
            start.countDown();
            List<CallToolResult> results = new ArrayList<>();
            for (Future<CallToolResult> f : calls) {
                results.add(f.get());
            }

            assertThat(results).filteredOn(r -> !r.isError()).as("exactly one 60% credit fits").hasSize(1);
            assertThat(results).filteredOn(CallToolResult::isError)
                    .allSatisfy(r -> assertThat(text(r)).startsWith("APPROVAL_REJECTED: LIMIT_EXCEEDED:"));
            assertThat(rows(d)).isEqualTo(1);
        } finally {
            clients.forEach(McpSyncClient::close);
        }
    }

    @Test
    void theRoleCannotWriteOutsideItsSchemaOrReadTheLedger() {
        // Spring reports SQLSTATE 42501 as "bad SQL grammar"; PostgreSQL's own message is on the root cause.
        assertThatThrownBy(() -> jdbc.sql("UPDATE intake.disputes SET claimed_amount_minor = 0").update())
                .rootCause().hasMessageContaining("permission denied for table disputes");
        assertThatThrownBy(() -> jdbc.sql("SELECT count(*) FROM ledger.cards").query(Long.class).single())
                .rootCause().hasMessageContaining("permission denied for schema ledger");
    }
}
