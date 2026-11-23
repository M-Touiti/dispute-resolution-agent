package dev.disputeagent.investigator.domain.investigation;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.approval.ApprovalRequest;
import dev.disputeagent.investigator.domain.approval.ApprovalToken;
import dev.disputeagent.investigator.domain.approval.Review;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One dispute through investigation, approval and action. Only legal transitions are possible (see {@link CaseState}),
 * each is recorded with its time and reason, and the decision is set at most once per run. Time is passed in; the
 * domain has no clock.
 */
public final class DisputeCase {

    /** One transition, for the audit log. */
    public record StateChange(CaseState from, CaseState to, Instant at, String note) {
    }

    private final CaseId id;
    private final DisputeCategory reportedCategory;
    private final List<StateChange> history = new ArrayList<>();
    private CaseState state = CaseState.RECEIVED;
    private DisputeCategory category;
    private Decision decision;
    private GuardResult guardResult;
    private EscalationReason escalationReason;
    private int replays;
    private ApprovalRequest approvalRequest;
    private Review review;
    private ApprovalToken token;
    private String actionDetail;

    /**
     * Everything a stored case holds, to restore it ({@link #restore}). Optional parts are null when the case has not
     * reached them.
     */
    public record Snapshot(CaseId id, DisputeCategory reportedCategory, DisputeCategory category, CaseState state,
            Decision decision, GuardResult guardResult, EscalationReason escalationReason, int replays,
            Instant approvalRequestedAt, Review review, ApprovalToken token, String actionDetail,
            List<StateChange> history) {

        public Snapshot {
            history = List.copyOf(history);
        }
    }

    /**
     * A stored case, as it was. The snapshot must be consistent: the state is the last transition of the history, a
     * case past its decision has the decision with its one action, and an approved case has its review and token.
     */
    public static DisputeCase restore(Snapshot s) {
        Objects.requireNonNull(s, "snapshot");
        if (s.history().isEmpty() || s.history().getLast().to() != s.state()) {
            throw new IllegalArgumentException("case " + s.id() + ": the state " + s.state()
                    + " is not the last transition of its history");
        }
        var c = new DisputeCase(s.id(), s.reportedCategory(), s.history().getFirst().at());
        c.history.clear();
        c.history.addAll(s.history());
        c.state = s.state();
        c.category = Objects.requireNonNull(s.category(), "category");
        c.decision = s.decision();
        c.guardResult = s.guardResult();
        c.escalationReason = s.escalationReason();
        c.replays = s.replays();
        c.review = s.review();
        c.token = s.token();
        c.actionDetail = s.actionDetail();
        boolean pastDecision = EnumSet.of(CaseState.AWAITING_APPROVAL, CaseState.APPROVED, CaseState.REJECTED,
                CaseState.ACTED, CaseState.ACTION_FAILED).contains(s.state());
        if (pastDecision) {
            if (s.decision() == null || s.decision().proposedActions().size() != 1 || s.approvalRequestedAt() == null) {
                throw new IllegalArgumentException("case " + s.id() + " is " + s.state()
                        + " without a decision, its action or the time approval was requested");
            }
            c.approvalRequest = new ApprovalRequest(s.id(), s.decision().proposedActions().getFirst(),
                    s.approvalRequestedAt());
        }
        if (EnumSet.of(CaseState.APPROVED, CaseState.ACTED, CaseState.ACTION_FAILED).contains(s.state())
                && (s.review() == null || s.token() == null)) {
            throw new IllegalArgumentException("case " + s.id() + " is " + s.state() + " without its review and token");
        }
        if ((s.state() == CaseState.ESCALATED) != (s.escalationReason() != null)) {
            throw new IllegalArgumentException("case " + s.id() + ": an escalated case, and only one, has a reason");
        }
        return c;
    }

    public DisputeCase(CaseId id, DisputeCategory reportedCategory, Instant receivedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.reportedCategory = Objects.requireNonNull(reportedCategory, "reportedCategory");
        this.category = reportedCategory;
        history.add(new StateChange(null, CaseState.RECEIVED, Objects.requireNonNull(receivedAt), "received"));
    }

    /** Triage settled the category; it may differ from the one on the event (both are kept). */
    public void triaged(DisputeCategory triagedCategory, Instant at) {
        Objects.requireNonNull(triagedCategory, "triagedCategory");
        String note = triagedCategory == reportedCategory
                ? "category " + triagedCategory
                : "triage says " + triagedCategory + ", event says " + reportedCategory;
        move(CaseState.TRIAGED, at, note);
        this.category = triagedCategory;
    }

    public void investigationStarted(Instant at) {
        move(CaseState.INVESTIGATING, at, "investigation started");
    }

    /**
     * Records the agent's decision and the guard's verdict. A guard failure escalates as {@link
     * EscalationReason#GUARD_FAILED}; an {@code ESCALATE} outcome escalates as {@link
     * EscalationReason#AGENT_DECISION}; any other passing decision ends the investigation as {@code DECIDED}.
     */
    public void decided(Decision decision, GuardResult guardResult, Instant at) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(guardResult, "guardResult");
        requireState(CaseState.INVESTIGATING);
        this.decision = decision;
        this.guardResult = guardResult;
        if (!guardResult.passed()) {
            escalate(EscalationReason.GUARD_FAILED, at, guardResult.violations().size() + " guard violations");
        } else if (decision.outcome() == DecisionOutcome.ESCALATE) {
            escalate(EscalationReason.AGENT_DECISION, at, "the agent escalated");
        } else {
            move(CaseState.DECIDED, at, decision.outcome().name());
        }
    }

    /** Ends the case with a person: from any state of the investigation, or a parked case (replay cap). */
    public void escalate(EscalationReason reason, Instant at, String detail) {
        Objects.requireNonNull(reason, "reason");
        move(CaseState.ESCALATED, at, reason + ": " + detail);
        this.escalationReason = reason;
    }

    /**
     * Parks the case: the model or the ledger was unavailable after the retries (ADR 0008). Nothing the run produced
     * is kept; a replay starts again from {@code RECEIVED}.
     */
    public void park(Instant at, String reason) {
        Objects.requireNonNull(reason, "reason");
        move(CaseState.AWAITING_RETRY, at, "parked: " + reason);
    }

    /** Starts a parked case again from the beginning; the history and the replay count carry over. */
    public void replay(Instant at) {
        requireState(CaseState.AWAITING_RETRY);
        replays++;
        move(CaseState.RECEIVED, at, "replay " + replays);
        category = reportedCategory;
        decision = null;
        guardResult = null;
    }

    /**
     * Puts the decision's action before a person. Every passing decision that is not an escalation carries exactly
     * one action (the guard's rule), so every decided case goes through approval.
     */
    public ApprovalRequest requestApproval(Instant at) {
        requireState(CaseState.DECIDED);
        var actions = decision.proposedActions();
        if (actions.size() != 1) {
            throw new IllegalStateException("case " + id + " has " + actions.size() + " actions, expected one");
        }
        move(CaseState.AWAITING_APPROVAL, at, "approval requested: " + actions.getFirst().toolName());
        approvalRequest = new ApprovalRequest(id, actions.getFirst(), at);
        return approvalRequest;
    }

    /**
     * A person approved; the token is what {@code actions-mcp} will verify. It must be issued for this case and this
     * action, by the reviewer who approved, and still be valid now.
     */
    public void approve(Review review, ApprovalToken token, Instant at) {
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(token, "token");
        requireState(CaseState.AWAITING_APPROVAL);
        if (!token.bindsTo(id, approvalRequest.action())) {
            throw new IllegalArgumentException("token " + token.tokenId() + " is for " + token.caseId() + " "
                    + token.action() + ", not " + id + " " + approvalRequest.action().toolName());
        }
        if (!token.reviewer().equals(review.reviewer())) {
            throw new IllegalArgumentException("token " + token.tokenId() + " names another reviewer");
        }
        if (token.expiredAt(at)) {
            throw new IllegalArgumentException("token " + token.tokenId() + " expired at " + token.expiresAt());
        }
        move(CaseState.APPROVED, at, "approved by " + review.reviewer());
        this.review = review;
        this.token = token;
    }

    /** A person rejected the action: nothing is carried out. */
    public void reject(Review review, Instant at) {
        Objects.requireNonNull(review, "review");
        requireState(CaseState.AWAITING_APPROVAL);
        move(CaseState.REJECTED, at, "rejected by " + review.reviewer());
        this.review = review;
    }

    /** {@code actions-mcp} carried the approved action out. */
    public void acted(Instant at, String detail) {
        Objects.requireNonNull(detail, "detail");
        requireState(CaseState.APPROVED);
        move(CaseState.ACTED, at, token.action() + " done");
        this.actionDetail = detail;
    }

    /** {@code actions-mcp} refused or failed the approved action; a person takes over. */
    public void actionFailed(Instant at, String reason) {
        Objects.requireNonNull(reason, "reason");
        requireState(CaseState.APPROVED);
        move(CaseState.ACTION_FAILED, at, token.action() + " failed: " + reason);
        this.actionDetail = reason;
    }

    private void move(CaseState to, Instant at, String note) {
        Objects.requireNonNull(at, "at");
        if (!state.next().contains(to)) {
            throw new IllegalStateException("case " + id + ": " + state + " -> " + to + " is not allowed");
        }
        history.add(new StateChange(state, to, at, note));
        state = to;
    }

    private void requireState(CaseState expected) {
        if (state != expected) {
            throw new IllegalStateException("case " + id + " is " + state + ", expected " + expected);
        }
    }

    public CaseId id() {
        return id;
    }

    public CaseState state() {
        return state;
    }

    public DisputeCategory reportedCategory() {
        return reportedCategory;
    }

    /** The category the investigation uses: the triaged one once triage ran, the reported one before. */
    public DisputeCategory category() {
        return category;
    }

    public Optional<Decision> decision() {
        return Optional.ofNullable(decision);
    }

    public Optional<GuardResult> guardResult() {
        return Optional.ofNullable(guardResult);
    }

    public Optional<EscalationReason> escalationReason() {
        return Optional.ofNullable(escalationReason);
    }

    /** Everything the case holds, for storage; {@link #restore} is the inverse. */
    public Snapshot snapshot() {
        return new Snapshot(id, reportedCategory, category, state, decision, guardResult, escalationReason, replays,
                approvalRequest == null ? null : approvalRequest.requestedAt(), review, token, actionDetail, history);
    }

    /** How many times the case was replayed after being parked. */
    public int replays() {
        return replays;
    }

    public Optional<ApprovalRequest> approvalRequest() {
        return Optional.ofNullable(approvalRequest);
    }

    public Optional<Review> review() {
        return Optional.ofNullable(review);
    }

    public Optional<ApprovalToken> token() {
        return Optional.ofNullable(token);
    }

    /** What {@code actions-mcp} answered: its result when acted, its reason when the action failed. */
    public Optional<String> actionDetail() {
        return Optional.ofNullable(actionDetail);
    }

    public List<StateChange> history() {
        return List.copyOf(history);
    }
}
