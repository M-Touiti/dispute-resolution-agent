package dev.disputeagent.investigator.domain;

/** The five reason categories in scope (ADR 0001). Not network reason codes. */
public enum DisputeCategory {
    UNAUTHORISED_TRANSACTION,
    DUPLICATE_CHARGE,
    GOODS_NOT_RECEIVED,
    CANCELLED_RECURRING,
    AMOUNT_MISMATCH
}
