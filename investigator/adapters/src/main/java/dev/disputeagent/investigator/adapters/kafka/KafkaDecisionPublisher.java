package dev.disputeagent.investigator.adapters.kafka;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.port.events.DecisionPublisher;
import dev.disputeagent.investigator.domain.approval.ApprovalRequest;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes {@code disputes.decided} and {@code disputes.approval-requested} keyed by dispute id and waits for the broker's acknowledgement, so the caller
 * acknowledges the incoming event only after the outgoing one is stored. The transactional outbox replaces this in M2.
 */
public final class KafkaDecisionPublisher implements DecisionPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final String decidedTopic;
    private final String approvalRequestedTopic;
    private final Duration timeout;

    public KafkaDecisionPublisher(KafkaTemplate<String, String> kafka, String decidedTopic,
            String approvalRequestedTopic, Duration timeout) {
        this.kafka = kafka;
        this.decidedTopic = decidedTopic;
        this.approvalRequestedTopic = approvalRequestedTopic;
        this.timeout = timeout;
    }

    @Override
    public void publish(CaseOutcome outcome) {
        send(decidedTopic, outcome.disputeId().value(), DisputeEvents.decided(outcome), "the outcome");
    }

    @Override
    public void publishApprovalRequest(ApprovalRequest request) {
        send(approvalRequestedTopic, request.caseId().value(), DisputeEvents.approvalRequested(request),
                "the approval request");
    }

    private void send(String topic, String key, String payload, String what) {
        try {
            kafka.send(topic, key, payload).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing " + what + " of " + key, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not publish " + what + " of " + key, e);
        }
    }
}
