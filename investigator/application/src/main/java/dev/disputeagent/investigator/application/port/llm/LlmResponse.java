package dev.disputeagent.investigator.application.port.llm;

import java.util.List;
import java.util.Objects;

/**
 * A provider-neutral response.
 *
 * @param id              provider message id, for audit
 * @param model           the model that actually answered; may differ from the configured id after a fallback
 * @param stopReason      why generation stopped; check before reading content
 * @param content         content blocks in order; thinking blocks must be passed back unchanged on the next turn
 * @param usage           token usage including cache reads and writes
 * @param refusalCategory provider category when {@code stopReason} is {@link StopReason#REFUSAL}, else {@code null}
 * @param cost            what the call cost, from the adapter's price table
 */
public record LlmResponse(
        String id,
        String model,
        StopReason stopReason,
        List<ContentBlock> content,
        Usage usage,
        String refusalCategory,
        LlmCost cost) {

    public LlmResponse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(cost, "cost");
        content = List.copyOf(content);
    }

    /** The tool calls in this response, in order. All results go back in a single user message. */
    public List<ContentBlock.ToolUse> toolUses() {
        return content.stream()
                .filter(ContentBlock.ToolUse.class::isInstance)
                .map(ContentBlock.ToolUse.class::cast)
                .toList();
    }

    /** This response as an assistant message, to append to the conversation unchanged. */
    public LlmMessage asAssistantMessage() {
        return new LlmMessage(Role.ASSISTANT, content);
    }
}
