/**
 * Traces and metrics as decorators around the ports (ADR 0008), on Micrometer's vendor-neutral Observation and meter
 * APIs; the bootstrap connects them to OpenTelemetry (OTLP) and Prometheus. {@link ObservedLlmPort},
 * {@link ObservedMcpToolPort} and {@link ObservedActionPort} make the spans; {@link MeteredAuditLog} derives the case
 * metrics from the audit trail; {@link CardNumbers} masks card numbers in log lines.
 */
package dev.disputeagent.investigator.adapters.otel;
