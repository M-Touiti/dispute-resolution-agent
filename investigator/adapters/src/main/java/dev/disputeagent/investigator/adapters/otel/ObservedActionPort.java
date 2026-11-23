package dev.disputeagent.investigator.adapters.otel;

import dev.disputeagent.investigator.application.port.approval.ActionPort;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/** A span and a timer per approved action carried out on actions-mcp (ADR 0008), tagged {@code action} and {@code outcome}. */
public final class ObservedActionPort implements ActionPort, AutoCloseable {

    public static final String NAME = "dispute.action";

    private final ActionPort delegate;
    private final ObservationRegistry observations;

    public ObservedActionPort(ActionPort delegate, ObservationRegistry observations) {
        this.delegate = delegate;
        this.observations = observations;
    }

    @Override
    public String argumentsSha256(CaseId disputeId, ProposedAction action) {
        return delegate.argumentsSha256(disputeId, action);
    }

    @Override
    public Result execute(CaseId disputeId, ProposedAction action, String approvalToken) {
        Observation observation = Observation.createNotStarted(NAME, observations)
                .contextualName("action " + action.toolName())
                .lowCardinalityKeyValue("action", action.toolName())
                .lowCardinalityKeyValue("outcome", "done")
                .highCardinalityKeyValue("dispute.id", disputeId.value())
                .start();
        try (Observation.Scope scope = observation.openScope()) {
            Result result = delegate.execute(disputeId, action, approvalToken);
            if (!result.ok()) {
                observation.lowCardinalityKeyValue("outcome", "refused");
            }
            return result;
        } catch (RuntimeException e) {
            observation.lowCardinalityKeyValue("outcome", "unavailable").error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
