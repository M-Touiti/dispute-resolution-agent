package dev.disputeagent.investigator.application.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.port.approval.ActionPort;
import dev.disputeagent.investigator.application.port.approval.TokenSigner;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.support.Fakes.RecordingAudit;
import dev.disputeagent.investigator.application.support.Fakes.TickingClock;
import dev.disputeagent.investigator.application.support.MemoryCases;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.approval.ApprovalToken;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The approval gate with fake ports: request, approve and act, reject, conflicts, resume, failures (ADR 0005). */
class ApprovalGateTest {

    static final CaseId ID = new CaseId("DSP-000018");
    static final ProposedAction CREDIT = new ProposedAction.IssueProvisionalCredit(new Money(110056, "EUR"));
    static final String HASH = "ab".repeat(32);
    static final UUID TOKEN_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    final RecordingAudit audit = new RecordingAudit();
    final TickingClock clock = new TickingClock(Instant.parse("2026-11-23T09:00:00Z"));
    final MemoryCases cases = new MemoryCases();
    final List<ApprovalToken> signed = new ArrayList<>();
    final TokenSigner signer = claims -> {
        signed.add(claims);
        return "jws." + claims.tokenId();
    };
    final FakeActions actions = new FakeActions();

    /** Records which case and action each call was for; hashes only the case and action it knows. */
    static final class FakeActions implements ActionPort {
        final List<String> tokensSent = new ArrayList<>();
        final List<String> hashed = new ArrayList<>();
        final List<String> executed = new ArrayList<>();
        ActionPort.Result answer = new ActionPort.Result(true, "credited 1100.56 EUR");
        RuntimeException failure;

        @Override
        public String argumentsSha256(CaseId disputeId, ProposedAction action) {
            hashed.add(disputeId + " " + action.toolName());
            if (!disputeId.equals(ID) || !action.equals(CREDIT)) {
                throw new AssertionError("hash asked for " + disputeId + " " + action);
            }
            return HASH;
        }

        @Override
        public ActionPort.Result execute(CaseId disputeId, ProposedAction action, String approvalToken) {
            executed.add(disputeId + " " + action.toolName());
            if (!action.equals(CREDIT)) {
                throw new AssertionError("asked to execute " + action + ", the case proposed " + CREDIT);
            }
            tokensSent.add(approvalToken);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    ApproveAction approve() {
        return new ApproveAction(cases, signer, actions, audit, clock, () -> TOKEN_ID);
    }

    RejectAction reject() {
        return new RejectAction(cases, audit, clock);
    }

    /** A case decided by the investigation, put before a person and stored, as ProcessDisputeOpened does. */
    @BeforeEach
    void aCaseWaitsForApproval() {
        var c = new DisputeCase(ID, DisputeCategory.UNAUTHORISED_TRANSACTION, clock.instant());
        c.triaged(DisputeCategory.UNAUTHORISED_TRANSACTION, clock.instant());
        c.investigationStarted(clock.instant());
        c.decided(new Decision(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT, Confidence.HIGH, "r", List.of("TXN-1"),
                List.of("unauthorised-card-not-present"), List.of(CREDIT)), new GuardResult(List.of()),
                clock.instant());
        CaseOutcome outcome = CaseOutcome.of(c);

        var request = new RequestApproval(audit, clock).request(c);

        assertThat(request).isPresent();
        assertThat(request.get().action()).isEqualTo(CREDIT);
        assertThat(c.state()).isEqualTo(CaseState.AWAITING_APPROVAL);
        assertThat(outcome.state()).as("the published outcome is the investigation's").isEqualTo(CaseState.DECIDED);
        cases.save(c, outcome, java.util.List.of());
        audit.events.clear();
    }

    @Test
    void anApprovalSignsATokenStoresItThenCarriesTheActionOut() {
        var result = approve().approve(ID, "reviewer-1", "evidence checked");

        assertThat(result.state()).isEqualTo(CaseState.ACTED);
        assertThat(result.detail()).isEqualTo("credited 1100.56 EUR");
        assertThat(cases.stateOf(ID)).isEqualTo(CaseState.ACTED);

        ApprovalToken claims = signed.getFirst();
        assertThat(claims.tokenId()).isEqualTo(TOKEN_ID);
        assertThat(claims.caseId()).isEqualTo(ID);
        assertThat(claims.action()).isEqualTo("issue_provisional_credit");
        assertThat(claims.argumentsSha256()).isEqualTo(HASH);
        assertThat(claims.reviewer()).isEqualTo("reviewer-1");
        assertThat(actions.tokensSent).containsExactly("jws." + TOKEN_ID);
        assertThat(actions.hashed).as("the hash is for the action that runs")
                .containsExactly(ID + " issue_provisional_credit");
        assertThat(actions.executed).containsExactly(ID + " issue_provisional_credit");
        assertThat(cases.find(ID).orElseThrow().approvalToken()).contains("jws." + TOKEN_ID);

        assertThat(audit.events).extracting(e -> e.getClass().getSimpleName()).containsExactly("Reviewed",
                "TokenIssued", "StateChanged", "ActionCalled", "StateChanged");
        assertThat(audit.of(AuditEvent.StateChanged.class)).extracting(AuditEvent.StateChanged::to)
                .containsExactly(CaseState.APPROVED, CaseState.ACTED);
        assertThat(audit.of(AuditEvent.TokenIssued.class).getFirst().tokenId()).isEqualTo(TOKEN_ID);
    }

    @Test
    void theCaseIsStoredAsApprovedBeforeTheCall() {
        actions.failure = new IllegalStateException("crash during the call");
        assertThatThrownBy(() -> approve().approve(ID, "reviewer-1", "ok")).hasMessage("crash during the call");

        assertThat(cases.stateOf(ID)).isEqualTo(CaseState.APPROVED);
        assertThat(cases.find(ID).orElseThrow().approvalToken()).contains("jws." + TOKEN_ID);
    }

    @Test
    void approvingAnInterruptedCaseAgainResendsTheSameToken() {
        actions.failure = new IllegalStateException("crash during the call");
        assertThatThrownBy(() -> approve().approve(ID, "reviewer-1", "ok"));
        actions.failure = null;

        var result = approve().approve(ID, "reviewer-2", "resume");

        assertThat(result.state()).isEqualTo(CaseState.ACTED);
        assertThat(signed).as("no second token").hasSize(1);
        assertThat(actions.tokensSent).containsExactly("jws." + TOKEN_ID, "jws." + TOKEN_ID);
        assertThat(actions.executed).as("the stored case and action, restored")
                .containsExactly(ID + " issue_provisional_credit", ID + " issue_provisional_credit");
        assertThat(actions.hashed).as("no second hash on resume").hasSize(1);
    }

    @Test
    void anInterruptedCaseWhoseTokenExpiredFailsWithoutACall() {
        actions.failure = new IllegalStateException("crash during the call");
        assertThatThrownBy(() -> approve().approve(ID, "reviewer-1", "ok"));
        actions.failure = null;
        clock.advance(Duration.ofMinutes(10));

        var result = approve().approve(ID, "reviewer-1", "resume");

        assertThat(result.state()).isEqualTo(CaseState.ACTION_FAILED);
        assertThat(result.detail()).contains("expired");
        assertThat(actions.tokensSent).hasSize(1);
    }

    @Test
    void theServersRefusalEndsTheCaseWithItsReason() {
        actions.answer = new ActionPort.Result(false, "APPROVAL_REJECTED: dispute already closed");

        var result = approve().approve(ID, "reviewer-1", "ok");

        assertThat(result.state()).isEqualTo(CaseState.ACTION_FAILED);
        assertThat(cases.find(ID).orElseThrow().disputeCase().actionDetail())
                .contains("APPROVAL_REJECTED: dispute already closed");
        assertThat(audit.of(AuditEvent.ActionCalled.class).getFirst().isError()).isTrue();
    }

    @Test
    void anUnreachableServerEndsTheCaseAsUnknownForAPersonToCheck() {
        actions.failure = new ActionPort.Unavailable("connection refused", null);

        var result = approve().approve(ID, "reviewer-1", "ok");

        assertThat(result.state()).isEqualTo(CaseState.ACTION_FAILED);
        assertThat(result.detail()).contains("outcome unknown").contains("connection refused");
    }

    @Test
    void aRejectionCarriesNothingOut() {
        var result = reject().reject(ID, "reviewer-1", "evidence contradicts the claim");

        assertThat(result.state()).isEqualTo(CaseState.REJECTED);
        assertThat(cases.stateOf(ID)).isEqualTo(CaseState.REJECTED);
        assertThat(actions.tokensSent).isEmpty();
        assertThat(signed).isEmpty();
        assertThat(audit.of(AuditEvent.Reviewed.class).getFirst().approved()).isFalse();
    }

    @Test
    void aCaseIsReviewedOnce() {
        reject().reject(ID, "reviewer-1", "no");

        assertThatThrownBy(() -> approve().approve(ID, "reviewer-2", "yes"))
                .isInstanceOfSatisfying(ReviewException.Conflict.class,
                        e -> assertThat(e.state()).isEqualTo(CaseState.REJECTED));
        assertThatThrownBy(() -> reject().reject(ID, "reviewer-2", "no"))
                .isInstanceOf(ReviewException.Conflict.class);
        assertThat(actions.tokensSent).isEmpty();
    }

    @Test
    void aReviewThatLosesTheRaceChangesNothing() {
        // Both reviewers read AWAITING_APPROVAL; the second update finds the case changed.
        var raced = new ApproveAction(new MemoryCasesThatChangeUnderneath(cases), signer, actions, audit, clock,
                () -> TOKEN_ID);
        reject().reject(ID, "reviewer-1", "no");

        assertThatThrownBy(() -> raced.approve(ID, "reviewer-2", "yes")).isInstanceOf(ReviewException.Conflict.class);
        assertThat(actions.tokensSent).isEmpty();
        assertThat(audit.of(AuditEvent.TokenIssued.class)).isEmpty();
    }

    @Test
    void anUnknownCaseIsNotFound() {
        assertThatThrownBy(() -> approve().approve(new CaseId("DSP-999999"), "r", "n"))
                .isInstanceOf(ReviewException.NotFound.class);
    }

    @Test
    void anEscalatedCaseIsNeverPutBeforeAPerson() {
        var c = new DisputeCase(new CaseId("DSP-000002"), DisputeCategory.UNAUTHORISED_TRANSACTION, clock.instant());
        c.escalate(EscalationReason.CASE_DATA_INVALID, clock.instant(), "x");

        assertThat(new RequestApproval(audit, clock).request(c)).isEmpty();
        assertThat(c.state()).isEqualTo(CaseState.ESCALATED);
    }

    /** Serves the case as it was before the rejection, as a reviewer who read it earlier would have it. */
    static final class MemoryCasesThatChangeUnderneath implements dev.disputeagent.investigator.application.port.store.CaseStore {
        private final MemoryCases real;
        private final StoredCase before;

        MemoryCasesThatChangeUnderneath(MemoryCases real) {
            this.real = real;
            this.before = real.find(ID).orElseThrow();
        }

        @Override
        public java.util.Optional<StoredCase> find(CaseId id) {
            return real.stateOf(id) == CaseState.AWAITING_APPROVAL ? real.find(id)
                    : java.util.Optional.of(new StoredCase(DisputeCase.restore(before.disputeCase().snapshot()),
                            before.outcome(), before.approvalToken()));
        }

        @Override
        public java.util.List<StoredCase> awaitingApproval(int limit) {
            return real.awaitingApproval(limit);
        }

        @Override
        public void save(DisputeCase c, CaseOutcome outcome,
                java.util.List<dev.disputeagent.investigator.application.port.events.OutgoingEvent> events) {
            real.save(c, outcome, events);
        }

        @Override
        public boolean update(DisputeCase c, CaseState expected, String token) {
            return real.update(c, expected, token);
        }
    }
}
