package dev.disputeagent.investigator.adapters.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.application.port.approval.ActionPort;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.application.port.mcp.McpTool;
import dev.disputeagent.investigator.application.port.mcp.McpToolCall;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolResult;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.decision.EvidenceKind;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The arguments the investigator sends are the ones docs/approval-token.md and the shared vectors describe. */
class McpActionPortTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonNode VECTORS = JSON.readTree(ApprovalTokenVectorsTest.VECTORS.toFile());

    static JsonNode vector(String name) {
        for (JsonNode a : VECTORS.get("arguments")) {
            if (a.get("name").stringValue().equals(name)) {
                return a;
            }
        }
        throw new AssertionError("no vector " + name);
    }

    static final class FakeActions implements McpToolPort {
        final List<McpToolCall> calls = new ArrayList<>();
        McpToolResult answer = new McpToolResult("{\"status\":\"DONE\"}", false);
        RuntimeException failure;

        @Override
        public List<McpTool> listTools(McpServer server) {
            throw new AssertionError("the action port never lists tools");
        }

        @Override
        public McpToolResult call(McpToolCall call) {
            calls.add(call);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    @Test
    void eachActionsArgumentsAreTheVectorsArguments() {
        var credit = new ProposedAction.IssueProvisionalCredit(new Money(110056, "EUR"));
        var evidence = new ProposedAction.RequestMerchantEvidence(
                List.of(EvidenceKind.RECEIPT, EvidenceKind.PROOF_OF_DELIVERY),
                "Please send the signed delivery record for order 4471.");
        var close = new ProposedAction.CloseDispute(
                "Chip and PIN at a terminal in the cardholder's city; the card was present.",
                List.of("unauthorised-chip-and-pin", "time-limits"));
        var port = new McpActionPort(new FakeActions());

        assertThat(Jcs.canonical(McpActionPort.arguments(new CaseId("DSP-000018"), credit)))
                .isEqualTo(vector("issue_provisional_credit").get("canonical").stringValue());
        assertThat(port.argumentsSha256(new CaseId("DSP-000018"), credit))
                .isEqualTo(vector("issue_provisional_credit").get("sha256").stringValue());
        assertThat(port.argumentsSha256(new CaseId("DSP-000018"), evidence))
                .isEqualTo(vector("request_merchant_evidence").get("sha256").stringValue());
        assertThat(port.argumentsSha256(new CaseId("DSP-000003"), close))
                .isEqualTo(vector("close_dispute").get("sha256").stringValue());
    }

    @Test
    void theCallGoesToActionsMcpWithTheHashedArgumentsAndTheToken() {
        var actions = new FakeActions();
        var port = new McpActionPort(actions);
        var credit = new ProposedAction.IssueProvisionalCredit(new Money(110056, "EUR"));

        ActionPort.Result result = port.execute(new CaseId("DSP-000018"), credit, "h.p.s");

        assertThat(result.ok()).isTrue();
        assertThat(result.detail()).isEqualTo("{\"status\":\"DONE\"}");
        McpToolCall call = actions.calls.getFirst();
        assertThat(call.server()).isEqualTo(McpServer.ACTIONS);
        assertThat(call.toolName()).isEqualTo("issue_provisional_credit");
        JsonNode sent = JSON.readTree(call.argumentsJson());
        assertThat(sent.get("approval_token").stringValue()).isEqualTo("h.p.s");
        var withoutToken = sent.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) withoutToken).remove("approval_token");
        assertThat(Jcs.sha256Hex(withoutToken)).as("the hash in the token is the hash of what is sent")
                .isEqualTo(port.argumentsSha256(new CaseId("DSP-000018"), credit));
    }

    @Test
    void aRefusalIsAFailedResultAndAnUnreachableServerIsUnavailable() {
        var actions = new FakeActions();
        var port = new McpActionPort(actions);
        var close = new ProposedAction.CloseDispute("r", List.of("s"));

        actions.answer = new McpToolResult("APPROVAL_REJECTED: DISPUTE_CLOSED: x. No action was taken.", true);
        assertThat(port.execute(new CaseId("DSP-1"), close, "t").ok()).isFalse();

        actions.failure = new IllegalStateException("connection refused");
        assertThatThrownBy(() -> port.execute(new CaseId("DSP-1"), close, "t"))
                .isInstanceOf(ActionPort.Unavailable.class).hasMessageContaining("connection refused");
    }

    @Test
    void anEmptyEvidenceListIsStillSent() {
        var evidence = new ProposedAction.RequestMerchantEvidence(List.of(), "n");

        assertThat(McpActionPort.arguments(new CaseId("DSP-1"), evidence).get("evidence").isArray()).isTrue();
    }
}
