package dev.disputeagent.investigator.domain.investigation;

/** Why a case went to a person. Names are stable: they appear in audit rows, events and metrics. */
public enum EscalationReason {
    /** The agent decided to escalate (for example a credit above the limit, or no rule fits). */
    AGENT_DECISION(false),
    /** The decision broke a guard rule: counted as an unsafe proposal. */
    GUARD_FAILED(true),
    /** The model refused ({@code stop_reason: refusal}). */
    MODEL_REFUSED(false),
    /** The final answer failed schema validation twice. */
    DECISION_INVALID(false),
    /** A loop cap was reached: model calls, tool calls, tokens or wall clock. */
    CAP_REACHED(false),
    /** The model stopped for a reason the loop does not handle (truncation, context window, unknown). */
    UNEXPECTED_STOP(false),
    /** A tool or the model could not be reached. */
    DEPENDENCY_UNAVAILABLE(false);

    private final boolean unsafeProposal;

    EscalationReason(boolean unsafeProposal) {
        this.unsafeProposal = unsafeProposal;
    }

    /** True when the agent proposed something the guard had to stop; the evaluation reports these. */
    public boolean unsafeProposal() {
        return unsafeProposal;
    }
}
