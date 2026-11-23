package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.kafka.DisputeOpenedListener;
import dev.disputeagent.investigator.adapters.kafka.KafkaDecisionPublisher;
import dev.disputeagent.investigator.adapters.persistence.JdbcAuditLog;
import dev.disputeagent.investigator.adapters.persistence.JdbcCaseStore;
import dev.disputeagent.investigator.application.HandleDisputeOpened;
import dev.disputeagent.investigator.application.ProcessDisputeOpened;
import dev.disputeagent.investigator.application.approval.RequestApproval;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.events.DecisionPublisher;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The service: disputes.opened in, cases and audit in PostgreSQL, disputes.decided and disputes.approval-requested
 * out. Every profile but eval.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!eval")
class ServiceConfiguration {

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
                properties.kafka().approvalRequestedTopic(), properties.kafka().publishTimeout());
    }

    /** A decided case's action goes before a person (ADR 0005); approving it arrives with the reviewer API. */
    @Bean
    RequestApproval requestApproval(AuditLog audit, Clock clock) {
        return new RequestApproval(audit, clock);
    }

    @Bean
    ProcessDisputeOpened processDisputeOpened(HandleDisputeOpened handle, RequestApproval requestApproval,
            CaseStore cases, DecisionPublisher publisher) {
        return new ProcessDisputeOpened(handle, requestApproval, cases, publisher);
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
    NewTopic disputesApprovalRequested(InvestigatorProperties properties) {
        return TopicBuilder.name(properties.kafka().approvalRequestedTopic())
                .partitions(properties.kafka().partitions()).replicas(1).build();
    }

    /** A failed case is retried twice, a second apart, then logged and skipped; the dead-letter topic is M2. */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1_000L, 2L));
    }
}
