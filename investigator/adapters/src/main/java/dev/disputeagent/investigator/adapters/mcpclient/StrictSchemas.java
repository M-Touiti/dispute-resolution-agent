package dev.disputeagent.investigator.adapters.mcpclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The investigator offers every discovered tool to the model as a strict tool, so a discovered input schema must stay
 * inside the subset strict tool use accepts. Each server's {@code ToolSchemaRulesTest} enforces this at build time;
 * the client checks it again at discovery, so a server that drifts fails start-up instead of the model call.
 */
final class StrictSchemas {

    static final Set<String> UNSUPPORTED = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "minLength", "maxLength", "pattern", "default", "minItems", "maxItems", "uniqueItems",
            "oneOf", "not", "if", "then", "else");

    private StrictSchemas() {
    }

    /** What makes this schema unusable as a strict tool; empty when it is fine. */
    static List<String> violations(Map<String, Object> schema) {
        List<String> out = new ArrayList<>();
        if (!"object".equals(schema.get("type"))) {
            out.add("the root type must be object");
        }
        if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            out.add("additionalProperties must be false");
        }
        collect(schema, "", out);
        return out;
    }

    private static void collect(Object node, String path, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                boolean isPropertyName = path.endsWith("/properties");
                if (!isPropertyName && UNSUPPORTED.contains(key)) {
                    out.add("unsupported keyword '" + key + "' at " + (path.isEmpty() ? "/" : path));
                }
                collect(e.getValue(), path + "/" + key, out);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collect(list.get(i), path + "/" + i, out);
            }
        }
    }
}
