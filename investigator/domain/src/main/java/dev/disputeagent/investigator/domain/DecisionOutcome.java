package dev.disputeagent.investigator.domain;

/** The decisions the agent may propose (ADR 0001). Any outcome that moves money passes the approval gate. */
public enum DecisionOutcome {
    APPROVE_PROVISIONAL_CREDIT(true),
    REQUEST_MERCHANT_EVIDENCE(false),
    DECLINE(false),
    ESCALATE(false);

    private final boolean movesMoney;

    DecisionOutcome(boolean movesMoney) {
        this.movesMoney = movesMoney;
    }

    public boolean movesMoney() {
        return movesMoney;
    }
}
