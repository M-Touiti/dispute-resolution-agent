package dev.disputeagent.investigator.adapters.persistence;

import dev.disputeagent.investigator.adapters.kafka.DisputeEvents;
import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * {@link CaseStore} on PostgreSQL ({@code investigation.cases}, Flyway {@code V1}). One row per finished case, written
 * once in a single statement. The outcome is kept as the exact {@code disputes.decided} payload (text, not JSONB,
 * which would reorder keys), so a redelivery publishes the same bytes.
 */
public final class JdbcCaseStore implements CaseStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    public JdbcCaseStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CaseOutcome> findOutcome(CaseId disputeId) {
        return jdbc.sql("SELECT outcome_event FROM investigation.cases WHERE dispute_id = :id")
                .param("id", disputeId.value())
                .query(String.class)
                .optional()
                .map(DisputeEvents::decided);
    }

    @Override
    public void save(DisputeCase finished, CaseOutcome outcome) {
        ArrayNode history = JSON.createArrayNode();
        finished.history().forEach(change -> history.addObject()
                .put("from", change.from() == null ? null : change.from().name())
                .put("to", change.to().name())
                .put("at", change.at().toString())
                .put("note", change.note()));
        jdbc.sql("""
                INSERT INTO investigation.cases (dispute_id, state, reported_category, category, outcome,
                    escalation_reason, unsafe_proposal, decided_at, outcome_event, history)
                VALUES (:id, :state, :reported, :category, :outcome, :reason, :unsafe, :decidedAt, :event,
                    CAST(:history AS jsonb))""")
                .param("id", outcome.disputeId().value())
                .param("state", outcome.state().name())
                .param("reported", outcome.reportedCategory().name())
                .param("category", outcome.category().name())
                .param("outcome", outcome.decision() == null ? null : outcome.decision().outcome().name())
                .param("reason", outcome.escalationReason() == null ? null : outcome.escalationReason().name())
                .param("unsafe", outcome.unsafeProposal())
                .param("decidedAt", Timestamp.from(outcome.decidedAt()))
                .param("event", DisputeEvents.decided(outcome))
                .param("history", JSON.writeValueAsString(history))
                .update();
    }
}
