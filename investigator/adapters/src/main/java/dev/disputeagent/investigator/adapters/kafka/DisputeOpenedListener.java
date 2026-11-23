package dev.disputeagent.investigator.adapters.kafka;

import dev.disputeagent.investigator.application.ProcessDisputeOpened;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Consumes {@code disputes.opened} and {@code disputes.replay}, one record at a time, and acknowledges a record only
 * after the case and its outgoing events are committed; the outbox relay publishes them afterwards (ADR 0008), off this
 * path. A replay re-runs a parked case from the start; a replay for a case no longer parked is a duplicate and is done.
 *
 * <p>A malformed payload is thrown as {@link DisputeEvents.MalformedEvent}: the error handler does not retry it and
 * sends it to {@code disputes.dlq} with the reason in its headers, so the partition moves on. Any other failure is
 * retried by the error handler, then dead-lettered the same way.
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
        DisputeOpened dispute = DisputeEvents.opened(record.value());
        log(dispute, process.process(dispute), "investigated");
        ack.acknowledge();
    }

    @KafkaListener(id = "disputes-replay", topics = "${investigator.kafka.replay-topic}",
            groupId = "${investigator.kafka.group-id}")
    public void onReplay(ConsumerRecord<String, String> record, Acknowledgment ack) {
        DisputeOpened dispute = DisputeEvents.opened(record.value());
        log(dispute, process.processReplay(dispute), "replayed");
        ack.acknowledge();
    }

    private static void log(DisputeOpened dispute, ProcessDisputeOpened.Result result, String what) {
        log.info("dispute {} {}: {}{}", dispute.disputeId(), result.redelivery() ? "redelivered" : what,
                result.state(), result.outcome() == null || result.outcome().escalationReason() == null ? ""
                        : " (" + result.outcome().escalationReason() + ")");
    }
}
