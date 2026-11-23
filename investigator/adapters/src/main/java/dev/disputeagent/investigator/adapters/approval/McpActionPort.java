package dev.disputeagent.investigator.adapters.approval;

import dev.disputeagent.investigator.application.port.approval.ActionPort;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ActionPort} on actions-mcp (ADR 0005). It is given an MCP client that knows only the actions server, separate
 * from the client the investigation loop uses, so the model's tools cannot reach a write even by mistake.
 *
 * <p>The arguments are built by one function from the action, for the hash and for the call alike, so the hash in the
 * token is the hash of exactly what is sent ({@code docs/approval-token.md}, "Canonical arguments").
 */
public final class McpActionPort implements ActionPort, AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final McpToolPort actions;

    public McpActionPort(McpToolPort actionsOnly) {
        this.actions = actionsOnly;
    }

    @Override
    public String argumentsSha256(CaseId disputeId, ProposedAction action) {
        return Jcs.sha256Hex(arguments(disputeId, action));
    }

    @Override
    public Result execute(CaseId disputeId, ProposedAction action, String approvalToken) {
        ObjectNode call = arguments(disputeId, action).put("approval_token", approvalToken);
        McpToolResult result;
        try {
            result = actions.call(new McpToolCall(McpServer.ACTIONS, action.toolName(), JSON.writeValueAsString(call)));
        } catch (RuntimeException e) {
            throw new Unavailable(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        return new Result(!result.isError(), result.content());
    }

    /** Closes the actions-only client. */
    @Override
    public void close() throws Exception {
        if (actions instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    /** The tool's arguments without {@code approval_token}, as the token binds them. */
    static ObjectNode arguments(CaseId disputeId, ProposedAction action) {
        ObjectNode n = JSON.createObjectNode().put("dispute_id", disputeId.value());
        switch (action) {
            case ProposedAction.IssueProvisionalCredit credit -> n
                    .put("amount_minor", credit.amount().amountMinor())
                    .put("currency", credit.amount().currency());
            case ProposedAction.RequestMerchantEvidence request -> {
                var evidence = n.putArray("evidence");
                request.evidence().forEach(kind -> evidence.add(kind.name()));
                n.put("note", request.note());
            }
            case ProposedAction.CloseDispute close -> {
                n.put("reason", close.reason());
                close.policySectionIds().forEach(n.putArray("policy_section_ids")::add);
            }
        }
        return n;
    }
}
