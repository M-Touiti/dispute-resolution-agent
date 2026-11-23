package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.kafka.DisputeOpenedListener;
import dev.disputeagent.investigator.adapters.kafka.DisputeEvents;
import dev.disputeagent.investigator.adapters.kafka.OutboxRelay;
import dev.disputeagent.investigator.adapters.resilience.ResilientLlmPort;
import dev.disputeagent.investigator.adapters.persistence.JdbcAuditLog;
import dev.disputeagent.investigator.adapters.persistence.JdbcCaseStore;
import dev.disputeagent.investigator.application.HandleDisputeOpened;
import dev.disputeagent.investigator.application.ProcessDisputeOpened;
import dev.disputeagent.investigator.application.approval.RequestApproval;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.llm.LlmPort;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.application.replay.ReplayPolicy;
import dev.disputeagent.investigator.application.replay.SweepParkedCases;
import java.time.Clock;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The service: disputes.opened and disputes.replay in, cases and audit in PostgreSQL, events out through the outbox,
 * dead letters to disputes.dlq. Every profile but eval.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!eval")
class ServiceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ServiceConfiguration.class);

    @Bean
    AuditLog auditLog(JdbcClient jdbc) {
        return new JdbcAuditLog(jdbc);
    }

    @Bean
    CaseStore caseStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
        return new JdbcCaseStore(jdbc, new TransactionTemplate(transactions));
    }

    /** Publishes the outbox (ADR 0008): the only code that sends investigator events to Kafka. */
    @Bean(initMethod = "start", destroyMethod = "close")
    OutboxRelay outboxRelay(JdbcClient jdbc, PlatformTransactionManager transactions,
            KafkaTemplate<String, String> kafka, InvestigatorProperties properties) {
        var k = properties.kafka();
        var outbox = properties.outbox();
        return new OutboxRelay(jdbc, new TransactionTemplate(transactions), OutboxRelay.kafka(kafka, k.publishTimeout()),
                Map.of("DECIDED", k.decidedTopic(), "APPROVAL_REQUESTED", k.approvalRequestedTopic(),
                        "REPLAY", k.replayTopic()),
                outbox.batchSize(), outbox.pollInterval());
    }

    /** A decided case's action goes before a person (ADR 0005); approving it arrives with the reviewer API. */
    @Bean
    RequestApproval requestApproval(AuditLog audit, Clock clock) {
        return new RequestApproval(audit, clock);
    }

    @Bean
    ProcessDisputeOpened processDisputeOpened(HandleDisputeOpened handle, RequestApproval requestApproval,
            CaseStore cases, ReplayPolicy replayPolicy, Clock clock) {
        return new ProcessDisputeOpened(handle, requestApproval, cases, replayPolicy, clock);
    }

    @Bean
    ReplayPolicy replayPolicy(InvestigatorProperties properties) {
        var r = properties.replay();
        return new ReplayPolicy(r.maxReplays(), r.firstDelay(), r.maxDelay(), r.inFlight());
    }

    /**
     * The replay sweep (ADR 0008): due parked cases go to disputes.replay while the model's circuit breaker is not open,
     * and a case past the replay cap escalates.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    PeriodicTask replaySweep(CaseStore cases, ReplayPolicy replayPolicy, AuditLog audit, Clock clock, LlmPort llm,
            InvestigatorProperties properties) {
        BooleanSupplier modelAvailable = llm instanceof ResilientLlmPort r ? r::available : () -> true;
        var sweep = new SweepParkedCases(cases, replayPolicy, audit, clock, modelAvailable);
        int batch = properties.replay().batchSize();
        return new PeriodicTask("replay-sweep", properties.replay().sweepInterval(), () -> {
            var swept = sweep.sweep(batch);
            if (swept.replayed() + swept.escalated() + swept.heldBack() > 0) {
                log.info("replay sweep: {} replayed, {} escalated, {} held back while the model is unavailable",
                        swept.replayed(), swept.escalated(), swept.heldBack());
            }
        });
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

    @Bean
    NewTopic disputesReplay(InvestigatorProperties properties) {
        return TopicBuilder.name(properties.kafka().replayTopic()).partitions(properties.kafka().partitions())
                .replicas(1).build();
    }

    @Bean
    NewTopic disputesDlq(InvestigatorProperties properties) {
        return TopicBuilder.name(properties.kafka().dlqTopic()).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic disputesApprovalRequested(InvestigatorProperties properties) {
        return TopicBuilder.name(properties.kafka().approvalRequestedTopic())
                .partitions(properties.kafka().partitions()).replicas(1).build();
    }

    /**
     * Dead letters (ADR 0008): a failed record is retried twice, a second apart, then published to disputes.dlq with
     * the exception and the original topic, partition and offset in headers; a malformed event goes there at once.
     * Either way the partition moves on.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka, InvestigatorProperties properties) {
        String dlq = properties.kafka().dlqTopic();
        var recoverer = new DeadLetterPublishingRecoverer(kafka, (record, e) -> new TopicPartition(dlq, -1));
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2L));
        handler.addNotRetryableExceptions(DisputeEvents.MalformedEvent.class);
        return handler;
    }
}
