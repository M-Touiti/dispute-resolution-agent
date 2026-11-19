package dev.disputeagent.investigator.application.port.json;

import java.util.List;

/** A parsed value, or the reasons it could not be parsed (schema violations, mapping errors). */
public record Parsed<T>(T value, List<String> errors) {

    public Parsed {
        errors = List.copyOf(errors);
        if ((value == null) == errors.isEmpty()) {
            throw new IllegalArgumentException("either a value or errors");
        }
    }

    public static <T> Parsed<T> ok(T value) {
        return new Parsed<>(value, List.of());
    }

    public static <T> Parsed<T> failed(List<String> errors) {
        return new Parsed<>(null, errors);
    }

    public boolean ok() {
        return value != null;
    }
}
