package dev.disputeagent.investigator.application.port.approval;

import dev.disputeagent.investigator.domain.approval.ApprovalToken;

/** Signs approval tokens (ADR 0005): the claims in, the compact JWS that actions-mcp verifies out. */
public interface TokenSigner {

    String sign(ApprovalToken claims);
}
