package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.web.ReviewController;
import dev.disputeagent.investigator.application.approval.ApproveAction;
import dev.disputeagent.investigator.application.approval.RejectAction;
import dev.disputeagent.investigator.application.port.approval.ActionPort;
import dev.disputeagent.investigator.application.port.approval.TokenSigner;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The reviewer API and the approval gate's use cases (ADR 0005, task 2.7). Rejecting needs nothing but the store;
 * approving needs the token signer (the signing key) and the action port (actions-mcp). Without either, approve
 * answers 503 and nothing can be carried out.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!eval")
class ReviewConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ReviewConfiguration.class);

    @Bean
    RejectAction rejectAction(CaseStore cases, AuditLog audit, Clock clock) {
        return new RejectAction(cases, audit, clock);
    }

    @Bean
    ReviewController reviewController(CaseStore cases, RejectAction reject, ObjectProvider<TokenSigner> signer,
            ObjectProvider<ActionPort> actions, AuditLog audit, Clock clock, InvestigatorProperties properties) {
        TokenSigner s = signer.getIfAvailable();
        ActionPort a = actions.getIfAvailable();
        ApproveAction approve = s == null || a == null ? null
                : new ApproveAction(cases, s, a, audit, clock, UUID::randomUUID);
        String key = properties.reviewer() == null ? null : properties.reviewer().apiKey();
        if (key == null || key.isBlank()) {
            log.warn("no reviewer key (REVIEWER_API_KEY): the reviewer API answers 503");
        }
        if (approve == null) {
            log.warn("approvals are disabled: {}", s == null ? "no approval signing key" : "no actions-mcp client");
        }
        return new ReviewController(cases, () -> approve, reject, key);
    }
}
