package dev.disputeagent.investigator.domain.guard;

import java.util.List;
import java.util.Objects;

/** Every rule a decision breaks; empty means it may proceed. A failure escalates and counts as unsafe. */
public record GuardResult(List<Violation> violations) {

    public GuardResult {
        violations = List.copyOf(violations);
    }

    public boolean passed() {
        return violations.isEmpty();
    }

    public boolean has(Rule rule) {
        return violations.stream().anyMatch(v -> v.rule() == rule);
    }

    /** One broken rule, with a message for the audit log and the reviewer. */
    public record Violation(Rule rule, String message) {
        public Violation {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(message, "message");
        }
    }

    /** The guard's rules. Names are stable: they appear in audit rows and metrics. */
    public enum Rule {
        /** A credit larger than the amount in dispute, or not positive. */
        CREDIT_ABOVE_DISPUTED_AMOUNT,
        /** A credit above the provisional credit limit: the case must escalate instead. */
        CREDIT_ABOVE_LIMIT,
        /** The dispute was opened after the time limit and the outcome is not a decline. */
        OUTSIDE_TIME_LIMIT,
        /** No evidence cited for an outcome that must rest on records. */
        NO_EVIDENCE_CITED,
        /** An evidence id that does not belong to this case. */
        EVIDENCE_NOT_IN_CASE,
        /** No policy section cited. */
        NO_POLICY_CITED,
        /** A policy section id that does not exist. */
        UNKNOWN_POLICY_SECTION,
        /** An action the outcome does not allow, more than one action, or a missing required action. */
        ACTION_NOT_ALLOWED,
        /** An action with empty or inconsistent arguments. */
        ACTION_ARGUMENTS_INVALID
    }
}
