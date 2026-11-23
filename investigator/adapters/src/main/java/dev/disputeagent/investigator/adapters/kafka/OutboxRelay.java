package dev.disputeagent.investigator.adapters.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the outbox (ADR 0008, Flyway {@code V3}): unsent rows in id order, each marked sent once the broker has it.
 *
 * <ul>
 *   <li>Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several replicas can relay at once without two of
 *       them sending the same row; order holds within a batch, not across replicas, and no consumer depends on order
 *       across disputes.</li>
 *   <li>A send that fails records the error on its row and ends the batch, so a later event for the same dispute never
 *       overtakes an earlier one; the next poll tries again. The rows sent before the failure stay marked.</li>
 *   <li>Delivery is at least once: a crash between the broker's acknowledgement and the commit sends the row again.
 *       Consumers are idempotent on the dispute id.</li>
 * </ul>
 */
public final class OutboxRelay implements AutoCloseable {

    /** Sends one event and returns once the broker has it; throws otherwise. */
    @FunctionalInterface
    public interface Sender {
        void send(String topic, String key, String payload) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Sender sender;
    private final Map<String, String> topicByType;
    private final int batchSize;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    /**
     * @param topicByType the topic of each outbox row type ({@code DECIDED}, {@code APPROVAL_REQUESTED})
     */
    public OutboxRelay(JdbcClient jdbc, TransactionTemplate transactions, Sender sender,
            Map<String, String> topicByType, int batchSize, Duration interval) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.sender = sender;
        this.topicByType = Map.copyOf(topicByType);
        this.batchSize = batchSize;
        this.interval = Objects.requireNonNull(interval, "interval");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
    }

    /** The production sender: {@link KafkaTemplate}, waiting for the broker's acknowledgement. */
    public static Sender kafka(KafkaTemplate<String, String> kafka, Duration timeout) {
        return (topic, key, payload) -> kafka.send(topic, key, payload).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private record Row(long id, String disputeId, String type, String payload) {
    }

    /** One batch; returns how many rows were sent. */
    public int relayOnce() {
        Integer sent = transactions.execute(status -> {
            List<Row> rows = jdbc.sql("""
                    SELECT id, dispute_id, type, payload FROM investigation.outbox
                    WHERE sent_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED""")
                    .param("limit", batchSize)
                    .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                    .list();
            int count = 0;
            for (Row row : rows) {
                String topic = topicByType.get(row.type());
                try {
                    if (topic == null) {
                        throw new IllegalStateException("no topic for outbox type " + row.type());
                    }
                    sender.send(topic, row.disputeId(), row.payload());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    jdbc.sql("""
                            UPDATE investigation.outbox SET attempts = attempts + 1, last_error = :error
                            WHERE id = :id""")
                            .param("id", row.id()).param("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                            .update();
                    log.warn("outbox row {} ({} for {}) not sent, retrying on the next poll: {}", row.id(), row.type(),
                            row.disputeId(), e.toString());
                    break;
                }
                jdbc.sql("""
                        UPDATE investigation.outbox SET sent_at = now(), attempts = attempts + 1, last_error = NULL
                        WHERE id = :id""")
                        .param("id", row.id()).update();
                count++;
            }
            return count;
        });
        return sent == null ? 0 : sent;
    }

    /** Polls every {@code interval}; a full batch is followed at once by the next. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("outbox-relay").factory());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                while (relayOnce() == batchSize) {
                    // a full batch: there may be more
                }
            } catch (RuntimeException e) {
                log.warn("outbox relay poll failed; retrying on the next poll: {}", e.toString());
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }
}
