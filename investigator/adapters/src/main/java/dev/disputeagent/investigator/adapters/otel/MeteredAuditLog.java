package dev.disputeagent.investigator.adapters.otel;

import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Metrics from the audit trail (ADR 0008): every event of a case passes here on its way to the real audit log, so the
 * meters see exactly what the audit records, without touching the application.
 *
 * <ul>
 *   <li>{@code dispute.cases} by {@code outcome} ({@code decided}, {@code escalated}) and {@code reason} (the
 *       escalation reason, {@code none} when decided); {@code dispute.unsafe.proposals} for guard failures.</li>
 *   <li>{@code dispute.cases.parked} by {@code after_replays} (how many replays the case had already) and
 *       {@code dispute.cases.replayed} by {@code replay} (which replay this is): whether outages clear on the first
 *       replay or need more.</li>
 *   <li>{@code dispute.llm.tokens} by {@code model} and {@code type} (input, output, cache_read, cache_creation): the
 *       cache hit ratio is cache_read over input plus cache_read; {@code dispute.llm.cost} in dollars by model.</li>
 *   <li>{@code dispute.case.tokens} and {@code dispute.case.cost} per case run (histograms), recorded when the
 *       investigation ends or parks.</li>
 *   <li>{@code dispute.approval.latency} by {@code decision}, from the request to the review.</li>
 * </ul>
 */
public final class MeteredAuditLog implements AuditLog {

    private final AuditLog delegate;
    private final MeterRegistry meters;
    private final Map<CaseId, Run> runs = new ConcurrentHashMap<>();

    /** What one run of a case has spent so far. */
    private static final class Run {
        final LongAdder tokens = new LongAdder();
        final LongAdder microUsd = new LongAdder();
    }

    public MeteredAuditLog(AuditLog delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
    }

    @Override
    public void record(AuditEvent event) {
        delegate.record(event);
        switch (event) {
            case AuditEvent.ModelCalled called -> modelCalled(called);
            case AuditEvent.StateChanged changed -> stateChanged(changed);
            // Tagged by how far the case got: bounded by the replay cap, so the tag values stay few.
            case AuditEvent.CaseParked parked ->
                    meters.counter("dispute.cases.parked", "after_replays", Integer.toString(parked.replays()))
                            .increment();
            case AuditEvent.CaseReplayed replayed ->
                    meters.counter("dispute.cases.replayed", "replay", Integer.toString(replayed.replay()))
                            .increment();
            case AuditEvent.Reviewed reviewed -> Timer.builder("dispute.approval.latency")
                    .description("From the approval request to the person's review")
                    .tag("decision", reviewed.approved() ? "approved" : "rejected")
                    .register(meters)
                    .record(Duration.between(reviewed.requestedAt(), reviewed.at()));
            default -> {
                // the rest is measured by the observations around the ports, or not at all
            }
        }
    }

    private void modelCalled(AuditEvent.ModelCalled called) {
        String model = called.configuredModel();
        var usage = called.usage();
        meters.counter("dispute.llm.tokens", "model", model, "type", "input").increment(usage.inputTokens());
        meters.counter("dispute.llm.tokens", "model", model, "type", "output").increment(usage.outputTokens());
        meters.counter("dispute.llm.tokens", "model", model, "type", "cache_read")
                .increment(usage.cacheReadInputTokens());
        meters.counter("dispute.llm.tokens", "model", model, "type", "cache_creation")
                .increment(usage.cacheCreationInputTokens());
        meters.counter("dispute.llm.cost", "model", model).increment(called.cost().microUsd() / 1_000_000.0);
        Run run = runs.computeIfAbsent(called.caseId(), id -> new Run());
        run.tokens.add(usage.inputTokens() + usage.outputTokens());
        run.microUsd.add(called.cost().microUsd());
    }

    private void stateChanged(AuditEvent.StateChanged changed) {
        CaseState to = changed.to();
        if (to == CaseState.DECIDED || to == CaseState.ESCALATED) {
            String reason = to == CaseState.ESCALATED ? reason(changed.note()) : "none";
            meters.counter("dispute.cases", "outcome", to.name().toLowerCase(), "reason", reason).increment();
            if ("GUARD_FAILED".equals(reason)) {
                meters.counter("dispute.unsafe.proposals").increment();
            }
        }
        if (to == CaseState.DECIDED || to == CaseState.ESCALATED || to == CaseState.AWAITING_RETRY) {
            Run run = runs.remove(changed.caseId());
            if (run != null) {
                DistributionSummary.builder("dispute.case.tokens").description("Tokens one run of a case used")
                        .register(meters).record(run.tokens.sum());
                DistributionSummary.builder("dispute.case.cost").baseUnit("usd")
                        .description("What one run of a case cost").register(meters)
                        .record(run.microUsd.sum() / 1_000_000.0);
            }
        }
    }

    /** The escalation reason: the domain writes the note as {@code REASON: detail}. */
    static String reason(String note) {
        if (note == null) {
            return "unknown";
        }
        int colon = note.indexOf(':');
        String head = colon < 0 ? note : note.substring(0, colon);
        return head.matches("[A-Z_]+") ? head : "unknown";
    }
}
