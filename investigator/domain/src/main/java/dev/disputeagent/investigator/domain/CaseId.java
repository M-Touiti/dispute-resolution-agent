package dev.disputeagent.investigator.domain;

import java.util.Objects;

/** Identity of a dispute case. Opaque; assigned by the system that raised the dispute. */
public record CaseId(String value) {

    public CaseId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("case id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
