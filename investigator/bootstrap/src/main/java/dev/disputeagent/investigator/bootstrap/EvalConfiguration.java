package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.application.HandleDisputeOpened;
import dev.disputeagent.investigator.bootstrap.eval.EvalAuditLog;
import dev.disputeagent.investigator.bootstrap.eval.EvalRunner;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** The evaluation run mode (profile {@code eval}): the golden cases instead of Kafka, results instead of a database. */
@Configuration(proxyBeanMethods = false)
@Profile("eval")
@EnableConfigurationProperties(EvalConfiguration.EvalProperties.class)
class EvalConfiguration {

    /** Paths are strings, read with Path.of (see InvestigatorProperties). */
    @ConfigurationProperties("investigator.eval")
    record EvalProperties(String golden, String dataDir, String out, long maxCostMicroUsd) {
    }

    @Bean
    @Qualifier("rawAuditLog")
    EvalAuditLog evalAuditLog() {
        return new EvalAuditLog();
    }

    @Bean
    EvalRunner evalRunner(HandleDisputeOpened handle, EvalAuditLog audit, EvalProperties eval,
            InvestigatorProperties properties, ObservationRegistry observations) {
        return new EvalRunner(handle, audit, Path.of(eval.golden()), Path.of(eval.dataDir()), Path.of(eval.out()),
                properties.llm().adapter(), eval.maxCostMicroUsd(), observations);
    }
}
