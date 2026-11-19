package dev.disputeagent.investigator.application;

import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.investigation.InvestigateDispute;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.application.port.audit.AuditLog;
import dev.disputeagent.investigator.application.triage.TriageDispute;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.time.Clock;

/**
 * One dispute from the {@code disputes.opened} event to a decided or escalated case: triage, then investigation.
 * Persistence and the outgoing event are the caller's (the Kafka and JPA adapters, task 1.13).
 */
public final class HandleDisputeOpened {

    private final TriageDispute triage;
    private final InvestigateDispute investigate;
    private final AuditLog audit;
    private final Clock clock;

    public HandleDisputeOpened(TriageDispute triage, InvestigateDispute investigate, AuditLog audit, Clock clock) {
        this.triage = triage;
        this.investigate = investigate;
        this.audit = audit;
        this.clock = clock;
    }

    public DisputeCase handle(DisputeOpened dispute) {
        var disputeCase = new DisputeCase(dispute.disputeId(), dispute.category(), clock.instant());
        var received = disputeCase.history().getFirst();
        audit.record(new AuditEvent.StateChanged(dispute.disputeId(), received.at(), null, received.to(),
                received.note()));

        DisputeCategory category = triage.triage(dispute);
        disputeCase.triaged(category, clock.instant());
        var triaged = disputeCase.history().getLast();
        audit.record(new AuditEvent.StateChanged(dispute.disputeId(), triaged.at(), triaged.from(), triaged.to(),
                triaged.note()));

        investigate.investigate(disputeCase, dispute);
        return disputeCase;
    }
}
