package dev.disputeagent.investigator.application.port.audit;

/** Append-only audit trail. Called from the loop's thread only, in order. */
public interface AuditLog {

    void record(AuditEvent event);
}
