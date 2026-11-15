package dev.disputeagent.investigator.application.port.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LlmRequestTest {

    private static final List<LlmMessage> MESSAGES = List.of(LlmMessage.userText("case C-1"));

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, "d", "{\"type\":\"object\"}", true);
    }

    private static LlmRequest request(List<SystemBlock> system, List<ToolDefinition> tools) {
        return new LlmRequest("model-from-config", Effort.MEDIUM, 16000, system, MESSAGES, tools, null);
    }

    @Test
    void sortsToolsByNameSoTheCachePrefixIsStable() {
        var request = request(List.of(), List.of(tool("get_transactions"), tool("get_cardholder"), tool("a_tool")));

        assertThat(request.tools()).extracting(ToolDefinition::name)
                .containsExactly("a_tool", "get_cardholder", "get_transactions");
    }

    @Test
    void rejectsDuplicateToolNames() {
        assertThatThrownBy(() -> request(List.of(), List.of(tool("x"), tool("x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate tool name");
    }

    @Test
    void allowsAtMostOneCacheBreakpoint() {
        var stable = new SystemBlock("policy", true);

        assertThat(request(List.of(stable, new SystemBlock("x", false)), List.of()).system()).hasSize(2);
        assertThatThrownBy(() -> request(List.of(stable, stable), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyConversationAndNonPositiveMaxTokens() {
        assertThatThrownBy(() -> new LlmRequest("m", Effort.LOW, 16000, List.of(), List.of(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmRequest("m", Effort.LOW, 0, List.of(), MESSAGES, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effortParsesConfigurationValues() {
        assertThat(Effort.fromConfig(" Medium ")).isEqualTo(Effort.MEDIUM);
        assertThat(Effort.XHIGH.wireValue()).isEqualTo("xhigh");
        assertThatThrownBy(() -> Effort.fromConfig("extreme")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responseExposesToolUsesInOrder() {
        var first = new ContentBlock.ToolUse("t1", "get_cardholder", "{}");
        var second = new ContentBlock.ToolUse("t2", "get_transactions", "{}");
        var response = new LlmResponse("msg_1", "model-that-answered", StopReason.TOOL_USE,
                List.of(new ContentBlock.Thinking("", "sig"), first, new ContentBlock.Text("x"), second),
                new Usage(10, 5, 0, 0), null);

        assertThat(response.toolUses()).containsExactly(first, second);
        assertThat(response.asAssistantMessage().content()).isEqualTo(response.content());
    }
}
