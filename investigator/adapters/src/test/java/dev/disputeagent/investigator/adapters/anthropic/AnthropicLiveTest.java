package dev.disputeagent.investigator.adapters.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.adapters.json.JacksonJsonAdapter;
import dev.disputeagent.investigator.application.port.json.Parsed;
import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.Effort;
import dev.disputeagent.investigator.application.port.llm.LlmMessage;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.OutputFormat;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.SystemBlock;
import dev.disputeagent.investigator.application.triage.Triage;
import dev.disputeagent.investigator.application.triage.TriageDispute;
import dev.disputeagent.investigator.domain.DecisionOutcome;
import dev.disputeagent.investigator.domain.decision.Decision;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Two small live calls that prove the request shape against the real API: the triage schema on the cheap model and
 * the decision schema (with {@code const} and {@code anyOf}) on the investigator model. Off by default; CI never
 * runs it. Costs well under one cent.
 *
 * <pre>ANTHROPIC_LIVE_TEST=1 ANTHROPIC_API_KEY=... ./mvnw -pl adapters test -Dtest=AnthropicLiveTest</pre>
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_LIVE_TEST", matches = "1")
class AnthropicLiveTest {

    private final AnthropicAdapter adapter = new AnthropicAdapter(
            AnthropicAdapter.client(System.getenv("ANTHROPIC_API_KEY"), null, Duration.ofMinutes(2), 2),
            TestPrices.TABLE);
    private final JacksonJsonAdapter json = new JacksonJsonAdapter();

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String lastText(LlmResponse response) {
        return response.content().stream().filter(ContentBlock.Text.class::isInstance)
                .map(b -> ((ContentBlock.Text) b).text()).reduce((a, b) -> b).orElse("");
    }

    @Test
    void triageSchemaOnTheCheapModel() {
        LlmResponse response = adapter.send(new LlmRequest(env("TRIAGE_MODEL", "claude-haiku-5-5"), Effort.LOW, 1_000,
                List.of(new SystemBlock("Classify the card dispute in the user message.", true)),
                List.of(LlmMessage.userText("I was charged twice for the same coffee this morning.")), List.of(),
                new OutputFormat(TriageDispute.schema())));

        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        Parsed<Triage> triage = json.triage(lastText(response));
        assertThat(triage.ok()).as(String.valueOf(triage.errors())).isTrue();
        assertThat(response.cost().microUsd()).isPositive();
    }

    @Test
    void decisionSchemaOnTheInvestigatorModel() {
        LlmResponse response = adapter.send(new LlmRequest(env("INVESTIGATOR_MODEL", "claude-opus-5-5"), Effort.LOW,
                4_000, List.of(new SystemBlock("You test a decision format. Escalate: there are no records.", true)),
                List.of(LlmMessage.userText("Decide dispute TEST-1. Cite the policy section escalation.")),
                List.of(), new OutputFormat(Decision.jsonSchema())));

        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        Parsed<Decision> decision = json.decision(lastText(response));
        assertThat(decision.ok()).as(String.valueOf(decision.errors())).isTrue();
        assertThat(decision.value().outcome()).isEqualTo(DecisionOutcome.ESCALATE);
    }
}
