package dev.disputeagent.investigator.adapters.json;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.disputeagent.investigator.application.port.json.JsonPort;
import dev.disputeagent.investigator.application.port.json.Parsed;
import dev.disputeagent.investigator.application.triage.Triage;
import dev.disputeagent.investigator.application.triage.TriageDispute;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Confidence;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.decision.EvidenceKind;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link JsonPort} on Jackson 3, validating structured outputs with {@code json-schema-validator} (draft 2020-12),
 * the library and version the MCP SDK uses for tool inputs. Schema errors are returned as text for the model to
 * correct; mapping happens only after the document is valid.
 */
public final class JacksonJsonAdapter implements JsonPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private final Schema decisionSchema = SCHEMAS.getSchema(Decision.jsonSchema());
    private final Schema triageSchema = SCHEMAS.getSchema(TriageDispute.schema());

    @Override
    public Parsed<Decision> decision(String json) {
        return parse(json, decisionSchema, JacksonJsonAdapter::toDecision);
    }

    @Override
    public Parsed<Triage> triage(String json) {
        return parse(json, triageSchema, node -> new Triage(DisputeCategory.valueOf(node.get("category").asString()),
                node.get("reason").asString()));
    }

    @Override
    public List<String> valuesOf(String json, String field) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        collect(root, field, out);
        return out;
    }

    @Override
    public String object(Map<String, ?> members) {
        return JSON.writeValueAsString(new TreeMap<>(members));
    }

    private static <T> Parsed<T> parse(String json, Schema schema, Function<JsonNode, T> mapper) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JacksonException e) {
            return Parsed.failed(List.of("not valid JSON: " + e.getOriginalMessage()));
        }
        List<Error> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            return Parsed.failed(errors.stream().map(Error::toString).sorted().toList());
        }
        try {
            return Parsed.ok(mapper.apply(node));
        } catch (RuntimeException e) {
            return Parsed.failed(List.of("invalid value: " + e.getMessage()));
        }
    }

    private static Decision toDecision(JsonNode node) {
        List<ProposedAction> actions = new ArrayList<>();
        for (JsonNode a : node.get("proposed_actions")) {
            actions.add(switch (a.get("action").asString()) {
                case "issue_provisional_credit" -> new ProposedAction.IssueProvisionalCredit(
                        new Money(a.get("amount_minor").asLong(), a.get("currency").asString()));
                case "request_merchant_evidence" -> new ProposedAction.RequestMerchantEvidence(
                        strings(a.get("evidence")).stream().map(EvidenceKind::valueOf).toList(),
                        a.get("note").asString());
                case "close_dispute" -> new ProposedAction.CloseDispute(a.get("reason").asString(),
                        strings(a.get("policy_section_ids")));
                default -> throw new IllegalArgumentException("unknown action " + a.get("action"));
            });
        }
        return new Decision(
                DecisionOutcome.valueOf(node.get("outcome").asString()),
                Confidence.valueOf(node.get("confidence").asString()),
                node.get("rationale").asString(),
                strings(node.get("cited_evidence_ids")),
                strings(node.get("cited_policy_sections")),
                actions);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static void collect(JsonNode node, String field, List<String> out) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if (e.getKey().equals(field) && e.getValue().isString()) {
                    out.add(e.getValue().asString());
                }
                collect(e.getValue(), field, out);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, field, out));
        }
    }
}
