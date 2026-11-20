package dev.disputeagent.investigator.adapters.fixtures;

import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.LlmMessage;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.SystemBlock;
import dev.disputeagent.investigator.application.port.llm.ToolDefinition;
import dev.disputeagent.investigator.application.port.llm.Usage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The fixture format and its key (ADR 0006).
 *
 * <p><b>Key.</b> SHA-256 of the canonical request: the port request as JSON with every object's keys sorted and every
 * embedded JSON (tool inputs, tool schemas, the output schema) parsed and sorted too. Formatting and key order cannot
 * move the key; any change to the model id, effort, token cap, prompt, tools, schema or conversation does. The port
 * request holds no timestamps and no secrets.
 *
 * <p><b>File.</b> {@code <dir>/<key>.json}: the format version, the key, the canonical request (so a pull request
 * shows what changed in the prompt) and the response. No recording time, so re-recording the same response writes
 * the same bytes. Tool inputs are kept as their exact JSON text, so replay hands the same bytes to the tools.
 */
public final class Fixtures {

    /** Bumped when the canonical form or the file format changes; every fixture is then re-recorded. */
    public static final int FORMAT = 1;

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(EnumFeature.WRITE_ENUMS_TO_LOWERCASE, false)
            .build();
    private static final JsonMapper COMPACT = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final Path dir;

    public Fixtures(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    // Key --------------------------------------------------------------------------------------------------------

    /** The canonical request as a sorted tree; the key is the SHA-256 of its compact form. */
    public static JsonNode canonical(LlmRequest request) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("format", FORMAT);
        r.put("model", request.model());
        r.put("effort", request.effort().wireValue());
        r.put("max_tokens", request.maxTokens());
        r.put("system", request.system().stream().map(Fixtures::system).toList());
        r.put("messages", request.messages().stream().map(Fixtures::message).toList());
        r.put("tools", request.tools().stream().map(Fixtures::tool).toList());
        r.put("output_schema", request.outputFormat() == null ? null : parse(request.outputFormat().schemaJson()));
        return sorted(CANONICAL.valueToTree(r));
    }

    public static String key(LlmRequest request) {
        return sha256(COMPACT.writeValueAsString(canonical(request)));
    }

    private static Map<String, Object> system(SystemBlock block) {
        return Map.of("text", block.text(), "cache_breakpoint", block.cacheBreakpoint());
    }

    private static Map<String, Object> message(LlmMessage message) {
        return Map.of("role", message.role().name().toLowerCase(),
                "content", message.content().stream().map(Fixtures::block).toList());
    }

    private static Map<String, Object> block(ContentBlock block) {
        Map<String, Object> b = new LinkedHashMap<>();
        switch (block) {
            case ContentBlock.Text t -> {
                b.put("type", "text");
                b.put("text", t.text());
            }
            case ContentBlock.Thinking t -> {
                b.put("type", "thinking");
                b.put("thinking", t.thinking());
                b.put("signature", t.signature());
            }
            case ContentBlock.RedactedThinking r -> {
                b.put("type", "redacted_thinking");
                b.put("data", r.data());
            }
            case ContentBlock.ToolUse u -> {
                b.put("type", "tool_use");
                b.put("id", u.id());
                b.put("name", u.name());
                b.put("input", parse(u.inputJson()));
            }
            case ContentBlock.ToolResult r -> {
                b.put("type", "tool_result");
                b.put("tool_use_id", r.toolUseId());
                b.put("content", r.content());
                b.put("is_error", r.isError());
            }
        }
        return b;
    }

    private static Map<String, Object> tool(ToolDefinition tool) {
        return Map.of("name", tool.name(), "description", tool.description(),
                "input_schema", parse(tool.inputSchemaJson()), "strict", tool.strict());
    }

    // Files ------------------------------------------------------------------------------------------------------

    public Path path(String key) {
        return dir.resolve(key + ".json");
    }

    /** Writes the fixture for this request and response; atomic, LF line endings, trailing newline. */
    public Path write(LlmRequest request, LlmResponse response) {
        String key = key(request);
        ObjectNode file = CANONICAL.createObjectNode();
        file.put("format", FORMAT);
        file.put("key", key);
        file.set("request", canonical(request));
        file.set("response", CANONICAL.valueToTree(response(response)));
        Path target = path(key);
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, key, ".tmp");
            Files.writeString(tmp, CANONICAL.writeValueAsString(file).replace("\r\n", "\n") + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The recorded response for this request, without cost (the caller prices it); empty on a miss. */
    public Optional<Recorded> read(LlmRequest request) {
        Path file = path(key(request));
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        JsonNode root = CANONICAL.readTree(file.toFile());
        if (root.get("format").asInt() != FORMAT) {
            throw new IllegalStateException(file + " is fixture format " + root.get("format") + ", expected " + FORMAT
                    + ": re-record");
        }
        JsonNode r = root.get("response");
        List<ContentBlock> content = new ArrayList<>();
        for (JsonNode b : r.get("content")) {
            content.add(switch (b.get("type").asString()) {
                case "text" -> new ContentBlock.Text(b.get("text").asString());
                case "thinking" -> new ContentBlock.Thinking(b.get("thinking").asString(), b.get("signature").asString());
                case "redacted_thinking" -> new ContentBlock.RedactedThinking(b.get("data").asString());
                case "tool_use" -> new ContentBlock.ToolUse(b.get("id").asString(), b.get("name").asString(),
                        b.get("input_json").asString());
                default -> throw new IllegalStateException(file + ": unknown block " + b.get("type"));
            });
        }
        JsonNode u = r.get("usage");
        return Optional.of(new Recorded(r.get("id").asString(), r.get("model").asString(),
                StopReason.valueOf(r.get("stop_reason").asString()), content,
                new Usage(u.get("input_tokens").asLong(), u.get("output_tokens").asLong(),
                        u.get("cache_read_input_tokens").asLong(), u.get("cache_creation_input_tokens").asLong()),
                r.get("refusal_category").isNull() ? null : r.get("refusal_category").asString()));
    }

    /** A recorded response before pricing. */
    public record Recorded(String id, String model, StopReason stopReason, List<ContentBlock> content, Usage usage,
            String refusalCategory) {
    }

    private static Map<String, Object> response(LlmResponse response) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", response.id());
        r.put("model", response.model());
        r.put("stop_reason", response.stopReason().name());
        r.put("refusal_category", response.refusalCategory());
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", response.usage().inputTokens());
        usage.put("output_tokens", response.usage().outputTokens());
        usage.put("cache_read_input_tokens", response.usage().cacheReadInputTokens());
        usage.put("cache_creation_input_tokens", response.usage().cacheCreationInputTokens());
        r.put("usage", usage);
        List<Map<String, Object>> content = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            Map<String, Object> b = new LinkedHashMap<>();
            switch (block) {
                case ContentBlock.Text t -> {
                    b.put("type", "text");
                    b.put("text", t.text());
                }
                case ContentBlock.Thinking t -> {
                    b.put("type", "thinking");
                    b.put("thinking", t.thinking());
                    b.put("signature", t.signature());
                }
                case ContentBlock.RedactedThinking rt -> {
                    b.put("type", "redacted_thinking");
                    b.put("data", rt.data());
                }
                case ContentBlock.ToolUse use -> {
                    b.put("type", "tool_use");
                    b.put("id", use.id());
                    b.put("name", use.name());
                    b.put("input_json", use.inputJson()); // exact text: replay sends the same bytes to the tool
                }
                case ContentBlock.ToolResult tr -> throw new IllegalArgumentException("a response has no tool results");
            }
            content.add(b);
        }
        r.put("content", content);
        return r;
    }

    // JSON helpers -----------------------------------------------------------------------------------------------

    private static JsonNode parse(String json) {
        return CANONICAL.readTree(json);
    }

    /** A copy with every object's keys sorted, recursively (embedded schemas included). */
    private static JsonNode sorted(JsonNode node) {
        return CANONICAL.readTree(COMPACT.writeValueAsString(CANONICAL.treeToValue(node, Object.class)));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
