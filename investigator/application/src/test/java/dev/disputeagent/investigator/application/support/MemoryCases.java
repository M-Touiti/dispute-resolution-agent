package dev.disputeagent.investigator.application.support;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.port.events.OutgoingEvent;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link CaseStore} in memory that behaves like the database: it keeps snapshots, not the live case, so a change is
 * visible only once saved, and an update applies only if the stored state is still the expected one.
 */
public final class MemoryCases implements CaseStore {

    private record Row(DisputeCase.Snapshot snapshot, CaseOutcome outcome, String token) {
    }

    /** The events parked cases came from, for replay. */
    public final Map<CaseId, dev.disputeagent.investigator.application.intake.DisputeOpened> parkedEvents =
            new HashMap<>();

    private final Map<CaseId, Row> rows = new HashMap<>();
    /** Every event saved with a case, in order: what the outbox would publish. */
    public final java.util.List<OutgoingEvent> outbox = new java.util.ArrayList<>();
    public int updates;

    @Override
    public synchronized Optional<StoredCase> find(CaseId id) {
        return Optional.ofNullable(rows.get(id)).map(r -> new StoredCase(DisputeCase.restore(r.snapshot()),
                r.outcome(), Optional.ofNullable(r.token())));
    }

    @Override
    public synchronized java.util.List<StoredCase> awaitingApproval(int limit) {
        return rows.keySet().stream().map(id -> find(id).orElseThrow())
                .filter(s -> s.disputeCase().state() == CaseState.AWAITING_APPROVAL)
                .sorted(java.util.Comparator.comparing(s -> s.disputeCase().approvalRequest().orElseThrow().requestedAt()))
                .limit(limit).toList();
    }

    @Override
    public synchronized void save(DisputeCase c, CaseOutcome outcome, java.util.List<OutgoingEvent> events) {
        if (rows.containsKey(c.id())) {
            throw new IllegalStateException("case " + c.id() + " is already stored");
        }
        rows.put(c.id(), new Row(c.snapshot(), outcome, null));
        outbox.addAll(events);
    }

    @Override
    public synchronized void park(DisputeCase parked, dev.disputeagent.investigator.application.intake.DisputeOpened event, java.time.Instant nextAttemptAt) {
        if (rows.containsKey(parked.id())) {
            throw new IllegalStateException("case " + parked.id() + " is already stored");
        }
        rows.put(parked.id(), new Row(parked.snapshot(), null, null));
        parkedEvents.put(parked.id(), event);
        nextAttempts.put(parked.id(), nextAttemptAt);
    }

    /** When each parked case may next be replayed. */
    public final Map<CaseId, java.time.Instant> nextAttempts = new HashMap<>();

    @Override
    public synchronized java.util.List<ParkedCase> dueForReplay(java.time.Instant now, int limit) {
        return rows.keySet().stream().sorted()
                .filter(id -> rows.get(id).snapshot().state() == CaseState.AWAITING_RETRY)
                .filter(id -> !nextAttempts.get(id).isAfter(now))
                .limit(limit)
                .map(id -> new ParkedCase(DisputeCase.restore(rows.get(id).snapshot()), parkedEvents.get(id),
                        nextAttempts.get(id)))
                .toList();
    }

    @Override
    public synchronized boolean scheduleReplay(CaseId id, dev.disputeagent.investigator.application.intake.DisputeOpened event, java.time.Instant now, java.time.Instant holdUntil) {
        Row row = rows.get(id);
        if (row == null || row.snapshot().state() != CaseState.AWAITING_RETRY || nextAttempts.get(id).isAfter(now)) {
            return false;
        }
        nextAttempts.put(id, holdUntil);
        outbox.add(new OutgoingEvent.Replay(event));
        return true;
    }

    @Override
    public synchronized boolean repark(DisputeCase parked, java.time.Instant nextAttemptAt) {
        Row row = rows.get(parked.id());
        if (row == null || row.snapshot().state() != CaseState.AWAITING_RETRY) {
            return false;
        }
        rows.put(parked.id(), new Row(parked.snapshot(), null, null));
        nextAttempts.put(parked.id(), nextAttemptAt);
        return true;
    }

    @Override
    public synchronized boolean complete(DisputeCase finished, CaseOutcome outcome, java.util.List<OutgoingEvent> events) {
        Row row = rows.get(finished.id());
        if (row == null || row.snapshot().state() != CaseState.AWAITING_RETRY) {
            return false;
        }
        rows.put(finished.id(), new Row(finished.snapshot(), outcome, null));
        nextAttempts.remove(finished.id());
        outbox.addAll(events);
        return true;
    }

    @Override
    public synchronized boolean update(DisputeCase c, CaseState expected, String token) {
        Row row = rows.get(c.id());
        if (row == null || row.snapshot().state() != expected) {
            return false;
        }
        updates++;
        rows.put(c.id(), new Row(c.snapshot(), row.outcome(), token == null ? row.token() : token));
        return true;
    }

    public synchronized CaseState stateOf(CaseId id) {
        return rows.get(id).snapshot().state();
    }

    public synchronized boolean contains(CaseId id) {
        return rows.containsKey(id);
    }
}
