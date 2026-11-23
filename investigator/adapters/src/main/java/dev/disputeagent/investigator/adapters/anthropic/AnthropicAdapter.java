package dev.disputeagent.investigator.adapters.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.GatewayTimeoutException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.OverloadedException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.RequestTimeoutException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnprocessableEntityException;
import dev.disputeagent.investigator.application.port.llm.LlmCallException;
import dev.disputeagent.investigator.application.port.llm.LlmCallException.Kind;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.llm.LlmRequest;
import dev.disputeagent.investigator.application.port.llm.LlmResponse;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Objects;

/**
 * {@link LlmPort} on the Anthropic Java SDK: one non-streaming Messages API call per request. SDK types stay in this
 * package; failures leave as {@link LlmCallException} with a provider-neutral kind (most specific SDK exception
 * first), so retryable and non-retryable failures are told apart without SDK types.
 *
 * <p>Retries are configured in exactly one place: {@code ResilientLlmPort} around this adapter (ADR 0008). The SDK's
 * {@code maxRetries} is 0 in configuration.
 */
public final class AnthropicAdapter implements LlmPort {

    private final AnthropicClient client;
    private final PriceTable prices;

    public AnthropicAdapter(AnthropicClient client, PriceTable prices) {
        this.client = Objects.requireNonNull(client, "client");
        this.prices = Objects.requireNonNull(prices, "prices");
    }

    /**
     * A client for the given key. The key comes from the environment ({@code ANTHROPIC_API_KEY}), never from
     * configuration files; {@code baseUrl} is null for the real API.
     */
    public static AnthropicClient client(String apiKey, String baseUrl, Duration timeout, int maxRetries) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY is not set; use the replay adapter without a key");
        }
        var builder = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(maxRetries);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    @Override
    public LlmResponse send(LlmRequest request) {
        try {
            return AnthropicMapping.fromMessage(client.messages().create(AnthropicMapping.toParams(request)), prices);
        } catch (RateLimitException e) {
            throw failure(Kind.RATE_LIMITED, e);
        } catch (OverloadedException e) {
            throw failure(Kind.OVERLOADED, e);
        } catch (RequestTimeoutException | GatewayTimeoutException e) {
            throw failure(Kind.TIMEOUT, e);
        } catch (InternalServerException e) { // includes ServiceUnavailableException; Overloaded is caught above
            throw failure(Kind.SERVER_ERROR, e);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw failure(Kind.AUTHENTICATION, e);
        } catch (BadRequestException | UnprocessableEntityException | NotFoundException e) {
            throw failure(Kind.BAD_REQUEST, e);
        } catch (AnthropicServiceException e) {
            throw failure(e.statusCode() >= 500 ? Kind.SERVER_ERROR : Kind.OTHER, e);
        } catch (AnthropicRetryableException e) {
            throw failure(Kind.CONNECTION, e);
        } catch (AnthropicIoException e) {
            throw failure(e.getCause() instanceof InterruptedIOException ? Kind.TIMEOUT : Kind.CONNECTION, e);
        } catch (AnthropicException e) {
            throw failure(Kind.OTHER, e);
        }
    }

    private static LlmCallException failure(Kind kind, AnthropicException e) {
        String detail = e instanceof AnthropicServiceException s
                ? "HTTP " + s.statusCode() + s.requestId().map(id -> ", request " + id).orElse("")
                : e.getClass().getSimpleName();
        return new LlmCallException(kind, detail, e);
    }
}
