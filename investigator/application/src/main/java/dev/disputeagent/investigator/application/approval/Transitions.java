package dev.disputeagent.investigator.application.approval;

import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.util.List;

/** The audit rows for the transitions a change made, recorded after the change is stored. */
final class Transitions {

    private Transitions() {
    }

    static int mark(DisputeCase c) {
        return c.history().size();
    }

    static List<AuditEvent> since(DisputeCase c, int mark) {
        return c.history().subList(mark, c.history().size()).stream()
                .<AuditEvent>map(t -> new AuditEvent.StateChanged(c.id(), t.at(), t.from(), t.to(), t.note()))
                .toList();
    }
}
