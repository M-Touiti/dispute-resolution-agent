package dev.disputeagent.investigator.adapters.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.adapters.anthropic.PriceTable;
import dev.disputeagent.investigator.application.port.llm.ContentBlock;
import dev.disputeagent.investigator.application.port.llm.Effort;
import dev.disputeagent.investigator.application.port.llm.LlmCallException;
import dev.disputeagent.investigator.application.port.llm.LlmCost;
import dev.disputeagent.investigator.application.port.llm.LlmMessage;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.OutputFormat;
import dev.disputeagent.investigator.application.port.llm.Role;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.SystemBlock;
import dev.disputeagent.investigator.application.port.llm.ToolDefinition;
import dev.disputeagent.investigator.application.port.llm.Usage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordReplayTest {

    static final PriceTable PRICES = new PriceTable("test-prices",
            Map.of("claude-opus-5-5", PriceTable.ModelPrice.perMillion(4.00, 20.00, 0.20, 5.00)));

    @TempDir
    Path dir;

    static LlmRequest request(String schema, String toolInput, String prompt, Effort effort) {
        return new LlmRequest("claude-opus-5-5", effort, 16_000, List.of(new SystemBlock(prompt, true)),
                List.of(LlmMessage.userText("Investigate DSP-000001."),
                        new LlmMessage(Role.ASSISTANT, List.of(new ContentBlock.Thinking("", "sig"),
                                new ContentBlock.ToolUse("toolu_1", "get_card", toolInput))),
                        new LlmMessage(Role.USER, List.of(new ContentBlock.ToolResult("toolu_1", "{}", false)))),
                List.of(new ToolDefinition("get_card", "Get a card.", schema, true)),
                new OutputFormat("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
    }

    static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"card_id\"],"
            + "\"properties\":{\"card_id\":{\"type\":\"string\"}},\"additionalProperties\":false}";

    static LlmRequest request() {
        return request(SCHEMA, "{\"card_id\":\"CRD-000042\"}", "You investigate.", Effort.MEDIUM);
    }

    static final LlmResponse RESPONSE = new LlmResponse("msg_1", "claude-opus-5-5", StopReason.TOOL_USE,
            List.of(new ContentBlock.Thinking("", "sig-2"), new ContentBlock.Text("Checking."),
                    new ContentBlock.ToolUse("toolu_2", "get_card", "{\"card_id\":\"CRD-000042\", \"x\":1}")),
            new Usage(1_000, 100, 5_000, 0), null, new LlmCost(999, "live-prices"));

    /** A live adapter that counts calls. */
    static final class Live implements LlmPort {
        final AtomicInteger calls = new AtomicInteger();
        RuntimeException failure;

        @Override
        public LlmResponse send(LlmRequest request) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return RESPONSE;
        }
    }

    // The key ----------------------------------------------------------------------------------------------------

    @Test
    void theKeyIgnoresFormattingAndKeyOrderInEmbeddedJson() {
        String reordered = "{ \"additionalProperties\": false, \"properties\": {\"card_id\": {\"type\": \"string\"}},"
                + " \"type\": \"object\", \"required\": [\"card_id\"] }";
        LlmRequest same = request(reordered, "{ \"card_id\" : \"CRD-000042\" }", "You investigate.", Effort.MEDIUM);

        assertThat(Fixtures.key(same)).isEqualTo(Fixtures.key(request())).hasSize(64);
    }

    @Test
    void theKeyChangesWithThePromptTheEffortTheToolsOrTheConversation() {
        String base = Fixtures.key(request());

        assertThat(Fixtures.key(request(SCHEMA, "{\"card_id\":\"CRD-000042\"}", "You investigate!", Effort.MEDIUM)))
                .isNotEqualTo(base);
        assertThat(Fixtures.key(request(SCHEMA, "{\"card_id\":\"CRD-000042\"}", "You investigate.", Effort.HIGH)))
                .isNotEqualTo(base);
        assertThat(Fixtures.key(request(SCHEMA.replace("string", "integer"), "{\"card_id\":\"CRD-000042\"}",
                "You investigate.", Effort.MEDIUM))).isNotEqualTo(base);
        assertThat(Fixtures.key(request(SCHEMA, "{\"card_id\":\"CRD-000043\"}", "You investigate.", Effort.MEDIUM)))
                .isNotEqualTo(base);
    }

    // Record and replay ------------------------------------------------------------------------------------------

    @Test
    void whatIsRecordedReplaysExactlyWithTheCurrentPrices() {
        var live = new Live();
        new RecordingAdapter(live, new Fixtures(dir)).send(request());

        LlmResponse replayed = new ReplayAdapter(new Fixtures(dir), PRICES).send(request());

        assertThat(live.calls.get()).isEqualTo(1);
        assertThat(replayed.id()).isEqualTo(RESPONSE.id());
        assertThat(replayed.model()).isEqualTo(RESPONSE.model());
        assertThat(replayed.stopReason()).isEqualTo(RESPONSE.stopReason());
        assertThat(replayed.content()).isEqualTo(RESPONSE.content()); // tool input text byte for byte
        assertThat(replayed.usage()).isEqualTo(RESPONSE.usage());
        // 1,000 x 4,000 + 100 x 20,000 + 5,000 x 200 nano-dollars = 7,000 micro-dollars, priced at replay time
        assertThat(replayed.cost()).isEqualTo(new LlmCost(7_000, "test-prices"));
    }

    @Test
    void reRecordingTheSameResponseWritesTheSameBytes() throws IOException {
        var fixtures = new Fixtures(dir);
        Path first = fixtures.write(request(), RESPONSE);
        String before = Files.readString(first);
        fixtures.write(request(), RESPONSE);

        assertThat(Files.readString(first)).isEqualTo(before);
        assertThat(before).endsWith("}\n").doesNotContain("\r");
        assertThat(before).contains("\"key\" : \"" + Fixtures.key(request()) + "\"", "You investigate.");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly(Fixtures.key(request()) + ".json");
        }
    }

    @Test
    void aMissNeverCallsAnythingAndNamesTheFixture() {
        var replay = new ReplayAdapter(new Fixtures(dir), PRICES);

        assertThatThrownBy(() -> replay.send(request()))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.kind()).isEqualTo(LlmCallException.Kind.FIXTURE_MISSING);
                    assertThat(e.kind().retryable()).isFalse();
                    assertThat(e.getMessage()).contains(Fixtures.key(request()), "re-record");
                });
    }

    @Test
    void aChangedRequestMissesInsteadOfReplayingAStaleAnswer() {
        new RecordingAdapter(new Live(), new Fixtures(dir)).send(request());
        var replay = new ReplayAdapter(new Fixtures(dir), PRICES);

        assertThatThrownBy(() -> replay.send(request(SCHEMA, "{\"card_id\":\"CRD-000042\"}", "Changed prompt.",
                Effort.MEDIUM))).isInstanceOf(LlmCallException.class);
    }

    @Test
    void aFailedLiveCallRecordsNothing() throws IOException {
        var live = new Live();
        live.failure = new LlmCallException(LlmCallException.Kind.OVERLOADED, "529", null);

        assertThatThrownBy(() -> new RecordingAdapter(live, new Fixtures(dir)).send(request()))
                .isSameAs(live.failure);
        try (var files = Files.list(dir)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void anOldFixtureFormatIsRejected() throws IOException {
        var fixtures = new Fixtures(dir);
        Path file = fixtures.write(request(), RESPONSE);
        Files.writeString(file, Files.readString(file).replaceFirst("\"format\" : 1", "\"format\" : 0"));

        assertThatThrownBy(() -> fixtures.read(request())).hasMessageContaining("re-record");
    }
}
