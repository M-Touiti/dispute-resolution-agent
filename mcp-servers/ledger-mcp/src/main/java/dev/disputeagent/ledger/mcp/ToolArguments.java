package dev.disputeagent.ledger.mcp;

import dev.disputeagent.ledger.store.LedgerRepository.Cursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads tool arguments after the server's schema validation, and enforces what the strict schemas cannot express:
 * id formats, the page-size range and well-formed page tokens. Every failure is an {@code INVALID_ARGUMENT}.
 */
final class ToolArguments {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    enum IdKind {
        CARDHOLDER("CH", "CH-000017"),
        CARD("CRD", "CRD-000123"),
        MERCHANT("MER", "MER-000042"),
        TRANSACTION("TXN", "TXN-004211"),
        AUTHORISATION("AUTH", "AUTH-004305");

        private final Pattern pattern;
        private final String example;

        IdKind(String prefix, String example) {
            this.pattern = Pattern.compile(prefix + "-\\d{6}");
            this.example = example;
        }
    }

    private final Map<String, Object> args;

    ToolArguments(Map<String, Object> args) {
        this.args = args == null ? Map.of() : args;
    }

    String requiredId(String name, IdKind kind) {
        String id = optionalId(name, kind);
        if (id == null) {
            throw ToolError.invalid(name + " is required");
        }
        return id;
    }

    String optionalId(String name, IdKind kind) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s) || !kind.pattern.matcher(s).matches()) {
            throw ToolError.invalid(name + " must look like " + kind.example);
        }
        return s;
    }

    LocalDate optionalDate(String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse((String) value);
        } catch (ClassCastException | DateTimeParseException e) {
            throw ToolError.invalid(name + " must be a date in the form YYYY-MM-DD");
        }
    }

    String optionalEnum(String name) {
        Object value = args.get(name);
        return value == null ? null : value.toString(); // the schema's enum has already been checked
    }

    int pageSize() {
        Object value = args.get("page_size");
        if (value == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue()
                || n.intValue() < 1 || n.intValue() > MAX_PAGE_SIZE) {
            throw ToolError.invalid("page_size must be a whole number from 1 to " + MAX_PAGE_SIZE);
        }
        return n.intValue();
    }

    static void checkRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ToolError.invalid("from_date must not be after to_date");
        }
    }

    /** Decodes a page token issued by {@link #pageToken} for the same tool; null when absent. */
    Cursor cursor(String tool) {
        Object value = args.get("page_token");
        if (value == null) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode((String) value), StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (parts.length != 3 || !parts[0].equals(tool)) {
                throw new IllegalArgumentException();
            }
            return new Cursor(Instant.parse(parts[1]), parts[2]);
        } catch (RuntimeException e) {
            throw ToolError.invalid("page_token is not a token this tool issued; omit it to start from the first page");
        }
    }

    static String pageToken(String tool, Instant at, String id) {
        String raw = tool + "|" + at + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
