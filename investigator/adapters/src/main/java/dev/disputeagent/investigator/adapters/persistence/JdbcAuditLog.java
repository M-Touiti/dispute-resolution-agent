package dev.disputeagent.investigator.adapters.persistence;

import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AuditLog} on PostgreSQL ({@code investigation.audit_events}): one row per event, appended as it happens, so
 * an investigation that crashes halfway leaves its trail. The payload is the event as JSON; tool arguments are
 * already a SHA-256 in the event, never in clear. A redelivered case that runs again appends a second trail under the
 * same dispute id; rows are ordered by their sequence number.
 */
public final class JdbcAuditLog implements AuditLog {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    public JdbcAuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(AuditEvent event) {
        jdbc.sql("""
                INSERT INTO investigation.audit_events (dispute_id, at, type, payload)
                VALUES (:id, :at, :type, CAST(:payload AS jsonb))""")
                .param("id", event.caseId().value())
                .param("at", Timestamp.from(event.at()))
                .param("type", event.getClass().getSimpleName())
                .param("payload", JSON.writeValueAsString(event))
                .update();
    }
}
