package dev.disputeagent.investigator.domain.guard;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the guard knows about a case independently of the model: the dispute as raised, the disputed transaction as the
 * ledger has it, the record ids that belong to this case (the cardholder's card and the dispute's documents, as the
 * investigator retrieved them), and the policy section ids that exist.
 *
 * @param disputedAmount the amount the cardholder claims (for an amount mismatch, the difference); it may be more
 *     than the ledger supports, so the guard bounds a credit by {@code postedAmount} as well
 * @param postedAmount the posted amount of the disputed transaction, from the ledger
 * @param authorisedAmount the amount of the disputed transaction's authorisation, when it has one
 * @param expectedDeliveryOn the expected delivery date from the merchant's order confirmation, when the dispute has
 *     exactly one
 */
public record CaseFacts(
        CaseId caseId,
        DisputeCategory category,
        Money disputedAmount,
        Money postedAmount,
        Optional<Money> authorisedAmount,
        LocalDate postedOn,
        LocalDate openedOn,
        Optional<LocalDate> expectedDeliveryOn,
        Set<String> evidenceIdsInCase,
        Set<String> knownPolicySections) {

    public CaseFacts {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(disputedAmount, "disputedAmount");
        Objects.requireNonNull(postedAmount, "postedAmount");
        Objects.requireNonNull(authorisedAmount, "authorisedAmount");
        Objects.requireNonNull(postedOn, "postedOn");
        Objects.requireNonNull(openedOn, "openedOn");
        Objects.requireNonNull(expectedDeliveryOn, "expectedDeliveryOn");
        evidenceIdsInCase = Set.copyOf(evidenceIdsInCase);
        knownPolicySections = Set.copyOf(knownPolicySections);
    }

    /**
     * The most provisional credit the ledger supports (ADR 0009's credit ceiling): for an amount mismatch the posted
     * amount above the authorised one (nothing when there is no authorisation or no excess), otherwise the posting.
     */
    public Money creditCeiling() {
        if (category != DisputeCategory.AMOUNT_MISMATCH) {
            return postedAmount;
        }
        return authorisedAmount
                .filter(authorised -> postedAmount.isGreaterThan(authorised))
                .map(authorised -> new Money(postedAmount.amountMinor() - authorised.amountMinor(),
                        postedAmount.currency()))
                .orElse(new Money(0, postedAmount.currency()));
    }
}
