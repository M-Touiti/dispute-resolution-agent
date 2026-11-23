package dev.disputeagent.investigator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.adapters.persistence.JdbcCaseStore;
import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.port.events.OutgoingEvent;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.approval.ApprovalToken;
import dev.disputeagent.investigator.domain.approval.Review;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The case store on the real schema (Flyway V1 and V2): a case through approval, guarded updates, constraints. */
class JdbcCaseStoreTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-trixie").asCompatibleSubstituteFor("postgres"));

    static JdbcClient jdbc;
    static JdbcCaseStore store;

    static final Instant T0 = Instant.parse("2026-11-23T09:00:00Z");
    static final ProposedAction CREDIT = new ProposedAction.IssueProvisionalCredit(new Money(110056, "EUR"));

    @BeforeAll
    static void start() {
        POSTGRES.start();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("investigation").createSchemas(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcCaseStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    static DisputeCase decided(String id) {
        var c = new DisputeCase(new CaseId(id), DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, T0.plusSeconds(1));
        c.investigationStarted(T0.plusSeconds(2));
        c.decided(new Decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, Confidence.HIGH, "r",
                List.of("TXN-1", "AUTH-1"), List.of("unauthorised-card-not-present"), List.of(CREDIT)),
                new GuardResult(List.of()), T0.plusSeconds(30));
        return c;
    }

    /** Saved as ProcessDisputeOpened saves it: waiting for approval, with the investigation's outcome. */
    static DisputeCase saved(String id) {
        var c = decided(id);
        CaseOutcome outcome = CaseOutcome.of(c);
        c.requestApproval(T0.plusSeconds(31));
        store.save(c, outcome, List.of(new OutgoingEvent.Decided(outcome)));
        return c;
    }

    @Test
    void aCaseThroughApprovalIsRestoredWithEverythingItHeld() {
        saved("DSP-000101");

        var waiting = store.find(new CaseId("DSP-000101")).orElseThrow();
        assertThat(waiting.disputeCase().state()).isEqualTo(CaseState.AWAITING_APPROVAL);
        assertThat(waiting.outcome().state()).isEqualTo(CaseState.DECIDED);
        assertThat(waiting.disputeCase().approvalRequest().orElseThrow().action()).isEqualTo(CREDIT);
        assertThat(waiting.approvalToken()).isEmpty();

        var c = waiting.disputeCase();
        var review = new Review("reviewer-1", "checked", T0.plusSeconds(600));
        var token = ApprovalToken.issue(UUID.randomUUID(), c.approvalRequest().orElseThrow(), "cd".repeat(32), review);
        c.approve(review, token, review.at());
        assertThat(store.update(c, CaseState.AWAITING_APPROVAL, "header.payload.signature")).isTrue();

        var approved = store.find(c.id()).orElseThrow();
        assertThat(approved.disputeCase().state()).isEqualTo(CaseState.APPROVED);
        assertThat(approved.disputeCase().token()).contains(token);
        assertThat(approved.disputeCase().review()).contains(review);
        assertThat(approved.approvalToken()).contains("header.payload.signature");

        var acting = approved.disputeCase();
        acting.acted(T0.plusSeconds(601), "credited 1100.56 EUR");
        assertThat(store.update(acting, CaseState.APPROVED, null)).isTrue();

        var acted = store.find(c.id()).orElseThrow();
        assertThat(acted.disputeCase().state()).isEqualTo(CaseState.ACTED);
        assertThat(acted.disputeCase().actionDetail()).contains("credited 1100.56 EUR");
        assertThat(acted.approvalToken()).as("kept from the approval").contains("header.payload.signature");
        assertThat(acted.disputeCase().history()).extracting(DisputeCase.StateChange::to).containsExactly(
                CaseState.RECEIVED, CaseState.TRIAGED, CaseState.INVESTIGATING, CaseState.DECIDED,
                CaseState.AWAITING_APPROVAL, CaseState.APPROVED, CaseState.ACTED);
        assertThat(acted.disputeCase().history()).isEqualTo(acting.history());
    }

    @Test
    void anUpdateFromAStaleReadChangesNothing() {
        saved("DSP-000102");
        var first = store.find(new CaseId("DSP-000102")).orElseThrow().disputeCase();
        var second = store.find(new CaseId("DSP-000102")).orElseThrow().disputeCase();

        first.reject(new Review("reviewer-1", "no", T0.plusSeconds(600)), T0.plusSeconds(600));
        assertThat(store.update(first, CaseState.AWAITING_APPROVAL, null)).isTrue();

        second.reject(new Review("reviewer-2", "no as well", T0.plusSeconds(601)), T0.plusSeconds(601));
        assertThat(store.update(second, CaseState.AWAITING_APPROVAL, null)).isFalse();

        var stored = store.find(new CaseId("DSP-000102")).orElseThrow().disputeCase();
        assertThat(stored.review().orElseThrow().reviewer()).isEqualTo("reviewer-1");
    }

    @Test
    void anEscalatedCaseIsStoredAsBefore() {
        var c = new DisputeCase(new CaseId("DSP-000103"), DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        c.escalate(EscalationReason.CASE_DATA_INVALID, T0.plusSeconds(1), "not on the card");
        store.save(c, CaseOutcome.of(c), List.of());

        var stored = store.find(c.id()).orElseThrow();
        assertThat(stored.disputeCase().state()).isEqualTo(CaseState.ESCALATED);
        assertThat(stored.disputeCase().escalationReason()).contains(EscalationReason.CASE_DATA_INVALID);
        assertThat(stored.disputeCase().approvalRequest()).isEmpty();
    }

    @Test
    void aParkedCaseIsStoredWithItsEventAndNoOutcome() {
        var c = new DisputeCase(new CaseId("DSP-000105"), DisputeCategory.UNAUTHORISED_TRANSACTION, T0);
        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, T0.plusSeconds(1));
        c.investigationStarted(T0.plusSeconds(2));
        c.park(T0.plusSeconds(3), "model call failed: OVERLOADED");
        var event = new dev.disputeagent.investigator.application.intake.DisputeOpened(c.id(), "CH-1", "CRD-1",
                "TXN-1", DisputeCategory.UNAUTHORISED_TRANSACTION, T0, Money.eur(100), "never made it", List.of());

        store.park(c, event);

        var stored = store.find(c.id()).orElseThrow();
        assertThat(stored.disputeCase().state()).isEqualTo(CaseState.AWAITING_RETRY);
        assertThat(stored.outcome()).isNull();
        assertThat(stored.disputeCase().history()).isEqualTo(c.history());
        assertThat(jdbc.sql("SELECT opened_event FROM investigation.cases WHERE dispute_id = 'DSP-000105'")
                .query(String.class).single()).contains("\"dispute_id\":\"DSP-000105\"").contains("never made it");
        assertThat(jdbc.sql("SELECT count(*) FROM investigation.outbox WHERE dispute_id = 'DSP-000105'")
                .query(Long.class).single()).as("nothing to publish yet").isZero();
    }

    @Test
    void theSchemaRefusesAParkedCaseWithoutItsEventOrWithAnOutcome() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO investigation.cases (dispute_id, state, reported_category, category, unsafe_proposal, history)
                VALUES ('DSP-000106', 'AWAITING_RETRY', 'UNAUTHORISED_TRANSACTION', 'UNAUTHORISED_TRANSACTION', false,
                    '[]'::jsonb)""").update()).isInstanceOf(DataIntegrityViolationException.class);
        saved("DSP-000107");
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE investigation.cases SET state = 'AWAITING_RETRY', opened_event = '{}'
                WHERE dispute_id = 'DSP-000107'""").update()).as("a parked case has no outcome")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSchemaRefusesAnApprovedCaseWithoutItsToken() {
        saved("DSP-000104");

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE investigation.cases SET state = 'APPROVED', reviewer = 'r', reviewed_at = now()
                WHERE dispute_id = 'DSP-000104'""").update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE investigation.cases SET state = 'PARTY' WHERE dispute_id = 'DSP-000104'""").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
