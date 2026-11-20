package dev.disputeagent.investigator.application.investigation;

import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditEvent.Caller;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.domain.CaseId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * One MCP call, timed. A transport failure becomes a tool error ({@code UNAVAILABLE}), never an exception into the
 * loop: the model sees it with {@code is_error} and can work around it. Thread-safe; auditing is left to the caller,
 * which records events in order on the loop's thread.
 */
final class ToolInvoker {

    /** The result of a call and the audit event that describes it. */
    record Invocation(McpToolResult result, AuditEvent.ToolCalled audit) {
    }

    private final McpToolPort mcp;
    private final Clock clock;

    ToolInvoker(McpToolPort mcp, Clock clock) {
        this.mcp = mcp;
        this.clock = clock;
    }

    Invocation invoke(CaseId caseId, Caller caller, McpToolCall call) {
        Instant start = clock.instant();
        McpToolResult result;
        try {
            result = mcp.call(call);
        } catch (RuntimeException e) {
            result = new McpToolResult("UNAVAILABLE: " + call.server().name().toLowerCase() + " could not be reached",
                    true);
        }
        Duration latency = Duration.between(start, clock.instant());
        return new Invocation(result, new AuditEvent.ToolCalled(caseId, start, caller, call.server(), call.toolName(),
                sha256(call.argumentsJson()), result.isError(), latency));
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
