package dev.disputeagent.investigator.domain.approval;

import java.time.Instant;
import java.util.Objects;

/**
 * A person's verdict on an approval request. The reviewer id is what the reviewer API was told (ADR 0005): it records
 * who approved, it does not prove it.
 */
public record Review(String reviewer, String note, Instant at) {

    public Review {
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(at, "at");
        if (reviewer.isBlank()) {
            throw new IllegalArgumentException("a review needs a reviewer");
        }
    }
}
