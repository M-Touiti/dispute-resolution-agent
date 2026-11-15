package dev.disputeagent.investigator.application.port.llm;

import java.util.List;
import java.util.Objects;

/** One message in the conversation. */
public record LlmMessage(Role role, List<ContentBlock> content) {

    public LlmMessage {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(content);
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
    }

    public static LlmMessage userText(String text) {
        return new LlmMessage(Role.USER, List.of(new ContentBlock.Text(text)));
    }
}
