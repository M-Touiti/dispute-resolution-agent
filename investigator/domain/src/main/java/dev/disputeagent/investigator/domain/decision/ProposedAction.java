package dev.disputeagent.investigator.domain.decision;

import dev.disputeagent.investigator.domain.DecisionOutcome;
import java.util.List;
import java.util.Objects;

/**
 * An action the agent proposes. Nothing here executes: from M2 a person approves it and the investigator, not the
 * model, calls {@code actions-mcp}. Tool names are those of actions-mcp.
 */
public sealed interface ProposedAction {

    /** The actions-mcp tool that would carry the action out. */
    String toolName();

    /** The only decision outcome this action may accompany (data/policy-corpus, section decisions-and-actions). */
    DecisionOutcome allowedWith();

    record IssueProvisionalCredit(Money amount) implements ProposedAction {
        public IssueProvisionalCredit {
            Objects.requireNonNull(amount, "amount");
        }

        @Override
        public String toolName() {
            return "issue_provisional_credit";
        }

        @Override
        public DecisionOutcome allowedWith() {
            return DecisionOutcome.APPROVE_PROVISIONAL_CREDIT;
        }
    }

    record RequestMerchantEvidence(List<EvidenceKind> evidence, String note) implements ProposedAction {
        public RequestMerchantEvidence {
            evidence = List.copyOf(evidence);
            Objects.requireNonNull(note, "note");
        }

        @Override
        public String toolName() {
            return "request_merchant_evidence";
        }

        @Override
        public DecisionOutcome allowedWith() {
            return DecisionOutcome.REQUEST_MERCHANT_EVIDENCE;
        }
    }

    record CloseDispute(String reason, List<String> policySectionIds) implements ProposedAction {
        public CloseDispute {
            Objects.requireNonNull(reason, "reason");
            policySectionIds = List.copyOf(policySectionIds);
        }

        @Override
        public String toolName() {
            return "close_dispute";
        }

        @Override
        public DecisionOutcome allowedWith() {
            return DecisionOutcome.DECLINE;
        }
    }
}
