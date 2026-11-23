package dev.disputeagent.investigator.adapters.otel;

import dev.disputeagent.investigator.application.port.mcp.McpTool;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A span and a timer per tool call (ADR 0008), tagged {@code server}, {@code tool} and {@code outcome} ({@code ok},
 * {@code error} for a tool-level error, {@code unavailable} when the server could not be reached): the tool error rate
 * by server and tool is a ratio of the timer's counts. The arguments never appear, only their SHA-256 on the span, as
 * in the audit log.
 */
public final class ObservedMcpToolPort implements McpToolPort, AutoCloseable {

    public static final String NAME = "dispute.tool.call";

    private final McpToolPort delegate;
    private final ObservationRegistry observations;

    public ObservedMcpToolPort(McpToolPort delegate, ObservationRegistry observations) {
        this.delegate = delegate;
        this.observations = observations;
    }

    @Override
    public List<McpTool> listTools(McpServer server) {
        return delegate.listTools(server);
    }

    @Override
    public McpToolResult call(McpToolCall call) {
        Observation observation = Observation.createNotStarted(NAME, observations)
                .contextualName("tool " + call.toolName())
                .lowCardinalityKeyValue("server", call.server().name().toLowerCase())
                .lowCardinalityKeyValue("tool", call.toolName())
                .lowCardinalityKeyValue("outcome", "ok")
                .highCardinalityKeyValue("arguments.sha256", sha256(call.argumentsJson()))
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            McpToolResult result = delegate.call(call);
            if (result.isError()) {
                observation.lowCardinalityKeyValue("outcome", "error");
            }
            return result;
        } catch (RuntimeException e) {
            observation.lowCardinalityKeyValue("outcome", "unavailable").error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
