package dev.disputeagent.investigator.bootstrap;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs a task on one virtual thread every {@code interval}; a failure is logged and the next run tries again. */
final class PeriodicTask implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PeriodicTask.class);

    private final String name;
    private final Duration interval;
    private final Runnable task;
    private ScheduledExecutorService scheduler;

    PeriodicTask(String name, Duration interval, Runnable task) {
        this.name = name;
        this.interval = interval;
        this.task = task;
    }

    synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(name).factory());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("{} failed; retrying in {}: {}", name, interval, e.toString());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
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
