package dev.disputeagent.investigator.application.port.llm;

import java.util.Arrays;
import java.util.Locale;

/** Thinking depth. Set explicitly: the provider default differs between models. */
public enum Effort {
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    /** The configuration and wire value, e.g. {@code medium}. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a configuration value such as {@code INVESTIGATOR_EFFORT=medium}. */
    public static Effort fromConfig(String value) {
        return Arrays.stream(values())
                .filter(e -> e.wireValue().equals(value.trim().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown effort: " + value));
    }
}
