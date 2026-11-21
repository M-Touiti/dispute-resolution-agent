package dev.disputeagent.investigator.application.port.store;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.util.Optional;

/** Finished cases. A case is written once, whole, when its investigation ends. */
public interface CaseStore {

    /** The outcome of a case already investigated, for idempotent redelivery. */
    Optional<CaseOutcome> findOutcome(CaseId disputeId);

    /** Stores a finished case with its outcome, history and decision, in one transaction. */
    void save(DisputeCase finished, CaseOutcome outcome);
}
