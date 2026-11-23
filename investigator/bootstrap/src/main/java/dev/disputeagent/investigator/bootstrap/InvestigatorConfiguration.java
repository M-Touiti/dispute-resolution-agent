package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.anthropic.PriceTable;
import dev.disputeagent.investigator.adapters.fixtures.Fixtures;
import dev.disputeagent.investigator.adapters.fixtures.RecordingAdapter;
import dev.disputeagent.investigator.adapters.fixtures.ReplayAdapter;
import dev.disputeagent.investigator.adapters.json.JacksonJsonAdapter;
import dev.disputeagent.investigator.adapters.otel.MeteredAuditLog;
import dev.disputeagent.investigator.adapters.otel.ObservedLlmPort;
import dev.disputeagent.investigator.adapters.resilience.ResilientLlmPort;
import dev.disputeagent.investigator.adapters.skills.FileSkillLibrary;
import dev.disputeagent.investigator.application.HandleDisputeOpened;
import dev.disputeagent.investigator.application.investigation.InvestigateDispute;
import dev.disputeagent.investigator.application.investigation.InvestigationSettings;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.json.JsonPort;
import dev.disputeagent.investigator.application.port.llm.Effort;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.skills.SkillLibrary;
import dev.disputeagent.investigator.application.triage.TriageDispute;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.guard.PolicyLimits;
import dev.disputeagent.investigator.domain.investigation.LoopBudget;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires what every run mode shares: the model port, the tools, the skills, the settings and the use case that triages
 * and investigates a dispute. {@link ServiceConfiguration} adds Kafka and PostgreSQL for the service;
 * {@link EvalConfiguration} adds the evaluation runner instead (profile {@code eval}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvestigatorProperties.class)
class InvestigatorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InvestigatorConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs a turn's tool calls concurrently; virtual threads, one per call. */
    @Bean(destroyMethod = "shutdownNow")
    @Qualifier("toolExecutor")
    ExecutorService toolExecutor() {
        // Carries the current observation to the thread, so work there stays in its case's trace (ADR 0008).
        return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(),
                ContextSnapshotFactory.builder().build());
    }

    /** Runs each model attempt, so the time limiter can cancel it (ADR 0008); virtual threads. */
    @Bean(destroyMethod = "shutdownNow")
    @Qualifier("llmAttempts")
    ExecutorService llmAttempts() {
        // Carries the current observation to the thread, so work there stays in its case's trace (ADR 0008).
        return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(),
                ContextSnapshotFactory.builder().build());
    }

    @Bean
    PriceTable priceTable(InvestigatorProperties properties) {
        var prices = properties.llm().prices();
        Map<String, PriceTable.ModelPrice> models = new LinkedHashMap<>();
        prices.models().forEach((model, p) -> models.put(model,
                PriceTable.ModelPrice.perMillion(p.input(), p.output(), p.cacheRead(), p.cacheWrite())));
        return new PriceTable(prices.version(), models);
    }

    /**
     * The port the use cases get: replay (CI, load test), recording (around the live model) or the live model. Primary,
     * because the live model is an {@code LlmPort} too.
     */
    @Bean
    @Primary
    LlmPort llmPort(InvestigatorProperties properties, PriceTable prices, ObjectProvider<LiveLlm> live, Clock clock,
            @Qualifier("llmAttempts") ExecutorService attempts, ObservationRegistry observations) {
        var fixtures = new Fixtures(properties.llm().fixturesPath());
        String adapter = properties.llm().adapter();
        log.info("model adapter: {} (fixtures {})", adapter, fixtures.dir().toAbsolutePath().normalize());
        LlmPort port = switch (adapter) {
            case "replay" -> new ReplayAdapter(fixtures, prices);
            case "recording" -> new RecordingAdapter(live.getObject(), fixtures);
            case "anthropic" -> live.getObject();
            default -> throw new IllegalStateException(
                    "investigator.llm.adapter must be anthropic, recording or replay, got " + adapter);
        };
        // Every adapter, replay included, goes through the same decorator (ADR 0008); replay never fails with a
        // transient kind, so there it passes through and the fixtures see the same requests.
        // Resilience outside, observation inside: a retried call shows one span per attempt (ADR 0008).
        return new ResilientLlmPort(new ObservedLlmPort(port, observations), properties.resilience(), clock, attempts);
    }

    /**
     * The audit log every use case writes to: the profile's own log (JDBC for the service, in memory for the
     * evaluation), with the case metrics derived on the way (ADR 0008).
     */
    @Bean
    @Primary
    AuditLog auditLog(@Qualifier("rawAuditLog") AuditLog raw, MeterRegistry meters) {
        return new MeteredAuditLog(raw, meters);
    }

    @Bean
    JsonPort jsonPort() {
        return new JacksonJsonAdapter();
    }

    @Bean
    SkillLibrary skillLibrary(InvestigatorProperties properties) {
        return new FileSkillLibrary(properties.skillsPath());
    }

    @Bean
    InvestigationSettings investigationSettings(InvestigatorProperties properties) {
        var llm = properties.llm();
        var b = properties.budget();
        var p = properties.policy();
        return new InvestigationSettings(llm.investigator().model(), Effort.fromConfig(llm.investigator().effort()),
                llm.investigator().maxTokens(), llm.triage().model(), Effort.fromConfig(llm.triage().effort()),
                llm.triage().maxTokens(),
                new LoopBudget(b.maxModelCalls(), b.maxToolCalls(), b.maxTokens(), b.maxWallClock()),
                new PolicyLimits(p.timeLimitDays(), Money.eur(p.provisionalCreditLimitMinor())), b.decisionRetries());
    }

    /** Triage then investigation; the audit log is the service's (JDBC) or the evaluation's (in memory). */
    @Bean
    HandleDisputeOpened handleDisputeOpened(LlmPort llm, McpToolPort mcp, JsonPort json, AuditLog audit, Clock clock,
            @Qualifier("toolExecutor") ExecutorService toolExecutor, InvestigationSettings settings,
            SkillLibrary skills) {
        return new HandleDisputeOpened(new TriageDispute(llm, json, audit, clock, settings),
                new InvestigateDispute(llm, mcp, json, audit, clock, toolExecutor, settings, skills), audit, clock);
    }
}
