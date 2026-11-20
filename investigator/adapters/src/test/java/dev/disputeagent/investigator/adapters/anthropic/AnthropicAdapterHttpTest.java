package dev.disputeagent.investigator.adapters.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.disputeagent.investigator.application.port.llm.LlmCallException;
import dev.disputeagent.investigator.application.port.llm.LlmCallException.Kind;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The whole path through the SDK's HTTP client against a local stub: headers, body, response, error mapping. */
class AnthropicAdapterHttpTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> credentials = new AtomicReference<>();
    private final AtomicReference<String> version = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    private AnthropicAdapter stub(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // SDK 3.8.0 (API version 2026-10-21) sends the key as a bearer token; older SDKs used x-api-key.
            credentials.set(exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + exchange.getRequestHeaders().getFirst("x-api-key"));
            version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.getResponseHeaders().add("request-id", "req_test");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var client = AnthropicAdapter.client("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(5), 0);
        return new AnthropicAdapter(client, TestPrices.TABLE);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTheMappedRequestAndMapsTheAnswer() throws Exception {
        LlmResponse response = stub(200, AnthropicMappingTest.TOOL_USE_RESPONSE).send(AnthropicMappingTest.request());

        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(credentials.get()).contains("test-key");
        assertThat(version.get()).isEqualTo("2026-10-21");
        JsonNode sent = JsonMapper.builder().build().readTree(body.get());
        assertThat(sent.at("/system/0/cache_control/type").asString()).isEqualTo("ephemeral");
        assertThat(sent.at("/output_config/format/type").asString()).isEqualTo("json_schema");
    }

    @ParameterizedTest
    @CsvSource({
        "429, rate_limit_error, RATE_LIMITED",
        "529, overloaded_error, OVERLOADED",
        "500, api_error, SERVER_ERROR",
        "400, invalid_request_error, BAD_REQUEST",
        "401, authentication_error, AUTHENTICATION",
    })
    void httpErrorsBecomeProviderNeutralKinds(int status, String type, Kind kind) throws Exception {
        var adapter = stub(status, "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"m\"}}");

        assertThatThrownBy(() -> adapter.send(AnthropicMappingTest.request()))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.kind()).isEqualTo(kind);
                    assertThat(e.getMessage()).contains("HTTP " + status).doesNotContain("test-key");
                });
        assertThat(requests.get()).as("retries are 0 here; one place for retries").isEqualTo(1);
    }

    @Test
    void anUnreachableServerIsAConnectionFailure() {
        var client = AnthropicAdapter.client("test-key", "http://127.0.0.1:1", Duration.ofSeconds(2), 0);
        var adapter = new AnthropicAdapter(client, TestPrices.TABLE);

        assertThatThrownBy(() -> adapter.send(AnthropicMappingTest.request()))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.kind().retryable()).isTrue());
    }

    @Test
    void aMissingKeyFailsAtStartUpNotAtTheFirstCall() {
        assertThatThrownBy(() -> AnthropicAdapter.client(" ", null, Duration.ofSeconds(1), 0))
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }
}
