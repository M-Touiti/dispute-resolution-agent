package dev.disputeagent.investigator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.adapters.kafka.OutboxRelay;
import dev.disputeagent.investigator.adapters.persistence.JdbcCaseStore;
import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.port.events.OutgoingEvent;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The outbox on the real schema (ADR 0008): saved with the case or not at all, then relayed at least once. */
class OutboxTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-trixie").asCompatibleSubstituteFor("postgres"));
    static final Instant T0 = Instant.parse("2026-11-24T09:00:00Z");
    static final Map<String, String> TOPICS = Map.of("DECIDED", "disputes.decided",
            "APPROVAL_REQUESTED", "disputes.approval-requested");

    static JdbcClient jdbc;
    static TransactionTemplate transactions;
    static JdbcCaseStore store;
    static int next;

    record Sent(String topic, String key, String payload) {
    }

    @BeforeAll
    static void start() {
        POSTGRES.start();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("investigation").createSchemas(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new JdbcCaseStore(jdbc, transactions);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void emptyOutbox() {
        jdbc.sql("DELETE FROM investigation.outbox").update();
    }

    /** A decided case, put before a person, saved as ProcessDisputeOpened saves it: two events. */
    static CaseId saveDecided() {
        var id = new CaseId("DSP-OUT-" + (++next));
        var c = new DisputeCase(id, DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, T0.plusSeconds(1));
        c.investigationStarted(T0.plusSeconds(2));
        c.decided(new Decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, Confidence.HIGH, "r", List.of("TXN-1"),
                List.of("s"), List.of(new ProposedAction.IssueProvisionalCredit(Money.eur(100)))),
                new GuardResult(List.of()), T0.plusSeconds(3));
        var outcome = CaseOutcome.of(c);
        var request = c.requestApproval(T0.plusSeconds(4));
        store.save(c, outcome, List.of(new OutgoingEvent.Decided(outcome), new OutgoingEvent.ApprovalRequested(request)));
        return id;
    }

    static long unsent() {
        return jdbc.sql("SELECT count(*) FROM investigation.outbox WHERE sent_at IS NULL").query(Long.class).single();
    }

    static OutboxRelay relay(OutboxRelay.Sender sender, int batch) {
        return new OutboxRelay(jdbc, transactions, sender, TOPICS, batch, Duration.ofMillis(50));
    }

    @Test
    void theCaseAndItsEventsAreSavedTogetherOrNotAtAll() {
        CaseId saved = saveDecided();
        assertThat(jdbc.sql("SELECT type FROM investigation.outbox WHERE dispute_id = :id ORDER BY id")
                .param("id", saved.value()).query(String.class).list()).containsExactly("DECIDED", "APPROVAL_REQUESTED");

        // Make the outbox refuse rows: the case must not be stored either.
        jdbc.sql("""
                CREATE FUNCTION investigation.refuse() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'outbox down'; END $$""").update();
        jdbc.sql("CREATE TRIGGER refuse BEFORE INSERT ON investigation.outbox FOR EACH ROW "
                + "EXECUTE FUNCTION investigation.refuse()").update();
        try {
            int casesBefore = jdbc.sql("SELECT count(*) FROM investigation.cases").query(Integer.class).single();
            assertThatThrownBy(OutboxTest::saveDecided).hasStackTraceContaining("outbox down");
            assertThat(jdbc.sql("SELECT count(*) FROM investigation.cases").query(Integer.class).single())
                    .as("rolled back with its events").isEqualTo(casesBefore);
        } finally {
            jdbc.sql("DROP TRIGGER refuse ON investigation.outbox").update();
            jdbc.sql("DROP FUNCTION investigation.refuse()").update();
        }
    }

    @Test
    void aFailedSendIsDeliveredOnTheNextPollInOrder() {
        CaseId id = saveDecided();
        List<Sent> sent = new ArrayList<>();

        int first = relay((topic, key, payload) -> {
            throw new IllegalStateException("broker down");
        }, 100).relayOnce();
        assertThat(first).isZero();
        assertThat(unsent()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT attempts || ' ' || last_error FROM investigation.outbox ORDER BY id LIMIT 1")
                .query(String.class).single()).isEqualTo("1 IllegalStateException: broker down");

        int second = relay((topic, key, payload) -> sent.add(new Sent(topic, key, payload)), 100).relayOnce();
        assertThat(second).isEqualTo(2);
        assertThat(sent).extracting(Sent::topic).containsExactly("disputes.decided", "disputes.approval-requested");
        assertThat(sent).extracting(Sent::key).containsOnly(id.value());
        assertThat(sent.getFirst().payload()).contains("\"state\":\"DECIDED\"");
        assertThat(unsent()).isZero();
        assertThat(relay((topic, key, payload) -> sent.add(new Sent(topic, key, payload)), 100).relayOnce())
                .as("nothing left").isZero();
    }

    @Test
    void aFailureMidBatchKeepsWhatWasSentAndStopsThere() {
        saveDecided();
        saveDecided();
        AtomicInteger calls = new AtomicInteger();
        List<String> sent = new ArrayList<>();

        int n = relay((topic, key, payload) -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("broker hiccup");
            }
            sent.add(key + " " + topic);
        }, 100).relayOnce();

        assertThat(n).isEqualTo(1);
        assertThat(calls).as("the batch stopped at the failure").hasValue(2);
        assertThat(unsent()).isEqualTo(3);
        List<Long> unsentIds = jdbc.sql("SELECT id FROM investigation.outbox WHERE sent_at IS NULL ORDER BY id")
                .query(Long.class).list();
        long firstId = jdbc.sql("SELECT min(id) FROM investigation.outbox").query(Long.class).single();
        assertThat(unsentIds.getFirst()).as("nothing after the failure overtook it").isEqualTo(firstId + 1);
    }

    @Test
    void twoRelaysAtOnceSendEveryRowExactlyOnce() {
        for (int i = 0; i < 25; i++) {
            saveDecided();
        }
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        OutboxRelay.Sender slow = (topic, key, payload) -> {
            Thread.sleep(2);
            sent.add(key + " " + topic);
        };

        var a = CompletableFuture.supplyAsync(() -> drain(relay(slow, 7)));
        var b = CompletableFuture.supplyAsync(() -> drain(relay(slow, 7)));

        assertThat(a.join() + b.join()).isEqualTo(50);
        assertThat(sent).hasSize(50).doesNotHaveDuplicates();
        assertThat(unsent()).isZero();
    }

    static int drain(OutboxRelay relay) {
        int total = 0;
        int n;
        do {
            n = relay.relayOnce();
            total += n;
        } while (n > 0);
        return total;
    }
}
