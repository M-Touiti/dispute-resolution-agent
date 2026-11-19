package dev.disputeagent.actions.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The three write tools. Each one requires an {@code approval_token}, and this server, not the client, decides
 * whether the token is valid (ADR 0005, M2). Until token verification exists, every call is rejected and nothing
 * changes.
 *
 * <p>Input schemas live in {@code src/main/resources/tools/<name>.schema.json} and stay usable as strict Messages API
 * tools. Changing a name, description or schema changes {@code tools/list}: update the contract file in the same pull
 * request and re-record the investigator's fixtures.
 */
public enum ActionTools {

    CLOSE_DISPUTE("close_dispute", "Close dispute", true,
            "Close a dispute as declined, with the reason and the policy sections that support it. The cardholder "
                    + "is notified. Requires an approval_token for exactly this dispute and these arguments."),
    ISSUE_PROVISIONAL_CREDIT("issue_provisional_credit", "Issue provisional credit", false,
            "Credit the cardholder's account provisionally for the disputed amount while the dispute is resolved. "
                    + "Amounts are in minor units (cents) of EUR and may not exceed the disputed amount. Requires an "
                    + "approval_token for exactly this dispute and these arguments."),
    REQUEST_MERCHANT_EVIDENCE("request_merchant_evidence", "Request merchant evidence", false,
            "Ask the merchant, through the card scheme, for the listed evidence about the disputed transaction. "
                    + "Requires an approval_token for exactly this dispute and these arguments.");

    /** Every call is refused before M2; the text says plainly that nothing happened. */
    static final String APPROVAL_REJECTED =
            "APPROVAL_REJECTED: approval tokens cannot be verified until milestone M2. No action was taken.";

    static final String APPROVAL_TOKEN = "approval_token";

    private final String toolName;
    private final String title;
    private final boolean destructive;
    private final String description;

    ActionTools(String toolName, String title, boolean destructive, String description) {
        this.toolName = toolName;
        this.title = title;
        this.destructive = destructive;
        this.description = description;
    }

    public String toolName() {
        return toolName;
    }

    /** The JSON Schema of the tool's input, as stored on the classpath. */
    public String inputSchemaJson() {
        String path = "/tools/" + toolName + ".schema.json";
        try (InputStream in = ActionTools.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing tool schema " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Tool tool(McpJsonMapper jsonMapper) {
        return Tool.builder(toolName, jsonMapper, inputSchemaJson())
                .title(title)
                .description(description)
                .annotations(ToolAnnotations.builder()
                        .title(title)
                        .readOnlyHint(false)
                        .destructiveHint(destructive)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
                .build();
    }

    /** Tool specifications sorted by name, each with a handler that rejects every call until M2. */
    static List<SyncToolSpecification> specifications(McpJsonMapper jsonMapper) {
        return Arrays.stream(values())
                .sorted(Comparator.comparing(ActionTools::toolName))
                .map(t -> SyncToolSpecification.builder()
                        .tool(t.tool(jsonMapper))
                        .callHandler((exchange, request) -> CallToolResult.builder()
                                .addTextContent(APPROVAL_REJECTED)
                                .isError(true)
                                .build())
                        .build())
                .toList();
    }
}
