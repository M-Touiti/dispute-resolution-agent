package dev.disputeagent.investigator.bootstrap.eval;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.HandleDisputeOpened;
import dev.disputeagent.investigator.application.intake.DisputeOpened;
import dev.disputeagent.investigator.application.port.audit.AuditEvent;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.DisputeCategory;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs every case of a golden set through the same triage and investigation as the service, one after another, and
 * writes {@code <out>/<dispute id>.json} per case plus {@code <out>/run.json}. Disputes are built from the generated
 * CSV files exactly as the compose smoke test builds the {@code disputes.opened} event from the seeded tables, so the
 * model requests (and fixture keys) are the same in both. Results are measured from the case's audit trail.
 *
 * <p>The runner judges nothing: the Python graders in {@code evals/evaltools} compare results with the ground truth.
 */
public final class EvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final HandleDisputeOpened handle;
    private final EvalAuditLog audit;
    private final Path golden;
    private final Path dataDir;
    private final Path out;
    private final String adapter;
    private final long maxCostMicroUsd;

    /**
     * @param maxCostMicroUsd stop before the next case once the run has cost this much (live runs); 0 for no cap
     */
    public EvalRunner(HandleDisputeOpened handle, EvalAuditLog audit, Path golden, Path dataDir, Path out,
            String adapter, long maxCostMicroUsd) {
        this.handle = handle;
        this.audit = audit;
        this.golden = golden;
        this.dataDir = dataDir;
        this.out = out;
        this.adapter = adapter;
        this.maxCostMicroUsd = maxCostMicroUsd;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        JsonNode set = JSON.readTree(golden.toFile());
        Map<String, DisputeOpened> disputes = disputes(dataDir);
        Files.createDirectories(out);
        List<String> ids = new ArrayList<>();
        set.get("cases").forEach(c -> ids.add(c.get("dispute_id").asString()));
        log.info("evaluating {} cases of {} with the {} adapter into {}", ids.size(), set.get("golden_set").asString(),
                adapter, out.toAbsolutePath().normalize());

        Map<String, Integer> states = new LinkedHashMap<>();
        long spent = 0;
        int done = 0;
        for (String id : ids) {
            if (maxCostMicroUsd > 0 && spent >= maxCostMicroUsd) {
                log.warn("spend cap reached: ${} of ${} after {} of {} cases; stopping", spent / 1e6,
                        maxCostMicroUsd / 1e6, done, ids.size());
                break;
            }
            DisputeOpened dispute = disputes.get(id);
            if (dispute == null) {
                throw new IllegalStateException(id + " is in the golden set but not in " + dataDir);
            }
            DisputeCase finished = handle.handle(dispute);
            Map<String, Object> result = result(dispute, finished, audit.take(dispute.disputeId()));
            write(out.resolve(id + ".json"), result);
            states.merge(finished.state() + finished.escalationReason().map(r -> " " + r).orElse(""), 1, Integer::sum);
            @SuppressWarnings("unchecked")
            long cost = (long) ((Map<String, Object>) result.get("usage")).get("cost_micro_usd");
            spent += cost;
            done++;
            log.info("{}: {} (${}, run total ${})", id, finished.state(), cost / 1e6, spent / 1e6);
        }
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("golden_set", set.get("golden_set").asString());
        run.put("adapter", adapter);
        run.put("cases", ids.size());
        run.put("cases_run", done);
        run.put("cost_micro_usd", spent);
        run.put("max_cost_micro_usd", maxCostMicroUsd);
        run.put("states", states);
        write(out.resolve("run.json"), run);
        log.info("evaluation run done: {}", states);
    }

    /** The disputes of the generated data, with their documents in id order (as the smoke test builds them). */
    static Map<String, DisputeOpened> disputes(Path dataDir) {
        Map<String, List<DisputeOpened.Document>> documents = Csv.read(dataDir.resolve("dispute_documents.csv"))
                .stream()
                .sorted(Comparator.comparing(r -> r.get("document_id")))
                .collect(Collectors.groupingBy(r -> r.get("dispute_id"), LinkedHashMap::new,
                        Collectors.mapping(r -> new DisputeOpened.Document(r.get("document_id"), r.get("kind"),
                                r.get("issued_by"), LocalDate.parse(r.get("dated")), r.get("summary")),
                                Collectors.toList())));
        Map<String, DisputeOpened> out = new LinkedHashMap<>();
        for (Map<String, String> r : Csv.read(dataDir.resolve("disputes.csv"))) {
            String id = r.get("dispute_id");
            out.put(id, new DisputeOpened(new CaseId(id), r.get("cardholder_id"), r.get("card_id"),
                    r.get("transaction_id"), DisputeCategory.valueOf(r.get("category")),
                    Instant.parse(r.get("opened_at")),
                    new Money(Long.parseLong(r.get("claimed_amount_minor")), r.get("currency")),
                    r.get("cardholder_statement"), documents.getOrDefault(id, List.of())));
        }
        return out;
    }

    /** One case's result: the outcome as published, and what the investigation used, from its audit trail. */
    static Map<String, Object> result(DisputeOpened dispute, DisputeCase finished, List<AuditEvent> events) {
        CaseOutcome outcome = CaseOutcome.of(finished);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dispute_id", dispute.disputeId().value());
        r.put("outcome", JSON.readTree(dev.disputeagent.investigator.adapters.kafka.DisputeEvents.decided(outcome)));

        long modelCalls = 0, inputTokens = 0, outputTokens = 0, cacheRead = 0, cacheWrite = 0, costMicroUsd = 0;
        List<String> priceTables = new ArrayList<>();
        List<String> modelFailures = new ArrayList<>();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        int systemToolCalls = 0;
        int decisionRejections = 0;
        List<String> skills = new ArrayList<>();
        boolean triageFellBack = false;
        for (AuditEvent e : events) {
            switch (e) {
                case AuditEvent.ModelCalled m -> {
                    modelCalls++;
                    inputTokens += m.usage().inputTokens();
                    outputTokens += m.usage().outputTokens();
                    cacheRead += m.usage().cacheReadInputTokens();
                    cacheWrite += m.usage().cacheCreationInputTokens();
                    costMicroUsd += m.cost().microUsd();
                    if (!priceTables.contains(m.cost().priceTableVersion())) {
                        priceTables.add(m.cost().priceTableVersion());
                    }
                }
                case AuditEvent.ModelCallFailed f -> modelFailures.add(f.stage() + ": " + f.error());
                case AuditEvent.ToolCalled t -> {
                    if (t.caller() == AuditEvent.Caller.SYSTEM) {
                        systemToolCalls++;
                    } else {
                        toolCalls.add(Map.of("server", t.server().name().toLowerCase(), "tool", t.tool(),
                                "is_error", t.isError()));
                    }
                }
                case AuditEvent.DecisionRejected d -> decisionRejections++;
                case AuditEvent.SkillLoaded s -> skills.add(s.skill());
                case AuditEvent.TriageFellBack t -> triageFellBack = true;
                case AuditEvent.StateChanged s -> {
                }
                case AuditEvent.DecisionChecked d -> {
                }
                // Approval, action and parking do not happen in an evaluation run (M2): the run ends at the
                // investigation's outcome and replay never fails with a retryable kind.
                case AuditEvent.CaseParked parked -> modelFailures.add("PARKED: " + parked.reason());
                case AuditEvent.CaseReplayed replayed -> {
                }
                case AuditEvent.ApprovalRequested requested -> {
                }
                case AuditEvent.Reviewed reviewed -> {
                }
                case AuditEvent.TokenIssued issued -> {
                }
                case AuditEvent.ActionCalled called -> {
                }
            }
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("model_calls", modelCalls);
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.put("cache_read_input_tokens", cacheRead);
        usage.put("cache_creation_input_tokens", cacheWrite);
        usage.put("cost_micro_usd", costMicroUsd);
        usage.put("price_tables", priceTables);
        r.put("usage", usage);
        r.put("tool_calls", toolCalls);
        r.put("system_tool_calls", systemToolCalls);
        r.put("skills_loaded", skills);
        r.put("decision_rejections", decisionRejections);
        r.put("triage_fell_back", triageFellBack);
        r.put("model_failures", modelFailures);
        return r;
    }

    private static void write(Path file, Object value) {
        try {
            Files.writeString(file, JSON.writeValueAsString(value).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
