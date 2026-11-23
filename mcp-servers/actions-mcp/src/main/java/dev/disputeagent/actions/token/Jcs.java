package dev.disputeagent.actions.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * JSON Canonicalization Scheme (RFC 8785), for the subset the approval token uses: objects, arrays, strings, integers
 * within ±2^53, booleans and null. Anything else (a fraction, a larger integer, a lone surrogate) is refused rather
 * than canonicalised differently from the other side. The investigator has its own copy (its
 * {@code adapters.approval.Jcs}): the write server depends on nothing of the investigator's. The shared vectors in
 * {@code docs/approval-token/vectors.json} keep the two equal (ADR 0005).
 *
 * <ul>
 *   <li>Object members are sorted by their names as UTF-16 code units ({@link String#compareTo}).</li>
 *   <li>Strings are escaped as ECMAScript {@code JSON.stringify} does: {@code \"}, {@code \\}, {@code \b \f \n \r \t},
 *       other control characters as a backslash, {@code u} and four lower-case hex digits; everything else as is, including non-ASCII.</li>
 *   <li>No whitespace. The output is UTF-8.</li>
 * </ul>
 */
public final class Jcs {

    /** The largest integer an IEEE 754 double holds exactly; JCS serialises numbers as doubles. */
    private static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    private Jcs() {
    }

    public static String canonical(JsonNode value) {
        var out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** Lower-case hex SHA-256 of the canonical UTF-8 bytes. */
    public static String sha256Hex(JsonNode value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    private static void write(JsonNode v, StringBuilder out) {
        if (v.isObject()) {
            List<Map.Entry<String, JsonNode>> members = new ArrayList<>(v.properties());
            members.sort(Map.Entry.comparingByKey());
            out.append('{');
            for (int i = 0; i < members.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                string(members.get(i).getKey(), out);
                out.append(':');
                write(members.get(i).getValue(), out);
            }
            out.append('}');
        } else if (v.isArray()) {
            out.append('[');
            int i = 0;
            for (JsonNode item : v.values()) {
                if (i++ > 0) {
                    out.append(',');
                }
                write(item, out);
            }
            out.append(']');
        } else if (v.isString()) {
            string(v.stringValue(), out);
        } else if (v.isIntegralNumber()) {
            if (!v.canConvertToLong() || Math.abs(v.longValue()) > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("integer outside ±2^53 cannot be canonicalised exactly: " + v);
            }
            out.append(v.longValue());
        } else if (v.isBoolean()) {
            out.append(v.booleanValue());
        } else if (v.isNull()) {
            out.append("null");
        } else {
            throw new IllegalArgumentException("not in the canonicalised subset: " + v.getNodeType() + " " + v);
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    throw new IllegalArgumentException("lone surrogate at index " + i);
                }
                out.append(c).append(s.charAt(++i));
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("lone surrogate at index " + i);
            }
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
