package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.resilience.ResilientLlmPort;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything under {@code investigator.*} in application.yml (ADR 0001: model ids, effort, token caps and prices in
 * configuration, never in code). Bound once at start-up; a missing value fails start-up.
 */
@ConfigurationProperties("investigator")
public record InvestigatorProperties(Llm llm, Mcp mcp, Kafka kafka, Budget budget, Policy policy, String skillsDir,
        Approval approval, Reviewer reviewer, Outbox outbox, ResilientLlmPort.Settings resilience, Replay replay) {

    // Directories are bound as strings and read with Path.of: Spring's String-to-Path conversion goes through the
    // resource loader, which rejects relative paths with '..' in a web application.

    public Path skillsPath() {
        return Path.of(skillsDir);
    }

    /**
     * @param adapter     {@code anthropic} (live), {@code recording} (live, writes fixtures) or {@code replay}
     * @param live        which live model to use when the adapter needs one: {@code anthropic}; tests supply their own
     * @param apiKey      from {@code ANTHROPIC_API_KEY}; needed only by a live adapter
     * @param maxRetries  retries in the SDK: 0, because Resilience4j retries around the port (ADR 0008)
     */
    public record Llm(String adapter, String live, Model investigator, Model triage, Prices prices,
            String fixturesDir, String apiKey, String baseUrl, int maxRetries, Duration timeout) {

        public Path fixturesPath() {
            return Path.of(fixturesDir);
        }
    }

    public record Model(String model, String effort, int maxTokens) {
    }

    /** Dollars per million tokens, by model id. */
    public record Prices(String version, Map<String, Price> models) {
    }

    public record Price(double input, double output, double cacheRead, double cacheWrite) {
    }

    /** The ledger and policy servers; the actions server is configured but never connected in M1. */
    public record Mcp(URI ledgerUrl, URI policyUrl, URI actionsUrl, Duration connectTimeout, Duration requestTimeout,
            int maxResponseBytes) {
    }

    /**
     * The approval-token keys (ADR 0005), base64 PKCS#8 and X.509, from the config tree the compose stack mounts (or
     * the environment). Both absent: no signer, approvals cannot be signed. Never in application.yml or the repository.
     */
    public record Approval(String signingKey, String verifyKey) {
        public boolean configured() {
            return signingKey != null && !signingKey.isBlank() && verifyKey != null && !verifyKey.isBlank();
        }
    }

    /** The outbox relay (ADR 0008): how often it polls, and how many rows one batch sends. */
    public record Outbox(Duration pollInterval, int batchSize) {
    }

    /** The reviewer API's static key (task 2.7), a stand-in for single sign-on; blank disables the API. */
    public record Reviewer(String apiKey) {
    }

    public record Kafka(String openedTopic, String decidedTopic, String approvalRequestedTopic, String replayTopic,
            String dlqTopic, String groupId, int partitions, Duration publishTimeout) {
    }

    /**
     * The replay of parked cases (ADR 0008): how often the sweep runs and how many cases it takes, and the policy (cap,
     * backoff, in-flight hold).
     */
    public record Replay(Duration sweepInterval, int batchSize, int maxReplays, Duration firstDelay, Duration maxDelay,
            Duration inFlight) {
    }

    public record Budget(int maxModelCalls, int maxToolCalls, long maxTokens, Duration maxWallClock,
            int decisionRetries) {
    }

    /** Must match data/datagen/policy.py and the policy corpus. */
    public record Policy(int timeLimitDays, long provisionalCreditLimitMinor) {
    }
}
