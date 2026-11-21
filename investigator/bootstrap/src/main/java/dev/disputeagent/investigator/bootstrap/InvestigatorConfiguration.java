package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.anthropic.PriceTable;
import dev.disputeagent.investigator.adapters.fixtures.Fixtures;
import dev.disputeagent.investigator.adapters.fixtures.RecordingAdapter;
import dev.disputeagent.investigator.adapters.fixtures.ReplayAdapter;
import dev.disputeagent.investigator.adapters.json.JacksonJsonAdapter;
import dev.disputeagent.investigator.adapters.kafka.DisputeOpenedListener;
import dev.disputeagent.investigator.adapters.kafka.KafkaDecisionPublisher;
import dev.disputeagent.investigator.adapters.persistence.JdbcAuditLog;
import dev.disputeagent.investigator.adapters.persistence.JdbcCaseStore;
import dev.disputeagent.investigator.adapters.skills.FileSkillLibrary;
import dev.disputeagent.investigator.application.HandleDisputeOpened;
import dev.disputeagent.investigator.application.ProcessDisputeOpened;
import dev.disputeagent.investigator.application.investigation.InvestigateDispute;
import dev.disputeagent.investigator.application.investigation.InvestigationSettings;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.events.DecisionPublisher;
import dev.disputeagent.investigator.application.port.json.JsonPort;
import dev.disputeagent.investigator.application.port.llm.Effort;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.mcp.McpToolPort;
import dev.disputeagent.investigator.application.port.skills.SkillLibrary;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.application.triage.TriageDispute;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.guard.PolicyLimits;
import dev.disputeagent.investigator.domain.investigation.LoopBudget;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Wires the hexagon: adapters to ports, ports to use cases, use cases to the Kafka listener. The only place that
 * knows every module.
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
    ExecutorService toolExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
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
    LlmPort llmPort(InvestigatorProperties properties, PriceTable prices, ObjectProvider<LiveLlm> live) {
        var fixtures = new Fixtures(properties.llm().fixturesPath());
        String adapter = properties.llm().adapter();
        log.info("model adapter: {} (fixtures {})", adapter, fixtures.dir().toAbsolutePath().normalize());
        return switch (adapter) {
            case "replay" -> new ReplayAdapter(fixtures, prices);
            case "recording" -> new RecordingAdapter(live.getObject(), fixtures);
            case "anthropic" -> live.getObject();
            default -> throw new IllegalStateException(
                    "investigator.llm.adapter must be anthropic, recording or replay, got " + adapter);
        };
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
    AuditLog auditLog(JdbcClient jdbc) {
        return new JdbcAuditLog(jdbc);
    }

    @Bean
    CaseStore caseStore(JdbcClient jdbc) {
        return new JdbcCaseStore(jdbc);
    }

    @Bean
    DecisionPublisher decisionPublisher(KafkaTemplate<String, String> kafka, InvestigatorProperties properties) {
        return new KafkaDecisionPublisher(kafka, properties.kafka().decidedTopic(),
                properties.kafka().publishTimeout());
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

    @Bean
    ProcessDisputeOpened processDisputeOpened(LlmPort llm, McpToolPort mcp, JsonPort json, AuditLog audit,
            Clock clock, ExecutorService toolExecutor, InvestigationSettings settings, SkillLibrary skills,
            CaseStore cases, DecisionPublisher publisher) {
        var handle = new HandleDisputeOpened(new TriageDispute(llm, json, audit, clock, settings),
                new InvestigateDispute(llm, mcp, json, audit, clock, toolExecutor, settings, skills), audit, clock);
        return new ProcessDisputeOpened(handle, cases, publisher);
    }

    @Bean
    DisputeOpenedListener disputeOpenedListener(ProcessDisputeOpened process) {
        return new DisputeOpenedListener(process);
    }

    @Bean
    NewTopic disputesOpened(InvestigatorProperties properties) {
        return TopicBuilder.name(properties.kafka().openedTopic()).partitions(properties.kafka().partitions())
                .replicas(1).build();
    }

    @Bean
    NewTopic disputesDecided(InvestigatorProperties properties) {
        return TopicBuilder.name(properties.kafka().decidedTopic()).partitions(properties.kafka().partitions())
                .replicas(1).build();
    }

    /** A failed case is retried twice, a second apart, then logged and skipped; the dead-letter topic is M2. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1_000L, 2L));
    }
}
