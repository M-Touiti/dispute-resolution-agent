package dev.disputeagent.investigator.adapters.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.AssistantMessageParam;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.UserMessageParam;
import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.Effort;
import dev.disputeagent.investigator.application.port.llm.LlmMessage;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.SystemBlock;
import dev.disputeagent.investigator.application.port.llm.ToolDefinition;
import dev.disputeagent.investigator.application.port.llm.Usage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Between the provider-neutral port types and the Anthropic SDK (non-beta Messages API). The only class that knows
 * the request shape (ADR 0001):
 *
 * <ul>
 *   <li>Adaptive thinking, sent explicitly; never a budget, never disabled. Depth is {@code output_config.effort}.</li>
 *   <li>Structured output as {@code output_config.format}; no forced {@code tool_choice}, no prefill, no sampling
 *       parameters.</li>
 *   <li>The cache breakpoint on the system block that asks for it.</li>
 *   <li>Tools in the request's order (sorted by name), strict when the definition says so.</li>
 *   <li>Assistant turns passed back block for block: thinking text and signature unchanged.</li>
 * </ul>
 */
final class AnthropicMapping {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {
    };

    private AnthropicMapping() {
    }

    static MessageCreateParams toParams(LlmRequest request) {
        var output = OutputConfig.builder().effort(effort(request.effort()));
        if (request.outputFormat() != null) {
            output.format(JsonOutputFormat.builder()
                    .schema(JsonOutputFormat.Schema.builder()
                            .putAllAdditionalProperties(jsonValues(parse(request.outputFormat().schemaJson())))
                            .build())
                    .build());
        }
        var params = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens())
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(output.build())
                .systemOfTextBlockParams(request.system().stream().map(AnthropicMapping::system).toList());
        request.tools().forEach(tool -> params.addTool(tool(tool)));
        for (LlmMessage message : request.messages()) {
            List<ContentBlockParam> blocks = message.content().stream().map(AnthropicMapping::block).toList();
            switch (message.role()) {
                case USER -> params.addMessage(UserMessageParam.builder().contentOfContentBlockParams(blocks).build());
                case ASSISTANT -> params.addMessage(
                        AssistantMessageParam.builder().contentOfContentBlockParams(blocks).build());
            }
        }
        return params.build();
    }

    static LlmResponse fromMessage(Message message, PriceTable prices) {
        StopReason stop = message.stopReason()
                .map(s -> stopReason(s.asString()))
                .orElseThrow(() -> new IllegalStateException("response without stop_reason"));
        List<ContentBlock> content = new ArrayList<>();
        for (com.anthropic.models.messages.ContentBlock block : message.content()) {
            content.add(block(block));
        }
        var u = message.usage();
        Usage usage = new Usage(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L),
                u.cacheCreationInputTokens().orElse(0L));
        String model = message.model().asString();
        String refusal = stop == StopReason.REFUSAL
                ? message.stopDetails().flatMap(d -> d.category()).map(c -> c.asString()).orElse("unspecified")
                : null;
        return new LlmResponse(message.id(), model, stop, content, usage, refusal, prices.cost(model, usage));
    }

    // Request side ------------------------------------------------------------------------------------------------

    private static OutputConfig.Effort effort(Effort effort) {
        return switch (effort) {
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM -> OutputConfig.Effort.MEDIUM;
            case HIGH -> OutputConfig.Effort.HIGH;
            case XHIGH -> OutputConfig.Effort.XHIGH;
            case MAX -> OutputConfig.Effort.MAX;
        };
    }

    private static TextBlockParam system(SystemBlock block) {
        var text = TextBlockParam.builder().text(block.text());
        if (block.cacheBreakpoint()) {
            text.cacheControl(CacheControlEphemeral.builder().build());
        }
        return text.build();
    }

    private static Tool tool(ToolDefinition definition) {
        Map<String, Object> schema = parse(definition.inputSchemaJson());
        var input = Tool.InputSchema.builder().type(JsonValue.from(schema.getOrDefault("type", "object")));
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            var props = Tool.InputSchema.Properties.builder();
            properties.forEach((name, property) -> props.putAdditionalProperty((String) name, JsonValue.from(property)));
            input.properties(props.build());
        }
        if (schema.get("required") instanceof List<?> required) {
            input.required(required.stream().map(String.class::cast).toList());
        }
        schema.forEach((key, value) -> {
            if (!key.equals("type") && !key.equals("properties") && !key.equals("required")) {
                input.putAdditionalProperty(key, JsonValue.from(value));
            }
        });
        return Tool.builder()
                .name(definition.name())
                .description(definition.description())
                .inputSchema(input.build())
                .strict(definition.strict())
                .build();
    }

    private static ContentBlockParam block(ContentBlock block) {
        return switch (block) {
            case ContentBlock.Text t -> ContentBlockParam.ofText(TextBlockParam.builder().text(t.text()).build());
            case ContentBlock.Thinking t -> ContentBlockParam.ofThinking(
                    ThinkingBlockParam.builder().thinking(t.thinking()).signature(t.signature()).build());
            case ContentBlock.RedactedThinking r -> ContentBlockParam.ofRedactedThinking(
                    RedactedThinkingBlockParam.builder().data(r.data()).build());
            case ContentBlock.ToolUse u -> ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(u.id())
                    .name(u.name())
                    .input(ToolUseBlockParam.Input.builder().putAllAdditionalProperties(jsonValues(parse(u.inputJson())))
                            .build())
                    .build());
            case ContentBlock.ToolResult r -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(r.toolUseId())
                    .content(r.content())
                    .isError(r.isError())
                    .build());
        };
    }

    // Response side -----------------------------------------------------------------------------------------------

    private static StopReason stopReason(String value) {
        return switch (value) {
            case "end_turn" -> StopReason.END_TURN;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "stop_sequence" -> StopReason.STOP_SEQUENCE;
            case "pause_turn" -> StopReason.PAUSE_TURN;
            case "refusal" -> StopReason.REFUSAL;
            case "model_context_window_exceeded" -> StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED;
            default -> throw new IllegalStateException("unknown stop_reason " + value);
        };
    }

    private static ContentBlock block(com.anthropic.models.messages.ContentBlock block) {
        if (block.text().isPresent()) {
            return new ContentBlock.Text(block.text().get().text());
        }
        if (block.thinking().isPresent()) {
            var t = block.thinking().get();
            return new ContentBlock.Thinking(t.thinking(), t.signature());
        }
        if (block.redactedThinking().isPresent()) {
            return new ContentBlock.RedactedThinking(block.redactedThinking().get().data());
        }
        if (block.toolUse().isPresent()) {
            var use = block.toolUse().get();
            Object input = use._input().convert(Object.class);
            return new ContentBlock.ToolUse(use.id(), use.name(), JSON.writeValueAsString(input));
        }
        throw new IllegalStateException("unexpected content block type " + block.type()
                + "; the investigator uses no server tools");
    }

    // JSON -------------------------------------------------------------------------------------------------------

    private static Map<String, Object> parse(String json) {
        return JSON.readValue(json, OBJECT);
    }

    private static Map<String, JsonValue> jsonValues(Map<String, Object> map) {
        Map<String, JsonValue> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, JsonValue.from(v)));
        return out;
    }
}
