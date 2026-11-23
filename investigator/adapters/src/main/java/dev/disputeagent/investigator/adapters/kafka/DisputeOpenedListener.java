package dev.disputeagent.investigator.adapters.kafka;

import dev.disputeagent.investigator.application.ProcessDisputeOpened;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    /** The span around one case (ADR 0008): every model, tool and action span of the case is a child of it. */
    public static final String CASE_SPAN = "dispute.case";

    private final ProcessDisputeOpened process;
    private final ObservationRegistry observations;

    public DisputeOpenedListener(ProcessDisputeOpened process, ObservationRegistry observations) {
        this.process = process;
        this.observations = observations;
    }

    @KafkaListener(id = "disputes-opened", topics = "${investigator.kafka.opened-topic}",
            groupId = "${investigator.kafka.group-id}")
    public void onDisputeOpened(ConsumerRecord<String, String> record, Acknowledgment ack) {
        DisputeOpened dispute = DisputeEvents.opened(record.value());
        observed(dispute, "opened", () -> log(dispute, process.process(dispute), "investigated"));
        ack.acknowledge();
    }

    @KafkaListener(id = "disputes-replay", topics = "${investigator.kafka.replay-topic}",
            groupId = "${investigator.kafka.group-id}")
    public void onReplay(ConsumerRecord<String, String> record, Acknowledgment ack) {
        DisputeOpened dispute = DisputeEvents.opened(record.value());
        observed(dispute, "replay", () -> log(dispute, process.processReplay(dispute), "replayed"));
        ack.acknowledge();
    }

    /** Runs one case inside its span, with the dispute id in the logging context (every JSON log line carries it). */
    private void observed(DisputeOpened dispute, String source, Runnable work) {
        try (var mdc = MDC.putCloseable("dispute_id", dispute.disputeId().value())) {
            Observation.createNotStarted(CASE_SPAN, observations)
                    .contextualName("case " + dispute.disputeId().value())
                    .lowCardinalityKeyValue("source", source)
                    .lowCardinalityKeyValue("category", dispute.category().name().toLowerCase())
                    .highCardinalityKeyValue("dispute.id", dispute.disputeId().value())
                    .observe(work);
        }
    }

    private static void log(DisputeOpened dispute, ProcessDisputeOpened.Result result, String what) {
        log.info("dispute {} {}: {}{}", dispute.disputeId(), result.redelivery() ? "redelivered" : what,
                result.state(), result.outcome() == null || result.outcome().escalationReason() == null ? ""
                        : " (" + result.outcome().escalationReason() + ")");
    }
}
