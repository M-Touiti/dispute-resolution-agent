package dev.disputeagent.investigator.application.approval;

import dev.disputeagent.investigator.application.port.approval.ActionPort;
import dev.disputeagent.investigator.application.port.approval.TokenSigner;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.approval.ApprovalToken;
import dev.disputeagent.investigator.domain.approval.Review;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A person approves the proposed action, and the investigator carries it out (ADR 0005).
 *
 * <ol>
 *   <li>The token's claims bind the case, the action, the hash of the exact arguments the action port will send,
 *       and the reviewer; the signer turns them into the compact JWS.</li>
 *   <li>The case moves to {@code APPROVED} and is stored with the token <em>before</em> the call, guarded by the
 *       state read, so a second reviewer gets a conflict and a crash leaves the token to resume with.</li>
 *   <li>The action runs; the case ends {@code ACTED} or {@code ACTION_FAILED} with the server's answer. An unreachable
 *       server also ends it {@code ACTION_FAILED}: whether the action ran is then unknown, and a person checks.</li>
 * </ol>
 *
 * Approving a case already {@code APPROVED} (the call was interrupted) resumes it: the stored token is sent again,
 * which the server answers from its record if the action already ran. An expired token ends the case
 * {@code ACTION_FAILED} without a call.
 */
public final class ApproveAction {

    private final CaseStore cases;
    private final TokenSigner signer;
    private final ActionPort actions;
    private final AuditLog audit;
    private final Clock clock;
    private final Supplier<UUID> tokenIds;

    public ApproveAction(CaseStore cases, TokenSigner signer, ActionPort actions, AuditLog audit, Clock clock,
            Supplier<UUID> tokenIds) {
        this.cases = cases;
        this.signer = signer;
        this.actions = actions;
        this.audit = audit;
        this.clock = clock;
        this.tokenIds = tokenIds;
    }

    public ReviewResult approve(CaseId disputeId, String reviewer, String note) {
        var stored = cases.find(disputeId).orElseThrow(() -> new ReviewException.NotFound(disputeId));
        DisputeCase c = stored.disputeCase();
        if (c.state() == CaseState.APPROVED) {
            return act(c, stored.approvalToken().orElseThrow(
                    () -> new IllegalStateException("case " + disputeId + " is APPROVED without its token")));
        }
        if (c.state() != CaseState.AWAITING_APPROVAL) {
            throw new ReviewException.Conflict(disputeId, c.state());
        }

        var request = c.approvalRequest().orElseThrow();
        var review = new Review(reviewer, note, clock.instant());
        var claims = ApprovalToken.issue(tokenIds.get(), request,
                actions.argumentsSha256(disputeId, request.action()), review);
        String token = signer.sign(claims);

        int mark = Transitions.mark(c);
        c.approve(review, claims, review.at());
        if (!cases.update(c, CaseState.AWAITING_APPROVAL, token)) {
            throw new ReviewException.Conflict(disputeId, cases.find(disputeId).orElseThrow().disputeCase().state());
        }
        audit.record(new AuditEvent.Reviewed(disputeId, review.at(), true, reviewer, note));
        audit.record(new AuditEvent.TokenIssued(disputeId, claims.issuedAt(), claims.tokenId(), claims.action(),
                claims.argumentsSha256(), claims.expiresAt()));
        Transitions.since(c, mark).forEach(audit::record);
        return act(c, token);
    }

    private ReviewResult act(DisputeCase c, String token) {
        ApprovalToken claims = c.token().orElseThrow();
        var action = c.approvalRequest().orElseThrow().action();
        int mark = Transitions.mark(c);
        Instant started = clock.instant();

        ActionPort.Result result;
        if (claims.expiredAt(started)) {
            result = new ActionPort.Result(false, "the approval token expired at " + claims.expiresAt()
                    + " before the action ran; a new approval is needed");
        } else {
            try {
                result = actions.execute(c.id(), action, token);
            } catch (ActionPort.Unavailable e) {
                result = new ActionPort.Result(false, "actions-mcp unavailable, outcome unknown: " + e.getMessage());
            }
            audit.record(new AuditEvent.ActionCalled(c.id(), clock.instant(), claims.action(), claims.tokenId(),
                    !result.ok(), result.detail(), Duration.between(started, clock.instant())));
        }

        Instant at = clock.instant();
        if (result.ok()) {
            c.acted(at, result.detail());
        } else {
            c.actionFailed(at, result.detail());
        }
        if (!cases.update(c, CaseState.APPROVED, null)) {
            throw new ReviewException.Conflict(c.id(), cases.find(c.id()).orElseThrow().disputeCase().state());
        }
        Transitions.since(c, mark).forEach(audit::record);
        return new ReviewResult(c.id(), c.state(), result.detail());
    }
}
