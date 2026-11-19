package dev.disputeagent.investigator.domain.decision;

/** What a merchant can be asked for. Must equal the enum of {@code request_merchant_evidence} in actions-mcp. */
public enum EvidenceKind {
    RECEIPT,
    PROOF_OF_DELIVERY,
    SIGNED_AUTHORISATION,
    CANCELLATION_POLICY,
    REFUND_RECORD
}
