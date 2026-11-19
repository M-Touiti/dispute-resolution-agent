package dev.disputeagent.investigator.application.triage;

import dev.disputeagent.investigator.domain.DisputeCategory;
import java.util.Objects;

/** What the triage model read in the cardholder's statement. */
public record Triage(DisputeCategory category, String reason) {

    public Triage {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(reason, "reason");
    }
}
