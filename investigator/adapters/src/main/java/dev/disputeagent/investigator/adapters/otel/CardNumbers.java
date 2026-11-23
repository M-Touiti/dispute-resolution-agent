package dev.disputeagent.investigator.adapters.otel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks anything shaped like a card number before it is logged (ADR 0008): 13 to 19 digits, optionally grouped by
 * spaces or dashes, that pass the Luhn check. All but the last four digits become {@code *}. The data is synthetic and
 * the ledger only ever returns the last four digits; this is the defence for the day a statement or a merchant
 * descriptor carries a full number.
 *
 * <p>The Luhn check keeps ids and amounts readable. A long number that happens to pass it (an epoch in milliseconds,
 * one time in ten) is masked as well: over-masking a log line is the safe side.
 */
public final class CardNumbers {

    private static final Pattern CANDIDATE = Pattern.compile("(?<![\\d-])\\d(?:[ -]?\\d){12,18}(?![\\d-])");

    private CardNumbers() {
    }

    public static String mask(String text) {
        if (text == null || text.length() < 13) {
            return text;
        }
        Matcher m = CANDIDATE.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            if (!luhn(digits)) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, last, m.start())
                    .append("*".repeat(digits.length() - 4))
                    .append(digits, digits.length() - 4, digits.length());
            last = m.end();
        }
        return out == null ? text : out.append(text, last, text.length()).toString();
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return sum % 10 == 0;
    }
}
