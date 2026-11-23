package dev.disputeagent.investigator.application.replay;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.port.events.OutgoingEvent;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.domain.investigation.EscalationReason;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The replay sweep (ADR 0008), run on a schedule. For each parked case that is due:
 *
 * <ul>
 *   <li>a case already replayed {@link ReplayPolicy#maxReplays()} times escalates as {@code DEPENDENCY_UNAVAILABLE}:
 *       by then the outage is real and a person takes over;</li>
 *   <li>otherwise, while the model is available (the circuit breaker is not open), its {@code disputes.opened} event
 *       goes to {@code disputes.replay} through the outbox and the case is held while the replay runs.</li>
 * </ul>
 *
 * Several replicas may sweep at once: scheduling is guarded by the case still being parked and due, so a case is sent
 * once per due time.
 */
public final class SweepParkedCases {

    /** What one sweep did. */
    public record Swept(int replayed, int escalated, int heldBack) {
    }

    private final CaseStore cases;
    private final ReplayPolicy policy;
    private final AuditLog audit;
    private final Clock clock;
    private final BooleanSupplier modelAvailable;

    public SweepParkedCases(CaseStore cases, ReplayPolicy policy, AuditLog audit, Clock clock,
            BooleanSupplier modelAvailable) {
        this.cases = cases;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
        this.modelAvailable = modelAvailable;
    }

    public Swept sweep(int limit) {
        Instant now = clock.instant();
        int replayed = 0;
        int escalated = 0;
        int heldBack = 0;
        boolean available = modelAvailable.getAsBoolean();
        for (CaseStore.ParkedCase parked : cases.dueForReplay(now, limit)) {
            var c = parked.disputeCase();
            if (c.replays() >= policy.maxReplays()) {
                int mark = c.history().size();
                c.escalate(EscalationReason.DEPENDENCY_UNAVAILABLE, now,
                        "still unavailable after " + c.replays() + " replays");
                CaseOutcome outcome = CaseOutcome.of(c);
                if (cases.complete(c, outcome, List.of(new OutgoingEvent.Decided(outcome)))) {
                    c.history().subList(mark, c.history().size()).forEach(t -> audit.record(
                            new AuditEvent.StateChanged(c.id(), t.at(), t.from(), t.to(), t.note())));
                    escalated++;
                }
            } else if (!available) {
                heldBack++;
            } else if (cases.scheduleReplay(c.id(), parked.event(), now, now.plus(policy.inFlight()))) {
                replayed++;
            }
        }
        return new Swept(replayed, escalated, heldBack);
    }
}
