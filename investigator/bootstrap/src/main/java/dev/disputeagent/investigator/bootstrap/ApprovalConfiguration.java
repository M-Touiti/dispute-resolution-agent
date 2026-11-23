package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.approval.ApprovalKeys;
import dev.disputeagent.investigator.adapters.approval.Ed25519TokenSigner;
import dev.disputeagent.investigator.application.port.approval.TokenSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The approval-token signer (ADR 0005), when its keys are configured. Start-up fails if the private key and the
 * public key are not a pair: the tokens would never verify in actions-mcp. The approve and reject use cases are wired
 * with the reviewer API (task 2.7).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!eval")
class ApprovalConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ApprovalConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "investigator.approval", name = "signing-key")
    TokenSigner tokenSigner(InvestigatorProperties properties) {
        var keys = properties.approval();
        if (keys == null || !keys.configured()) {
            throw new IllegalStateException("investigator.approval.signing-key is set but verify-key is not");
        }
        var signer = new Ed25519TokenSigner(ApprovalKeys.privateKey(keys.signingKey()),
                ApprovalKeys.publicKey(keys.verifyKey()));
        log.info("approval tokens are signed with Ed25519 key {}", signer.keyId());
        return signer;
    }
}
