package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.adapters.fixtures.FaultSwitch;
import dev.disputeagent.investigator.adapters.web.FaultController;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Failure injection (task 2.14), for the load-test stack only: {@code investigator.faults.enabled=true}
 * ({@code FAULT_INJECTION}). Without it neither the switch nor {@code /faults} exists. With it, the investigator
 * refuses to start on any adapter but replay, so a fault can never reach the live model.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!eval")
@ConditionalOnProperty(prefix = "investigator.faults", name = "enabled", havingValue = "true")
class FaultConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FaultConfiguration.class);

    @Bean
    FaultSwitch faultSwitch(InvestigatorProperties properties, Clock clock) {
        String adapter = properties.llm().adapter();
        if (!"replay".equals(adapter)) {
            throw new IllegalStateException(
                    "investigator.faults.enabled needs the replay adapter, got " + adapter + "; unset FAULT_INJECTION");
        }
        log.warn("failure injection is on: /faults can make every model call fail (load-test stack only)");
        return new FaultSwitch(clock);
    }

    @Bean
    FaultController faultController(FaultSwitch faults) {
        return new FaultController(faults);
    }
}
