package dev.disputeagent.ledger.mcp;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The six read-only ledger tools. Input schemas live in {@code src/main/resources/tools/<name>.schema.json} and are
 * written to be usable as strict Messages API tools: {@code additionalProperties: false}, bounded strings and pages.
 *
 * <p>Changing a name, description or schema changes {@code tools/list}: update the contract file in the same pull
 * request and re-record the investigator's fixtures.
 */
public enum LedgerTools {

    SEARCH_TRANSACTIONS("search_transactions", "Search transactions",
            "List posted transactions (purchases and refunds) on one card, newest first. Filter by date range, "
                    + "merchant and kind. At most 50 per page; pass next_page_token to continue. Amounts are in "
                    + "minor units (cents) of EUR."),
    GET_TRANSACTION("get_transaction", "Get transaction",
            "Get one transaction by id, including its authorisation id and, for a refund, the transaction it "
                    + "refunds."),
    GET_CARDHOLDER("get_cardholder", "Get cardholder",
            "Get a cardholder's profile: name, home city and country, and customer-since date."),
    GET_CARD("get_card", "Get card",
            "Get a card by id: owning cardholder, product, status, issue date, expiry and the last four digits. "
                    + "Full card numbers are never returned."),
    GET_MERCHANT("get_merchant", "Get merchant",
            "Get a merchant by id: name, category, city, country, statement descriptor, and whether it sells "
                    + "online and bills on a recurring basis."),
    GET_AUTHORISATIONS("get_authorisations", "Get authorisations",
            "List authorisation attempts on one card, newest first, including declines: entry mode, 3-D Secure "
                    + "result, CVV result, PIN verification and the fraud flag. Filter by authorisation id, date "
                    + "range, merchant and response. At most 50 per page.");

    private final String toolName;
    private final String title;
    private final String description;

    LedgerTools(String toolName, String title, String description) {
        this.toolName = toolName;
        this.title = title;
        this.description = description;
    }

    public String toolName() {
        return toolName;
    }

    /** The JSON Schema of the tool's input, as stored on the classpath. */
    public String inputSchemaJson() {
        String path = "/tools/" + toolName + ".schema.json";
        try (InputStream in = LedgerTools.class.getResourceAsStream(path)) {
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
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
    }

    private static final Logger log = LoggerFactory.getLogger(LedgerTools.class);

    /** Tool results: snake_case fields, ISO-8601 dates and instants. Not a bean, so Boot keeps its own mapper. */
    static final JsonMapper RESULT_JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    /**
     * Tool specifications sorted by name. A result is the JSON of the handler's answer as text content; a
     * {@link ToolError} becomes {@code isError: true} with its code; a database failure becomes {@code UNAVAILABLE}
     * without details, which go to the log.
     */
    static List<SyncToolSpecification> specifications(McpJsonMapper jsonMapper, LedgerToolHandlers handlers) {
        return Arrays.stream(values())
                .sorted(Comparator.comparing(LedgerTools::toolName))
                .map(t -> SyncToolSpecification.builder()
                        .tool(t.tool(jsonMapper))
                        .callHandler((exchange, request) -> {
                            try {
                                Object result = handlers.handle(t, request.arguments());
                                return CallToolResult.builder()
                                        .addTextContent(RESULT_JSON.writeValueAsString(result))
                                        .build();
                            } catch (ToolError e) {
                                return error(e.text());
                            } catch (DataAccessException e) {
                                log.error("{} failed", t.toolName(), e);
                                return error("UNAVAILABLE: the ledger could not be read; try again later");
                            }
                        })
                        .build())
                .toList();
    }

    private static CallToolResult error(String text) {
        return CallToolResult.builder().addTextContent(text).isError(true).build();
    }
}
