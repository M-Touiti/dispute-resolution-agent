package dev.disputeagent.investigator.domain.decision;

/**
 * How sure the agent is of its decision. Coarse on purpose: a number between 0 and 1 cannot be bounded in a strict
 * structured-output schema and invites false precision.
 */
public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
}
