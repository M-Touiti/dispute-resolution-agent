package dev.disputeagent.investigator.application.investigation;

import dev.disputeagent.investigator.application.port.llm.Effort;
import dev.disputeagent.investigator.domain.guard.PolicyLimits;
import dev.disputeagent.investigator.domain.investigation.LoopBudget;
import java.util.Objects;

/**
 * Everything the use cases take from configuration (ADR 0001: model ids, effort and token caps never in code).
 *
 * @param decisionRetries how many times an answer that fails the decision schema is sent back with the errors
 */
public record InvestigationSettings(
        String investigatorModel,
        Effort investigatorEffort,
        int investigatorMaxTokens,
        String triageModel,
        Effort triageEffort,
        int triageMaxTokens,
        LoopBudget budget,
        PolicyLimits policyLimits,
        int decisionRetries) {

    public InvestigationSettings {
        Objects.requireNonNull(investigatorEffort, "investigatorEffort");
        Objects.requireNonNull(triageEffort, "triageEffort");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(policyLimits, "policyLimits");
        if (investigatorModel == null || investigatorModel.isBlank() || triageModel == null || triageModel.isBlank()) {
            throw new IllegalArgumentException("model ids come from configuration and must be set");
        }
        if (investigatorMaxTokens <= 0 || triageMaxTokens <= 0 || decisionRetries < 0) {
            throw new IllegalArgumentException("token caps must be positive and retries not negative");
        }
    }
}
