package dev.disputeagent.investigator.domain.guard;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * What the guard knows about a case independently of the model: the dispute as raised, the posting date of the
 * disputed transaction, the record ids that belong to this case (the cardholder's card and the dispute's documents,
 * as the investigator retrieved them), and the policy section ids that exist.
 *
 * @param disputedAmount the amount in dispute (for an amount mismatch, the difference, not the whole transaction)
 */
public record CaseFacts(
        CaseId caseId,
        DisputeCategory category,
        Money disputedAmount,
        LocalDate postedOn,
        LocalDate openedOn,
        Set<String> evidenceIdsInCase,
        Set<String> knownPolicySections) {

    public CaseFacts {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(disputedAmount, "disputedAmount");
        Objects.requireNonNull(postedOn, "postedOn");
        Objects.requireNonNull(openedOn, "openedOn");
        evidenceIdsInCase = Set.copyOf(evidenceIdsInCase);
        knownPolicySections = Set.copyOf(knownPolicySections);
    }
}
