package dev.disputeagent.investigator.application.support;

import dev.disputeagent.investigator.application.CaseOutcome;
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

    private final Map<CaseId, Row> rows = new HashMap<>();
    public int updates;

    @Override
    public synchronized Optional<StoredCase> find(CaseId id) {
        return Optional.ofNullable(rows.get(id)).map(r -> new StoredCase(DisputeCase.restore(r.snapshot()),
                r.outcome(), Optional.ofNullable(r.token())));
    }

    @Override
    public synchronized void save(DisputeCase c, CaseOutcome outcome) {
        if (rows.containsKey(c.id())) {
            throw new IllegalStateException("case " + c.id() + " is already stored");
        }
        rows.put(c.id(), new Row(c.snapshot(), outcome, null));
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
