package dev.disputeagent.investigator.application.approval;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.investigation.CaseState;

/** Where a reviewed case ended: {@code ACTED}, {@code ACTION_FAILED} or {@code REJECTED}, with the detail. */
public record ReviewResult(CaseId disputeId, CaseState state, String detail) {
}
