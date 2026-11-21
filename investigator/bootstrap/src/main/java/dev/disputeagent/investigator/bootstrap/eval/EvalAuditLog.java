package dev.disputeagent.investigator.bootstrap.eval;

import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.domain.CaseId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The audit trail in memory, per dispute, for the evaluation runner to measure each case from. */
public final class EvalAuditLog implements AuditLog {

    private final Map<CaseId, List<AuditEvent>> byCase = new ConcurrentHashMap<>();

    @Override
    public void record(AuditEvent event) {
        byCase.computeIfAbsent(event.caseId(), id -> new ArrayList<>()).add(event);
    }

    /** The events of one dispute, in order, removed from the log. */
    public List<AuditEvent> take(CaseId disputeId) {
        List<AuditEvent> events = byCase.remove(disputeId);
        return events == null ? List.of() : List.copyOf(events);
    }
}
