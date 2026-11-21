package dev.disputeagent.investigator.adapters.kafka;

import dev.disputeagent.investigator.application.ProcessDisputeOpened;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Consumes {@code disputes.opened} one record at a time and acknowledges it only after the case is stored and its
 * outcome published. A malformed payload is acknowledged and logged (it would fail the same way forever; the
 * dead-letter topic comes in M2). Any other failure leaves the record unacknowledged for the error handler to retry.
 */
public final class DisputeOpenedListener {

    private static final Logger log = LoggerFactory.getLogger(DisputeOpenedListener.class);

    private final ProcessDisputeOpened process;

    public DisputeOpenedListener(ProcessDisputeOpened process) {
        this.process = process;
    }

    @KafkaListener(id = "disputes-opened", topics = "${investigator.kafka.opened-topic}",
            groupId = "${investigator.kafka.group-id}")
    public void onDisputeOpened(ConsumerRecord<String, String> record, Acknowledgment ack) {
        DisputeOpened dispute;
        try {
            dispute = DisputeEvents.opened(record.value());
        } catch (DisputeEvents.MalformedEvent e) {
            log.error("skipping malformed disputes.opened at {}-{}@{}: {}", record.topic(), record.partition(),
                    record.offset(), e.getMessage());
            ack.acknowledge();
            return;
        }
        var result = process.process(dispute);
        log.info("dispute {} {}: {}{}", dispute.disputeId(), result.redelivery() ? "redelivered" : "investigated",
                result.outcome().state(), result.outcome().escalationReason() == null ? ""
                        : " (" + result.outcome().escalationReason() + ")");
        ack.acknowledge();
    }
}
