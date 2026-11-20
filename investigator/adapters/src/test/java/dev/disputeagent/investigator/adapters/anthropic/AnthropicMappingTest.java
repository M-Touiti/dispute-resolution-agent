package dev.disputeagent.investigator.adapters.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.Effort;
import dev.disputeagent.investigator.application.port.llm.LlmMessage;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.OutputFormat;
import dev.disputeagent.investigator.application.port.llm.Role;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.SystemBlock;
import dev.disputeagent.investigator.application.port.llm.ToolDefinition;
import dev.disputeagent.investigator.domain.decision.Decision;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The request body and the response mapping, checked against the SDK's own JSON (de)serialisation, offline. */
class AnthropicMappingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TOOL_SCHEMA = """
            {"type":"object","properties":{"card_id":{"type":"string","description":"Card id."}},\
            "required":["card_id"],"additionalProperties":false}""";

    static LlmRequest request() {
        return new LlmRequest("claude-opus-5-5", Effort.MEDIUM, 16_000,
                List.of(new SystemBlock("You investigate card disputes.", true)),
                List.of(LlmMessage.userText("Investigate DSP-000001."),
                        new LlmMessage(Role.ASSISTANT, List.of(new ContentBlock.Thinking("", "sig-abc"),
                                new ContentBlock.RedactedThinking("opaque"),
                                new ContentBlock.ToolUse("toolu_1", "get_card", "{\"card_id\":\"CRD-000042\"}"))),
                        new LlmMessage(Role.USER, List.of(new ContentBlock.ToolResult("toolu_1",
                                "<untrusted_tool_result>{}</untrusted_tool_result>", true)))),
                List.of(new ToolDefinition("search_policy", "Search.", TOOL_SCHEMA, true),
                        new ToolDefinition("get_card", "Get a card.", TOOL_SCHEMA, true)),
                new OutputFormat(Decision.jsonSchema()));
    }

    static JsonNode body(LlmRequest request) throws Exception {
        var params = AnthropicMapping.toParams(request);
        return JSON.readTree(ObjectMappers.jsonMapper().writeValueAsString(params._body()));
    }

    @Test
    void modelTokensThinkingAndEffort() throws Exception {
        JsonNode body = body(request());

        assertThat(body.get("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(16_000);
        assertThat(body.at("/thinking/type").asString()).isEqualTo("adaptive");
        assertThat(body.at("/thinking").has("budget_tokens")).isFalse();
        assertThat(body.at("/output_config/effort").asString()).isEqualTo("medium");
    }

    @Test
    void neverSendsForcedToolChoiceSamplingParametersOrStreaming() throws Exception {
        JsonNode body = body(request());

        assertThat(body.propertyNames()).doesNotContain("tool_choice", "temperature", "top_p", "top_k", "stream");
    }

    @Test
    void theDecisionSchemaGoesAsTheOutputFormatUnchanged() throws Exception {
        JsonNode body = body(request());

        assertThat(body.at("/output_config/format/type").asString()).isEqualTo("json_schema");
        assertThat(body.at("/output_config/format/schema")).isEqualTo(JSON.readTree(Decision.jsonSchema()));
    }

    @Test
    void theSystemBlockCarriesTheCacheBreakpoint() throws Exception {
        JsonNode system = body(request()).get("system");

        assertThat(system).hasSize(1);
        assertThat(system.get(0).get("text").asString()).isEqualTo("You investigate card disputes.");
        assertThat(system.get(0).at("/cache_control/type").asString()).isEqualTo("ephemeral");
    }

    @Test
    void toolsAreStrictSortedAndKeepTheirSchema() throws Exception {
        JsonNode tools = body(request()).get("tools");

        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).get("name").asString()).isEqualTo("get_card");
        assertThat(tools.get(1).get("name").asString()).isEqualTo("search_policy");
        assertThat(tools.get(0).get("strict").asBoolean()).isTrue();
        assertThat(tools.get(0).get("input_schema")).isEqualTo(JSON.readTree(TOOL_SCHEMA));
    }

    @Test
    void theAssistantTurnIsPassedBackBlockForBlock() throws Exception {
        JsonNode messages = body(request()).get("messages");

        assertThat(messages).hasSize(3);
        JsonNode assistant = messages.get(1);
        assertThat(assistant.get("role").asString()).isEqualTo("assistant");
        assertThat(assistant.at("/content/0/type").asString()).isEqualTo("thinking");
        assertThat(assistant.at("/content/0/signature").asString()).isEqualTo("sig-abc");
        assertThat(assistant.at("/content/1/type").asString()).isEqualTo("redacted_thinking");
        assertThat(assistant.at("/content/1/data").asString()).isEqualTo("opaque");
        assertThat(assistant.at("/content/2/type").asString()).isEqualTo("tool_use");
        assertThat(assistant.at("/content/2/input/card_id").asString()).isEqualTo("CRD-000042");
        JsonNode result = messages.get(2).at("/content/0");
        assertThat(result.get("type").asString()).isEqualTo("tool_result");
        assertThat(result.get("tool_use_id").asString()).isEqualTo("toolu_1");
        assertThat(result.get("is_error").asBoolean()).isTrue();
    }

    @Test
    void aFreeTurnHasNoOutputFormat() throws Exception {
        var r = request();
        var free = new LlmRequest(r.model(), Effort.LOW, 1_000, r.system(), r.messages(), List.of(), null);

        JsonNode body = body(free);

        assertThat(body.at("/output_config").has("format")).isFalse();
        assertThat(body.at("/output_config/effort").asString()).isEqualTo("low");
        assertThat(body.has("tools")).isFalse();
    }

    // Responses --------------------------------------------------------------------------------------------------

    static Message message(String json) throws Exception {
        return ObjectMappers.jsonMapper().readValue(json, Message.class);
    }

    static final String TOOL_USE_RESPONSE = """
            {"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"thinking","thinking":"","signature":"sig-1"},
                        {"type":"text","text":"Checking the card."},
                        {"type":"tool_use","id":"toolu_9","name":"get_card","input":{"card_id":"CRD-000042"}}],
             "stop_reason":"tool_use","stop_sequence":null,
             "usage":{"input_tokens":1200,"output_tokens":300,"cache_read_input_tokens":9000,
                      "cache_creation_input_tokens":0}}""";

    @Test
    void mapsAToolUseResponse() throws Exception {
        LlmResponse response = AnthropicMapping.fromMessage(message(TOOL_USE_RESPONSE),
                TestPrices.TABLE);

        assertThat(response.id()).isEqualTo("msg_01");
        assertThat(response.model()).isEqualTo("claude-opus-5-5");
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.content()).containsExactly(new ContentBlock.Thinking("", "sig-1"),
                new ContentBlock.Text("Checking the card."),
                new ContentBlock.ToolUse("toolu_9", "get_card", "{\"card_id\":\"CRD-000042\"}"));
        assertThat(response.usage().cacheReadInputTokens()).isEqualTo(9_000);
        // 1,200 x 4,000 + 300 x 20,000 + 9,000 x 200 nano-dollars = 12,600 micro-dollars
        assertThat(response.cost().microUsd()).isEqualTo(12_600);
        assertThat(response.refusalCategory()).isNull();
    }

    @Test
    void mapsARefusalWithItsCategory() throws Exception {
        LlmResponse response = AnthropicMapping.fromMessage(message("""
                {"id":"msg_02","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],
                 "stop_reason":"refusal","stop_sequence":null,
                 "stop_details":{"type":"refusal","category":"cyber","explanation":"declined"},
                 "usage":{"input_tokens":10,"output_tokens":0}}"""), TestPrices.TABLE);

        assertThat(response.stopReason()).isEqualTo(StopReason.REFUSAL);
        assertThat(response.refusalCategory()).isEqualTo("cyber");
        assertThat(response.content()).isEmpty();
    }

    @Test
    void anUnknownStopReasonIsAnErrorNotAGuess() throws Exception {
        Message m = message("""
                {"id":"msg_03","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],
                 "stop_reason":"something_new","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""");

        assertThatThrownBy(() -> AnthropicMapping.fromMessage(m, TestPrices.TABLE))
                .hasMessageContaining("something_new");
    }
}
