package dev.disputeagent.investigator.application.port.llm;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A provider-neutral request.
 *
 * <p>The model id, effort and max tokens come from configuration, never from code. Tools are sorted by name on
 * construction so that the prompt cache prefix and the fixture key do not depend on discovery order.
 *
 * @param model        configured model id
 * @param effort       thinking depth; thinking itself is adaptive and is not configured here
 * @param maxTokens    output cap for the turn
 * @param system       stable system blocks; at most one carries the cache breakpoint
 * @param messages     the conversation so far, oldest first
 * @param tools        tool definitions, sorted by name
 * @param outputFormat structured output for the final decision, or {@code null} for a free turn
 */
public record LlmRequest(
        String model,
        Effort effort,
        int maxTokens,
        List<SystemBlock> system,
        List<LlmMessage> messages,
        List<ToolDefinition> tools,
        OutputFormat outputFormat) {

    public LlmRequest {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(effort, "effort");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        system = List.copyOf(system);
        messages = List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (system.stream().filter(SystemBlock::cacheBreakpoint).count() > 1) {
            throw new IllegalArgumentException("at most one system block may carry the cache breakpoint");
        }
        tools = tools.stream().sorted(Comparator.comparing(ToolDefinition::name)).toList();
        var names = new HashSet<String>();
        for (ToolDefinition tool : tools) {
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException("duplicate tool name: " + tool.name());
            }
        }
    }
}
