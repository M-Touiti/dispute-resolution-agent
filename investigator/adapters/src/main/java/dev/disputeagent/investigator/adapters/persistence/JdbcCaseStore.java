package dev.disputeagent.investigator.adapters.persistence;

import dev.disputeagent.investigator.adapters.kafka.DisputeEvents;
import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.events.OutgoingEvent;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.approval.ApprovalToken;
import dev.disputeagent.investigator.domain.approval.Review;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * {@link CaseStore} on PostgreSQL ({@code investigation.cases} and {@code investigation.outbox}, Flyway {@code V1} to
 * {@code V3}). A case is inserted when its investigation ends, together with its outgoing events in the outbox, in one
 * transaction (ADR 0008); it is updated after that, each update guarded by the state the caller read. The outcome is
 * kept as the exact {@code disputes.decided} payload (text, not JSONB, which would reorder keys); it also carries the
 * decision and the guard's verdict a stored case is restored with.
 */
public final class JdbcCaseStore implements CaseStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcCaseStore(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Optional<StoredCase> find(CaseId disputeId) {
        return jdbc.sql("""
                SELECT state, history, outcome_event, replays, approval_requested_at, reviewer, review_note,
                    reviewed_at, token_id, token_arguments_sha256, token_issued_at, token_expires_at, approval_token,
                    action_detail, reported_category, category
                FROM investigation.cases WHERE dispute_id = :id""")
                .param("id", disputeId.value())
                .query((rs, n) -> stored(disputeId, rs))
                .optional();
    }

    @Override
    public List<StoredCase> awaitingApproval(int limit) {
        return jdbc.sql("""
                SELECT dispute_id, state, history, outcome_event, replays, approval_requested_at, reviewer, review_note,
                    reviewed_at, token_id, token_arguments_sha256, token_issued_at, token_expires_at, approval_token,
                    action_detail, reported_category, category
                FROM investigation.cases WHERE state = 'AWAITING_APPROVAL'
                ORDER BY approval_requested_at, dispute_id LIMIT :limit""")
                .param("limit", limit)
                .query((rs, n) -> stored(new CaseId(rs.getString("dispute_id")), rs))
                .list();
    }

    @Override
    public void save(DisputeCase c, CaseOutcome outcome, List<OutgoingEvent> events) {
        transactions.executeWithoutResult(status -> {
            insert(c, outcome);
            for (OutgoingEvent event : events) {
                OutboxRow row = row(event);
                jdbc.sql("""
                        INSERT INTO investigation.outbox (dispute_id, type, payload)
                        VALUES (:id, :type, :payload)""")
                        .param("id", event.disputeId().value())
                        .param("type", row.type())
                        .param("payload", row.payload())
                        .update();
            }
        });
    }

    /** The type the relay maps to a topic, and the event's wire payload. */
    private record OutboxRow(String type, String payload) {
    }

    private static OutboxRow row(OutgoingEvent event) {
        return switch (event) {
            case OutgoingEvent.Decided decided -> new OutboxRow("DECIDED", DisputeEvents.decided(decided.outcome()));
            case OutgoingEvent.ApprovalRequested requested ->
                    new OutboxRow("APPROVAL_REQUESTED", DisputeEvents.approvalRequested(requested.request()));
        };
    }

    @Override
    public void park(DisputeCase parked, DisputeOpened event) {
        var s = parked.snapshot();
        jdbc.sql("""
                INSERT INTO investigation.cases (dispute_id, state, reported_category, category, unsafe_proposal,
                    history, replays, opened_event)
                VALUES (:id, :state, :reported, :category, false, CAST(:history AS jsonb), :replays, :event)""")
                .param("id", parked.id().value())
                .param("state", s.state().name())
                .param("reported", s.reportedCategory().name())
                .param("category", s.category().name())
                .param("history", history(s.history()))
                .param("replays", s.replays())
                .param("event", DisputeEvents.opened(event))
                .update();
    }

    private void insert(DisputeCase c, CaseOutcome outcome) {
        var s = c.snapshot();
        jdbc.sql("""
                INSERT INTO investigation.cases (dispute_id, state, reported_category, category, outcome,
                    escalation_reason, unsafe_proposal, decided_at, outcome_event, history, replays,
                    approval_requested_at)
                VALUES (:id, :state, :reported, :category, :outcome, :reason, :unsafe, :decidedAt, :event,
                    CAST(:history AS jsonb), :replays, :requestedAt)""")
                .param("id", outcome.disputeId().value())
                .param("state", s.state().name())
                .param("reported", outcome.reportedCategory().name())
                .param("category", outcome.category().name())
                .param("outcome", outcome.decision() == null ? null : outcome.decision().outcome().name())
                .param("reason", outcome.escalationReason() == null ? null : outcome.escalationReason().name())
                .param("unsafe", outcome.unsafeProposal())
                .param("decidedAt", Timestamp.from(outcome.decidedAt()))
                .param("event", DisputeEvents.decided(outcome))
                .param("history", history(s.history()))
                .param("replays", s.replays())
                .param("requestedAt", timestamp(s.approvalRequestedAt()))
                .update();
    }

    @Override
    public boolean update(DisputeCase c, CaseState expected, String approvalToken) {
        var s = c.snapshot();
        Review review = s.review();
        ApprovalToken token = s.token();
        return jdbc.sql("""
                UPDATE investigation.cases SET state = :state, history = CAST(:history AS jsonb), replays = :replays,
                    approval_requested_at = :requestedAt, reviewer = :reviewer, review_note = :note,
                    reviewed_at = :reviewedAt, token_id = :tokenId, token_arguments_sha256 = :hash,
                    token_issued_at = :issuedAt, token_expires_at = :expiresAt,
                    approval_token = COALESCE(:token, approval_token), action_detail = :detail, updated_at = now()
                WHERE dispute_id = :id AND state = :expected""")
                .param("id", c.id().value())
                .param("expected", expected.name())
                .param("state", s.state().name())
                .param("history", history(s.history()))
                .param("replays", s.replays())
                .param("requestedAt", timestamp(s.approvalRequestedAt()))
                .param("reviewer", review == null ? null : review.reviewer())
                .param("note", review == null ? null : review.note())
                .param("reviewedAt", review == null ? null : timestamp(review.at()))
                .param("tokenId", token == null ? null : token.tokenId())
                .param("hash", token == null ? null : token.argumentsSha256())
                .param("issuedAt", token == null ? null : timestamp(token.issuedAt()))
                .param("expiresAt", token == null ? null : timestamp(token.expiresAt()))
                .param("token", approvalToken)
                .param("detail", s.actionDetail())
                .update() == 1;
    }

    private static StoredCase stored(CaseId id, ResultSet rs) throws SQLException {
        CaseState state = CaseState.valueOf(rs.getString("state"));
        String outcomeEvent = rs.getString("outcome_event");
        if (outcomeEvent == null) {
            // Parked before its investigation ended: no outcome, no decision, no approval yet.
            var parked = new DisputeCase.Snapshot(id, DisputeCategory.valueOf(rs.getString("reported_category")),
                    DisputeCategory.valueOf(rs.getString("category")), state, null, null, null, rs.getInt("replays"),
                    null, null, null, null, history(rs.getString("history")));
            return new StoredCase(DisputeCase.restore(parked), null, Optional.empty());
        }
        CaseOutcome outcome = DisputeEvents.decided(outcomeEvent);
        String reviewer = rs.getString("reviewer");
        Review review = reviewer == null ? null
                : new Review(reviewer, rs.getString("review_note"), instant(rs, "reviewed_at"));
        UUID tokenId = rs.getObject("token_id", UUID.class);
        ApprovalToken token = tokenId == null || outcome.decision() == null ? null
                : new ApprovalToken(tokenId, id, outcome.decision().proposedActions().getFirst().toolName(),
                        rs.getString("token_arguments_sha256"), reviewer, instant(rs, "token_issued_at"),
                        instant(rs, "token_expires_at"));
        var snapshot = new DisputeCase.Snapshot(id, outcome.reportedCategory(), outcome.category(), state,
                outcome.decision(), outcome.decision() == null ? null : new GuardResult(outcome.guardViolations()),
                outcome.escalationReason(), rs.getInt("replays"), instant(rs, "approval_requested_at"), review, token,
                rs.getString("action_detail"), history(rs.getString("history")));
        return new StoredCase(DisputeCase.restore(snapshot), outcome,
                Optional.ofNullable(rs.getString("approval_token")));
    }

    private static String history(List<DisputeCase.StateChange> changes) {
        ArrayNode history = JSON.createArrayNode();
        changes.forEach(change -> history.addObject()
                .put("from", change.from() == null ? null : change.from().name())
                .put("to", change.to().name())
                .put("at", change.at().toString())
                .put("note", change.note()));
        return JSON.writeValueAsString(history);
    }

    private static List<DisputeCase.StateChange> history(String json) {
        List<DisputeCase.StateChange> changes = new ArrayList<>();
        for (JsonNode n : JSON.readTree(json)) {
            JsonNode from = n.get("from");
            changes.add(new DisputeCase.StateChange(from == null || from.isNull() ? null
                    : CaseState.valueOf(from.asString()), CaseState.valueOf(n.get("to").asString()),
                    Instant.parse(n.get("at").asString()), n.get("note").asString()));
        }
        return changes;
    }

    private static Timestamp timestamp(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
