package dev.disputeagent.investigator.application.port.audit;

import dev.disputeagent.investigator.application.port.llm.LlmCost;
import dev.disputeagent.investigator.application.port.llm.StopReason;
import dev.disputeagent.investigator.application.port.llm.Usage;
import dev.disputeagent.investigator.application.port.mcp.McpServer;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.decision.Decision;
import dev.disputeagent.investigator.domain.guard.GuardResult;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** One audit row. Every model call, tool call, state change and decision of a case is one event, in order. */
public sealed interface AuditEvent {

    CaseId caseId();

    Instant at();

    enum Stage { TRIAGE, INVESTIGATE }

    /** Who asked for a tool call: the model, or the investigator itself (building the case's evidence index). */
    enum Caller { MODEL, SYSTEM }

    record ModelCalled(CaseId caseId, Instant at, Stage stage, String configuredModel, String answeredModel,
            String responseId, StopReason stopReason, Usage usage, LlmCost cost, Duration latency)
            implements AuditEvent {
    }

    /** A model call that failed before an answer (transport, provider error). */
    record ModelCallFailed(CaseId caseId, Instant at, Stage stage, String configuredModel, String error,
            Duration latency) implements AuditEvent {
    }

    /** Arguments are recorded as a SHA-256, not in clear: they can carry case data. */
    record ToolCalled(CaseId caseId, Instant at, Caller caller, McpServer server, String tool,
            String argumentsSha256, boolean isError, Duration latency) implements AuditEvent {
    }

    record StateChanged(CaseId caseId, Instant at, CaseState from, CaseState to, String note) implements AuditEvent {
    }

    record DecisionChecked(CaseId caseId, Instant at, Decision decision, GuardResult guardResult)
            implements AuditEvent {
    }

    /** The final answer did not match the decision schema. */
    record DecisionRejected(CaseId caseId, Instant at, int attempt, java.util.List<String> errors)
            implements AuditEvent {
    }

    /** The model loaded a skill (or asked for one that does not exist). */
    record SkillLoaded(CaseId caseId, Instant at, String skill, boolean found) implements AuditEvent {
    }

    /** Triage could not classify; the reported category is used. */
    record TriageFellBack(CaseId caseId, Instant at, String reason) implements AuditEvent {
    }

    /** The case was parked after the model or the ledger stayed unavailable (ADR 0008). */
    record CaseParked(CaseId caseId, Instant at, String reason, int replays) implements AuditEvent {
    }

    /** A parked case started again from the beginning. */
    record CaseReplayed(CaseId caseId, Instant at, int replay) implements AuditEvent {
    }

    /** The decision's action was put before a person (ADR 0005). */
    record ApprovalRequested(CaseId caseId, Instant at, String action) implements AuditEvent {
    }

    /** A person approved or rejected the action. */
    record Reviewed(CaseId caseId, Instant at, boolean approved, String reviewer, String note) implements AuditEvent {
    }

    /** An approval token was signed. Its id and claims are recorded, never the token itself. */
    record TokenIssued(CaseId caseId, Instant at, UUID tokenId, String action, String argumentsSha256,
            Instant expiresAt) implements AuditEvent {
    }

    /** The investigator called the approved action on actions-mcp; {@code detail} is the server's answer. */
    record ActionCalled(CaseId caseId, Instant at, String action, UUID tokenId, boolean isError, String detail,
            Duration latency) implements AuditEvent {
    }
}
