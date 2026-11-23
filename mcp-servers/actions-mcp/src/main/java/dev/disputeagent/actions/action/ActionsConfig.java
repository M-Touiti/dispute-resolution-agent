package dev.disputeagent.actions.action;

import dev.disputeagent.actions.token.ApprovalVerifier;
import dev.disputeagent.actions.token.VerifyKeys;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The verifier with the configured public keys, and the service that carries actions out. The keys come from
 * {@code actions-mcp.approval.verify-keys}: in compose a config-tree file holding only the public key (ADR 0005).
 */
@Configuration(proxyBeanMethods = false)
class ActionsConfig {

    private static final Logger log = LoggerFactory.getLogger(ActionsConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    VerifyKeys verifyKeys(@Value("${actions-mcp.approval.verify-keys:}") String configured) {
        VerifyKeys keys = VerifyKeys.parse(configured);
        if (keys.ids().isEmpty()) {
            log.warn("no approval verify keys configured: every call is rejected with UNKNOWN_KEY");
        } else {
            log.info("approval tokens are accepted from Ed25519 keys {}", keys.ids());
        }
        return keys;
    }

    @Bean
    ActionService actionService(VerifyKeys keys, JdbcClient jdbc, PlatformTransactionManager transactions,
            Clock clock) {
        return new ActionService(new ApprovalVerifier(keys), jdbc, new TransactionTemplate(transactions), clock);
    }
}
