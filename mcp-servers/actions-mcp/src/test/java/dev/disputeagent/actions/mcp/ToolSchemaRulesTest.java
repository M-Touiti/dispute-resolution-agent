package dev.disputeagent.actions.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The schemas are passed to Claude as strict tools, so they must stay inside the JSON Schema subset strict mode
 * accepts: no numeric or length constraints, no patterns, {@code additionalProperties: false} on every object.
 */
class ToolSchemaRulesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> UNSUPPORTED_IN_STRICT_MODE = Set.of(
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
            "minLength", "maxLength", "pattern", "default", "minItems", "maxItems", "uniqueItems");

    @ParameterizedTest
    @EnumSource(ActionTools.class)
    void schemaIsAStrictCompatibleClosedObject(ActionTools tool) {
        JsonNode schema = JSON.readTree(tool.inputSchemaJson());

        assertThat(schema.get("type").asString()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").isBoolean()).isTrue();
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();

        JsonNode properties = schema.get("properties");
        List<String> required = new ArrayList<>();
        schema.get("required").forEach(n -> required.add(n.asString()));
        assertThat(required).isNotEmpty();
        assertThat(properties.propertyNames()).containsAll(required);

        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            assertThat(property.getValue().has("type")).as(property.getKey()).isTrue();
            assertThat(property.getValue().get("description").asString()).as(property.getKey()).isNotBlank();
        }
    }

    @ParameterizedTest
    @EnumSource(ActionTools.class)
    void schemaUsesNoKeywordStrictModeRejects(ActionTools tool) {
        List<String> found = new ArrayList<>();
        collectKeywords(JSON.readTree(tool.inputSchemaJson()), found);

        assertThat(found).doesNotContainAnyElementsOf(UNSUPPORTED_IN_STRICT_MODE);
    }

    @Test
    void toolNamesAreUniqueSnakeCase() {
        List<String> names = Arrays.stream(ActionTools.values()).map(ActionTools::toolName).toList();

        assertThat(names).doesNotHaveDuplicates().allMatch(n -> n.matches("[a-z]+(_[a-z]+)*"));
        assertThat(names).containsExactlyInAnyOrder("close_dispute", "issue_provisional_credit",
                "request_merchant_evidence");
    }

    @ParameterizedTest
    @EnumSource(ActionTools.class)
    void everyToolRequiresAnApprovalTokenAndADispute(ActionTools tool) {
        JsonNode schema = JSON.readTree(tool.inputSchemaJson());
        List<String> required = new ArrayList<>();
        schema.get("required").forEach(n -> required.add(n.asString()));

        assertThat(required).contains(ActionTools.APPROVAL_TOKEN, "dispute_id");
        assertThat(schema.get("properties").get(ActionTools.APPROVAL_TOKEN).get("type").asString())
                .isEqualTo("string");
    }

    private static void collectKeywords(JsonNode node, List<String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                out.add(e.getKey());
                collectKeywords(e.getValue(), out);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectKeywords(child, out));
        }
    }
}
