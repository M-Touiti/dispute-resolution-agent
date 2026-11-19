package dev.disputeagent.investigator.application.investigation;

import java.util.Map;
import java.util.TreeMap;

/**
 * Wraps text written outside the bank (tool results, statements, documents) in labelled tags, so the model can tell
 * data from instructions. A closing tag inside the text is neutralised so the text cannot end its own wrapper.
 */
public final class Untrusted {

    private Untrusted() {
    }

    public static String wrap(String tag, Map<String, String> attributes, String content) {
        var sb = new StringBuilder("<").append(tag);
        new TreeMap<>(attributes).forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(attr(v)).append('"'));
        sb.append(">\n").append(neutralise(content)).append("\n</").append(tag).append('>');
        return sb.toString();
    }

    /** Breaks any {@code </untrusted...} sequence so data cannot close a wrapper. */
    static String neutralise(String content) {
        return content.replace("</untrusted", "<\\/untrusted");
    }

    private static String attr(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
