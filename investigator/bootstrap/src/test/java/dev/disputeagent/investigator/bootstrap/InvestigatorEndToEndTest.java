package dev.disputeagent.investigator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.disputeagent.investigator.adapters.approval.McpActionPort;
import dev.disputeagent.investigator.adapters.kafka.DisputeEvents;
import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.approval.ActionPort;
import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.LlmCost;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.Usage;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpTool;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.application.triage.TriageDispute;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The service end to end on real Kafka and PostgreSQL (the compose images and init scripts): an event in, triage, the
 * investigation, the guard, the case and its audit trail stored, the decision event out. The model is scripted and
 * the MCP servers are canned, both through the bootstrap's switches ({@code investigator.llm.live=test},
 * {@code investigator.mcp.client=none}); everything else is the production wiring.
 *
 * <ol>
 *   <li>A recording instance decides a dispute and writes fixtures; a malformed event before it is skipped. The
 *       events leave through the outbox (ADR 0008); the same event delivered again is neither investigated nor
 *       emitted again.</li>
 *   <li>Its reviewer API approves the case over HTTP: the token is signed with the configured Ed25519 key and the
 *       action port (a test double that verifies the signature) carries the action out once; a second approval is a
 *       conflict, and a redelivery after the review asks no one again.</li>
 *   <li>A replay instance, with its own database and consumer group, reads the same events and reaches the same
 *       decision without a single live call.</li>
 * </ol>
 */
class InvestigatorEndToEndTest {

    static final String DECISION = """
            {"outcome":"APPROVE_PROVISIONAL_CREDIT","confidence":"HIGH",
             "rationale":"Card not present, 3-D Secure not performed, fraud flag set; within the time limit.",
             "cited_evidence_ids":["TXN-000100","AUTH-000100"],"cited_policy_sections":["unauthorised-card-not-present"],
             "proposed_actions":[{"action":"issue_provisional_credit","amount_minor":42500,"currency":"EUR"}]}""";

    static final DisputeOpened DISPUTE = new DisputeOpened(new CaseId("DSP-E2E-0001"), "CH-000042", "CRD-000042",
            "TXN-000100", DisputeCategory.UNAUTHORISED_TRANSACTION, Instant.parse("2026-10-30T10:00:00Z"),
            Money.eur(42_500), "I never made this payment.", List.of());

    /** A second dispute, whose investigation meets an overloaded model until the test says otherwise. */
    static final DisputeOpened PARKED = new DisputeOpened(new CaseId("DSP-E2E-0002"), "CH-000042", "CRD-000042",
            "TXN-000100", DisputeCategory.UNAUTHORISED_TRANSACTION, Instant.parse("2026-10-30T11:00:00Z"),
            Money.eur(42_500), "I did not make this payment either.", List.of());
    static final java.util.concurrent.atomic.AtomicBoolean OVERLOADED = new java.util.concurrent.atomic.AtomicBoolean();

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-trixie").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("disputes").withUsername("disputes").withPassword("disputes")
            .withCopyFileToContainer(MountableFile.forHostPath(initdb("00-extensions.sql"), 0755),
                    "/docker-entrypoint-initdb.d/00-extensions.sql")
            .withCopyFileToContainer(MountableFile.forHostPath(initdb("40-investigator.sh"), 0755),
                    "/docker-entrypoint-initdb.d/40-investigator.sh");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");

    static final AtomicInteger LIVE_CALLS = new AtomicInteger();

    static final String REVIEWER_KEY = "test-reviewer-key";
    static final KeyPair APPROVAL_KEYS = approvalKeys();
    static final VerifyingActions ACTIONS = new VerifyingActions();

    static KeyPair approvalKeys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stands in for actions-mcp: accepts a call only if the token's signature and argument hash check out. */
    static final class VerifyingActions implements ActionPort {
        final List<String> tokens = new CopyOnWriteArrayList<>();

        @Override
        public String argumentsSha256(CaseId disputeId, ProposedAction action) {
            return new McpActionPort(null).argumentsSha256(disputeId, action);
        }

        @Override
        public Result execute(CaseId disputeId, ProposedAction action, String approvalToken) {
            String[] parts = approvalToken.split("\\.");
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(APPROVAL_KEYS.getPublic());
                verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                    return new Result(false, "APPROVAL_REJECTED: BAD_SIGNATURE");
                }
            } catch (Exception e) {
                return new Result(false, "APPROVAL_REJECTED: MALFORMED");
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            if (!payload.contains("\"args\":\"" + argumentsSha256(disputeId, action) + "\"")) {
                return new Result(false, "APPROVAL_REJECTED: ARGUMENTS_MISMATCH");
            }
            tokens.add(approvalToken);
            return new Result(true, "{\"status\":\"DONE\",\"dispute_id\":\"" + disputeId + "\"}");
        }
    }

    static HttpResponse<String> http(String method, String url, String key, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("X-Reviewer-Key", key);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @TempDir
    static Path fixtures;

    static Path initdb(String script) {
        return Path.of("../../deploy/compose/postgres/initdb").resolve(script);
    }

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        KAFKA.start();
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "disputes", "disputes")) {
            admin.createStatement().execute("CREATE DATABASE replay OWNER investigator");
        }
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl().replace("/disputes", "/replay"), "disputes", "disputes")) {
            admin.createStatement().execute("CREATE SCHEMA investigation AUTHORIZATION investigator");
        }
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
        POSTGRES.stop();
    }

    // The test doubles: registered as an extra source of the application, not component-scanned.

    /** Answers triage, then one turn of tool calls, then the decision; counts every call. */
    static final class ScriptedModel {
        @Bean
        LiveLlm liveLlm() {
            return request -> {
                LIVE_CALLS.incrementAndGet();
                if (request.outputFormat().schemaJson().equals(TriageDispute.schema())) {
                    return response(StopReason.END_TURN, new ContentBlock.Text(
                            "{\"category\":\"UNAUTHORISED_TRANSACTION\",\"reason\":\"never made it\"}"));
                }
                if (OVERLOADED.get() && request.messages().getFirst().toString().contains(PARKED.disputeId().value())) {
                    throw new dev.disputeagent.investigator.application.port.llm.LlmCallException(
                            dev.disputeagent.investigator.application.port.llm.LlmCallException.Kind.OVERLOADED,
                            "529 overloaded (scripted)", null);
                }
                if (request.messages().size() == 1) {
                    return response(StopReason.TOOL_USE,
                            new ContentBlock.ToolUse("t1", "search_policy", "{\"query\":\"card not present\"}"),
                            new ContentBlock.ToolUse("t2", "get_authorisations", "{\"card_id\":\"CRD-000042\"}"));
                }
                return response(StopReason.END_TURN, new ContentBlock.Text(DECISION));
            };
        }

        @Bean
        McpToolPort mcpToolPort() {
            return new CannedServers();
        }

        @Bean
        ActionPort actionPort() {
            return ACTIONS;
        }

        private static LlmResponse response(StopReason stop, ContentBlock... content) {
            return new LlmResponse("msg_" + LIVE_CALLS.get(), "claude-opus-5-5", stop, List.of(content),
                    new Usage(900, 120, 0, 0), null, new LlmCost(0, "test"));
        }
    }

    static final class CannedServers implements McpToolPort {
        @Override
        public List<McpTool> listTools(McpServer server) {
            List<String> names = switch (server) {
                case LEDGER -> List.of("get_transaction", "search_transactions", "get_authorisations");
                case POLICY -> List.of("search_policy", "get_policy_section");
                case ACTIONS -> throw new IllegalStateException("the actions server is never reached");
            };
            List<McpTool> tools = new ArrayList<>();
            names.forEach(n -> tools.add(new McpTool(server, n, n, "{\"type\":\"object\"}")));
            return tools;
        }

        @Override
        public McpToolResult call(McpToolCall call) {
            return new McpToolResult(switch (call.toolName()) {
                case "get_transaction" -> "{\"transaction_id\":\"TXN-000100\",\"card_id\":\"CRD-000042\","
                        + "\"auth_id\":\"AUTH-000100\",\"posted_on\":\"2026-10-01\"}";
                case "search_transactions" -> "{\"items\":[{\"transaction_id\":\"TXN-000100\","
                        + "\"auth_id\":\"AUTH-000100\"}],\"next_page_token\":null}";
                case "get_authorisations" -> "{\"items\":[{\"auth_id\":\"AUTH-000100\",\"entry_mode\":\"ECOMMERCE\","
                        + "\"three_ds_result\":\"NOT_PERFORMED\",\"fraud_flag\":true}],\"next_page_token\":null}";
                case "search_policy" -> "{\"results\":[{\"section_id\":\"unauthorised-card-not-present\"}]}";
                default -> "{}";
            }, false);
        }
    }

    /** Settings as command-line arguments: they take precedence over application.yml, as an operator's would. */
    static ConfigurableApplicationContext instance(String adapter, String database, String group) {
        Map<String, String> settings = Map.ofEntries(
                Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl().replace("/disputes", "/" + database)),
                Map.entry("spring.datasource.username", "investigator"),
                Map.entry("spring.datasource.password", "investigator"),
                Map.entry("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers()),
                Map.entry("server.port", "0"),
                Map.entry("investigator.llm.adapter", adapter),
                Map.entry("investigator.llm.live", "test"),
                Map.entry("investigator.mcp.client", "none"),
                Map.entry("investigator.llm.fixtures-dir", fixtures.toString()),
                Map.entry("investigator.kafka.group-id", group),
                Map.entry("investigator.approval.signing-key",
                        Base64.getEncoder().encodeToString(APPROVAL_KEYS.getPrivate().getEncoded())),
                Map.entry("investigator.approval.verify-key",
                        Base64.getEncoder().encodeToString(APPROVAL_KEYS.getPublic().getEncoded())),
                Map.entry("investigator.reviewer.api-key", REVIEWER_KEY),
                // Fast retries and replays, so the test does not wait minutes for what production spaces out.
                Map.entry("investigator.resilience.first-backoff", "PT0.05S"),
                Map.entry("investigator.resilience.max-backoff", "PT0.1S"),
                Map.entry("investigator.replay.first-delay", "PT1S"),
                Map.entry("investigator.replay.sweep-interval", "PT0.5S"),
                Map.entry("investigator.resilience.breaker-open-for", "PT2S"),
                Map.entry("investigator.skills-dir", "../../skills"));
        String[] args = settings.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(InvestigatorApplication.class, ScriptedModel.class).run(args);
    }

    static void send(String key, String value) throws Exception {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (var producer = new KafkaProducer<String, String>(p)) {
            producer.send(new ProducerRecord<>("disputes.opened", key, value)).get();
        }
    }

    /** Every disputes.decided record for the dispute, from the start of the topic. */
    static List<String> decided(String disputeId) {
        return records("disputes.decided", disputeId);
    }

    /** Every record of the topic for the dispute, from the start. */
    static List<String> records(String topic, String disputeId) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<String> out = new CopyOnWriteArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(p)) {
            consumer.subscribe(List.of(topic));
            for (int i = 0; i < 5; i++) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (disputeId.equals(r.key())) {
                        out.add(r.value());
                    }
                }
            }
        }
        return out;
    }

    static String state(JdbcClient jdbc, String disputeId) {
        return jdbc.sql("SELECT state FROM investigation.cases WHERE dispute_id = :id").param("id", disputeId)
                .query(String.class).optional().orElse(null);
    }

    /** The reason header of the dead letter with this key, from the start of disputes.dlq. */
    static String dlqReason(String key) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (var consumer = new KafkaConsumer<String, String>(p)) {
            consumer.subscribe(List.of("disputes.dlq"));
            for (int i = 0; i < 10; i++) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    var header = r.headers().lastHeader("kafka_dlt-exception-message");
                    if (key.equals(r.key()) && header != null) {
                        return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        }
        return null;
    }

    static long count(ConfigurableApplicationContext ctx, String sql) {
        return ctx.getBean(JdbcClient.class).sql(sql).query(Long.class).single();
    }

    @Test
    void recordThenReplayADisputeThroughKafkaAndPostgres() throws Exception {
        String id = DISPUTE.disputeId().value();

        // 1. Recording instance: a malformed event, then the dispute.
        try (var recording = instance("recording", "disputes", "recording")) {
            send("garbage", "{\"this is\": \"not a dispute\"}");
            send(id, DisputeEvents.opened(DISPUTE));

            await().atMost(Duration.ofSeconds(60)).until(() -> decided(id).size() == 1);
            CaseOutcome first = DisputeEvents.decided(decided(id).getFirst());
            assertThat(first.state()).isEqualTo(CaseState.DECIDED);
            assertThat(first.decision().outcome()).isEqualTo(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT);
            assertThat(LIVE_CALLS.get()).isEqualTo(3); // triage, tool turn, decision
            assertThat(count(recording, "SELECT count(*) FROM investigation.cases")).isEqualTo(1);
            assertThat(count(recording, "SELECT count(*) FROM investigation.audit_events WHERE type = 'ModelCalled'"))
                    .isEqualTo(3);

            // The decision proposes a credit: the case waits for a person (ADR 0005).
            assertThat(recording.getBean(JdbcClient.class)
                    .sql("SELECT state FROM investigation.cases WHERE dispute_id = :id").param("id", id)
                    .query(String.class).single()).isEqualTo("AWAITING_APPROVAL");
            await().atMost(Duration.ofSeconds(30)).until(() -> records("disputes.approval-requested", id).size() == 1);
            assertThat(records("disputes.approval-requested", id).getFirst())
                    .contains("\"dispute_id\":\"" + id + "\"")
                    .contains("\"action\":{\"action\":\"issue_provisional_credit\",\"amount_minor\":42500");
            var stored = recording.getBean(CaseStore.class).find(DISPUTE.disputeId()).orElseThrow();
            assertThat(stored.disputeCase().state()).isEqualTo(CaseState.AWAITING_APPROVAL);
            assertThat(stored.disputeCase().approvalRequest()).isPresent();
            assertThat(stored.outcome()).isEqualTo(first);
            try (Stream<Path> files = Files.list(fixtures)) {
                assertThat(files.filter(f -> f.toString().endsWith(".json")).count()).isEqualTo(3);
            }

            assertThat(count(recording, "SELECT count(*) FROM investigation.outbox WHERE sent_at IS NULL"))
                    .as("the relay sent both outbox rows").isZero();
            assertThat(count(recording, "SELECT count(*) FROM investigation.outbox")).isEqualTo(2);

            // The same event again: not investigated again, and nothing emitted again (the outbox already did).
            send(id, DisputeEvents.opened(DISPUTE));
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30))
                    .until(() -> decided(id).size() == 1 && records("disputes.approval-requested", id).size() == 1);
            assertThat(LIVE_CALLS.get()).isEqualTo(3);
            assertThat(count(recording, "SELECT count(*) FROM investigation.outbox")).isEqualTo(2);
            assertThat(count(recording, "SELECT count(*) FROM investigation.cases")).isEqualTo(1);

            // 2. The reviewer API (ADR 0005): the queue, then an approval carried out once.
            String base = "http://localhost:" + recording.getEnvironment().getProperty("local.server.port") + "/reviews";
            assertThat(http("GET", base, null, null).statusCode()).as("no key").isEqualTo(401);
            assertThat(http("GET", base, "wrong", null).statusCode()).as("wrong key").isEqualTo(401);
            var queue = http("GET", base, REVIEWER_KEY, null);
            assertThat(queue.statusCode()).isEqualTo(200);
            assertThat(queue.body()).contains("\"dispute_id\":\"" + id + "\"").contains("issue_provisional_credit")
                    .contains("\"outcome\":\"APPROVE_PROVISIONAL_CREDIT\"");
            assertThat(http("POST", base + "/" + id + "/approve", REVIEWER_KEY, "{\"note\":\"x\"}").statusCode())
                    .as("no reviewer").isEqualTo(400);

            var approved = http("POST", base + "/" + id + "/approve", REVIEWER_KEY,
                    "{\"reviewer\":\"reviewer-1\",\"note\":\"evidence checked\"}");
            assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
            assertThat(approved.body()).contains("\"state\":\"ACTED\"");
            assertThat(ACTIONS.tokens).as("one call, with a token that verifies").hasSize(1);
            assertThat(recording.getBean(JdbcClient.class)
                    .sql("SELECT state FROM investigation.cases WHERE dispute_id = :id").param("id", id)
                    .query(String.class).single()).isEqualTo("ACTED");
            assertThat(count(recording, "SELECT count(*) FROM investigation.audit_events WHERE type = 'TokenIssued'"))
                    .isEqualTo(1);
            assertThat(count(recording, "SELECT count(*) FROM investigation.audit_events WHERE type = 'ActionCalled'"))
                    .isEqualTo(1);
            assertThat(count(recording, "SELECT count(*) FROM investigation.audit_events WHERE type = 'Reviewed'"))
                    .isEqualTo(1);

            var again = http("POST", base + "/" + id + "/approve", REVIEWER_KEY, "{\"reviewer\":\"reviewer-2\"}");
            assertThat(again.statusCode()).isEqualTo(409);
            assertThat(again.body()).contains("\"state\":\"ACTED\"");
            assertThat(http("POST", base + "/DSP-NONE/reject", REVIEWER_KEY, "{\"reviewer\":\"r\"}").statusCode())
                    .isEqualTo(404);
            assertThat(ACTIONS.tokens).hasSize(1);

            // A redelivery after the review: nothing emitted, no one asked again.
            send(id, DisputeEvents.opened(DISPUTE));
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30))
                    .until(() -> decided(id).size() == 1 && records("disputes.approval-requested", id).size() == 1);

            // 3. Dead letters and replay (ADR 0008). The malformed event sent first is in disputes.dlq with its reason;
            // the partition moved on (the dispute after it was decided above).
            assertThat(dlqReason("garbage")).contains("not a valid disputes.opened event");

            // A dispute the model cannot serve: every attempt is overloaded, so the case parks with its event.
            String parked = PARKED.disputeId().value();
            OVERLOADED.set(true);
            send(parked, DisputeEvents.opened(PARKED));
            JdbcClient jdbc = recording.getBean(JdbcClient.class);
            await().atMost(Duration.ofSeconds(30)).until(() -> "AWAITING_RETRY".equals(state(jdbc, parked)));
            assertThat(decided(parked)).as("nothing emitted while parked").isEmpty();

            // The model recovers: the sweep sends it to disputes.replay, and the replay decides it.
            OVERLOADED.set(false);
            await().atMost(Duration.ofSeconds(60)).until(() -> "AWAITING_APPROVAL".equals(state(jdbc, parked)));
            assertThat(decided(parked)).hasSize(1);
            // The sweep may replay it once more while the model is still overloaded (timing), and that replay parks
            // again; whatever the count, each replay event is one replay run, and every run but the last parked.
            int replaysSent = records("disputes.replay", parked).size();
            assertThat(replaysSent).isPositive();
            assertThat(jdbc.sql("SELECT replays FROM investigation.cases WHERE dispute_id = :id").param("id", parked)
                    .query(Integer.class).single()).isEqualTo(replaysSent);
            assertThat(count(recording, "SELECT count(*) FROM investigation.audit_events WHERE type = 'CaseReplayed'"))
                    .isEqualTo(replaysSent);
            assertThat(count(recording, "SELECT count(*) FROM investigation.audit_events WHERE type = 'CaseParked'"))
                    .isEqualTo(replaysSent);
        }
        int liveAfterRecording = LIVE_CALLS.get();

        // 2. Replay instance: own database, own group, same topic from the start.
        try (var replay = instance("replay", "replay", "replay")) {
            // Three disputes.opened for one dispute: one investigation, one event of each kind.
            await().atMost(Duration.ofSeconds(60)).until(() -> decided(id).size() == 2);
            await().atMost(Duration.ofSeconds(30)).until(() -> records("disputes.approval-requested", id).size() == 2);
            CaseOutcome replayed = DisputeEvents.decided(decided(id).get(1));
            CaseOutcome recorded = DisputeEvents.decided(decided(id).getFirst());

            assertThat(replayed.state()).isEqualTo(recorded.state());
            assertThat(replayed.decision()).isEqualTo(recorded.decision());
            assertThat(LIVE_CALLS.get()).as("replay never calls the live model").isEqualTo(liveAfterRecording);
            // The second dispute decides straight from the fixtures its successful replay recorded; the old replay
            // event finds it not parked and is ignored.
            await().atMost(Duration.ofSeconds(60)).until(() -> decided(PARKED.disputeId().value()).size() == 2);
            assertThat(count(replay, "SELECT count(*) FROM investigation.cases")).isEqualTo(2);
        }
    }
}
