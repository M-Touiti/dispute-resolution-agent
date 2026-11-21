package dev.disputeagent.investigator.adapters.kafka;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.port.events.DecisionPublisher;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes {@code disputes.decided} keyed by dispute id and waits for the broker's acknowledgement, so the caller
 * acknowledges the incoming event only after the outgoing one is stored. The transactional outbox replaces this in M2.
 */
public final class KafkaDecisionPublisher implements DecisionPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Duration timeout;

    public KafkaDecisionPublisher(KafkaTemplate<String, String> kafka, String topic, Duration timeout) {
        this.kafka = kafka;
        this.topic = topic;
        this.timeout = timeout;
    }

    @Override
    public void publish(CaseOutcome outcome) {
        try {
            kafka.send(topic, outcome.disputeId().value(), DisputeEvents.decided(outcome))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing " + outcome.disputeId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not publish the outcome of " + outcome.disputeId(), e);
        }
    }
}
