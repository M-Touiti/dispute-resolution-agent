package dev.disputeagent.investigator.application.port.llm;

import java.util.Objects;

/** A content block in a message. JSON payloads are carried as JSON text; the adapter parses them. */
public sealed interface ContentBlock {

    record Text(String text) implements ContentBlock {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Model reasoning. The text is often empty (display omitted); the signature binds the block to this
     * conversation. Pass it back byte for byte; never edit or reorder it.
     */
    record Thinking(String thinking, String signature) implements ContentBlock {
        public Thinking {
            Objects.requireNonNull(thinking, "thinking");
            Objects.requireNonNull(signature, "signature");
        }
    }

    /** Encrypted reasoning. Opaque; passed back unchanged. */
    record RedactedThinking(String data) implements ContentBlock {
        public RedactedThinking {
            Objects.requireNonNull(data, "data");
        }
    }

    /** A tool call requested by the model. {@code inputJson} is a JSON object. */
    record ToolUse(String id, String name, String inputJson) implements ContentBlock {
        public ToolUse {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(inputJson, "inputJson");
        }
    }

    /**
     * The result of a tool call. {@code content} is untrusted data and is wrapped in delimiters by the agent loop
     * before it is placed here. A failed call sets {@code isError} rather than being dropped.
     */
    record ToolResult(String toolUseId, String content, boolean isError) implements ContentBlock {
        public ToolResult {
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(content, "content");
        }
    }
}
