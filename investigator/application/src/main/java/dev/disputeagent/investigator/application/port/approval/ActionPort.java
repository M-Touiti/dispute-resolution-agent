package dev.disputeagent.investigator.application.port.approval;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.decision.ProposedAction;

/**
 * Carries an approved action out on actions-mcp. The adapter builds the tool's arguments from the action, so the hash
 * it reports and the arguments it sends are the same object (ADR 0005). The model never reaches this port.
 */
public interface ActionPort {

    /** The server's answer: {@code ok} when the action was carried out (or already had been, for this token). */
    record Result(boolean ok, String detail) {
    }

    /** actions-mcp could not be reached or did not answer; whether the action ran is unknown. */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Lower-case hex SHA-256 of the tool's arguments without {@code approval_token}, canonicalised as JCS. */
    String argumentsSha256(CaseId disputeId, ProposedAction action);

    /**
     * Calls the action's tool with the token. Sending the same token again is safe: the server returns the stored
     * result of the first call (the token id is the idempotency key).
     *
     * @throws Unavailable when the server cannot be reached
     */
    Result execute(CaseId disputeId, ProposedAction action, String approvalToken);
}
