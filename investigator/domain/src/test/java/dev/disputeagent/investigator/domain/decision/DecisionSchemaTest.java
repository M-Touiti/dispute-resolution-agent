package dev.disputeagent.investigator.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.domain.DecisionOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The decision schema is sent as {@code output_config.format}: it must stay inside the structured-output subset and
 * agree with the Java types and with the tools of actions-mcp that would carry the actions out.
 */
class DecisionSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode SCHEMA = JSON.readTree(Decision.jsonSchema());
    private static final Path ACTIONS_TOOLS = Path.of("../../mcp-servers/actions-mcp/src/main/resources/tools");

    private static final Set<String> UNSUPPORTED = Set.of("minimum", "maximum", "exclusiveMinimum",
            "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "pattern", "default", "minItems",
            "maxItems", "uniqueItems", "oneOf", "not", "if", "then", "else");

    @Test
    void usesNoKeywordOutsideTheStructuredOutputSubset() {
        List<String> keys = new ArrayList<>();
        collectKeys(SCHEMA, keys);
        assertThat(keys).doesNotContainAnyElementsOf(UNSUPPORTED);
    }

    @Test
    void everyObjectIsClosedAndRequiresAllItsProperties() {
        List<JsonNode> objects = new ArrayList<>();
        collectObjects(SCHEMA, objects);

        assertThat(objects).hasSize(4); // the decision and the three action shapes
        for (JsonNode object : objects) {
            assertThat(object.get("additionalProperties").asBoolean(true)).isFalse();
            Set<String> required = new HashSet<>();
            object.get("required").forEach(n -> required.add(n.asString()));
            assertThat(required).isEqualTo(new HashSet<>(object.get("properties").propertyNames()));
        }
    }

    @Test
    void enumsMatchTheJavaTypes() {
        assertThat(strings(SCHEMA.at("/properties/outcome/enum")))
                .containsExactlyElementsOf(names(DecisionOutcome.values()));
        assertThat(strings(SCHEMA.at("/properties/confidence/enum")))
                .containsExactlyElementsOf(names(Confidence.values()));
        assertThat(strings(actionShape("request_merchant_evidence").at("/properties/evidence/items/enum")))
                .containsExactlyElementsOf(names(EvidenceKind.values()));
    }

    @Test
    void actionNamesAreTheActionsMcpToolNames() throws Exception {
        List<String> inSchema = new ArrayList<>();
        SCHEMA.at("/properties/proposed_actions/items/anyOf")
                .forEach(shape -> inSchema.add(shape.at("/properties/action/const").asString()));
        List<String> javaTools = List.of(new ProposedAction.IssueProvisionalCredit(Money.eur(1)).toolName(),
                new ProposedAction.RequestMerchantEvidence(List.of(), "").toolName(),
                new ProposedAction.CloseDispute("", List.of()).toolName());
        List<String> serverTools;
        try (var files = Files.list(ACTIONS_TOOLS)) {
            serverTools = files.map(p -> p.getFileName().toString().replace(".schema.json", "")).sorted().toList();
        }

        assertThat(inSchema).containsExactlyInAnyOrderElementsOf(serverTools);
        assertThat(javaTools).containsExactlyInAnyOrderElementsOf(serverTools);
    }

    @Test
    void evidenceKindsAreTheOnesActionsMcpAccepts() throws Exception {
        JsonNode tool = JSON.readTree(Files.readString(ACTIONS_TOOLS.resolve("request_merchant_evidence.schema.json")));
        assertThat(strings(tool.at("/properties/evidence/items/enum")))
                .containsExactlyElementsOf(names(EvidenceKind.values()));
    }

    private static JsonNode actionShape(String action) {
        for (JsonNode shape : SCHEMA.at("/properties/proposed_actions/items/anyOf")) {
            if (shape.at("/properties/action/const").asString().equals(action)) {
                return shape;
            }
        }
        throw new AssertionError("no action " + action);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static void collectKeys(JsonNode node, List<String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (!e.getKey().equals("properties")) {
                    out.add(e.getKey());
                }
                collectKeys(e.getValue(), out);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, out));
        }
    }

    private static void collectObjects(JsonNode node, List<JsonNode> out) {
        if (node.isObject()) {
            if ("object".equals(node.path("type").asString(""))) {
                out.add(node);
            }
            node.properties().forEach(e -> collectObjects(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectObjects(child, out));
        }
    }
}
