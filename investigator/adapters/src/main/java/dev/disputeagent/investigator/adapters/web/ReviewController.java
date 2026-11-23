package dev.disputeagent.investigator.adapters.web;

import dev.disputeagent.investigator.adapters.kafka.DisputeEvents;
import dev.disputeagent.investigator.application.approval.ApproveAction;
import dev.disputeagent.investigator.application.approval.RejectAction;
import dev.disputeagent.investigator.application.approval.ReviewException;
import dev.disputeagent.investigator.application.approval.ReviewResult;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.decision.Decision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reviewer API (ADR 0005): the queue of cases waiting for a person, and approve or reject. A static key in the
 * {@code X-Reviewer-Key} header stands in for single sign-on; the reviewer id in the body is recorded as who decided,
 * not proven. Every answer is JSON, written by hand so the wire format is explicit.
 *
 * <ul>
 *   <li>{@code GET /reviews}: the waiting cases, oldest request first, with the decision a person needs.</li>
 *   <li>{@code POST /reviews/{id}/approve} and {@code /reject}, body {@code {"reviewer": ..., "note": ...}}: 200 with
 *       where the case ended, 404 for an unknown case, 409 when the case is not waiting (another reviewer was first),
 *       400 without a reviewer, 401 without the key, 503 when the key or the token signer is not configured.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/reviews", produces = MediaType.APPLICATION_JSON_VALUE)
public final class ReviewController {

    public static final String KEY_HEADER = "X-Reviewer-Key";
    private static final int QUEUE_LIMIT = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CaseStore cases;
    private final Supplier<ApproveAction> approve;
    private final RejectAction reject;
    private final byte[] apiKey;

    /**
     * @param approve the approve use case, or null when no token signer is configured
     * @param apiKey  the reviewer key; blank disables the API (every call answers 503)
     */
    public ReviewController(CaseStore cases, Supplier<ApproveAction> approve, RejectAction reject, String apiKey) {
        this.cases = cases;
        this.approve = approve;
        this.reject = reject;
        this.apiKey = apiKey == null ? new byte[0] : apiKey.strip().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping
    public ResponseEntity<String> queue(@RequestHeader(name = KEY_HEADER, required = false) String key) {
        ResponseEntity<String> denied = denied(key);
        if (denied != null) {
            return denied;
        }
        ObjectNode body = JSON.createObjectNode();
        ArrayNode reviews = body.putArray("reviews");
        for (var stored : cases.awaitingApproval(QUEUE_LIMIT)) {
            var c = stored.disputeCase();
            var request = c.approvalRequest().orElseThrow();
            ObjectNode r = reviews.addObject()
                    .put("dispute_id", c.id().value())
                    .put("category", c.category().name())
                    .put("requested_at", request.requestedAt().toString());
            r.set("action", JSON.readTree(DisputeEvents.approvalRequested(request)).get("action"));
            Decision d = c.decision().orElseThrow();
            ObjectNode dn = r.putObject("decision")
                    .put("outcome", d.outcome().name())
                    .put("confidence", d.confidence().name())
                    .put("rationale", d.rationale());
            d.citedEvidenceIds().forEach(dn.putArray("cited_evidence_ids")::add);
            d.citedPolicySections().forEach(dn.putArray("cited_policy_sections")::add);
        }
        return ResponseEntity.ok(JSON.writeValueAsString(body));
    }

    @PostMapping(path = "/{disputeId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> approve(@RequestHeader(name = KEY_HEADER, required = false) String key,
            @PathVariable String disputeId, @RequestBody String body) {
        ResponseEntity<String> denied = denied(key);
        if (denied != null) {
            return denied;
        }
        ApproveAction action = approve == null ? null : approve.get();
        if (action == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "approvals cannot be signed: no approval signing key");
        }
        return review(disputeId, body, (id, reviewer, note) -> action.approve(id, reviewer, note));
    }

    @PostMapping(path = "/{disputeId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> reject(@RequestHeader(name = KEY_HEADER, required = false) String key,
            @PathVariable String disputeId, @RequestBody String body) {
        ResponseEntity<String> denied = denied(key);
        if (denied != null) {
            return denied;
        }
        return review(disputeId, body, reject::reject);
    }

    private interface Review {
        ReviewResult apply(CaseId id, String reviewer, String note);
    }

    private ResponseEntity<String> review(String disputeId, String body, Review review) {
        JsonNode request;
        try {
            request = JSON.readTree(body);
        } catch (JacksonException e) {
            return error(HttpStatus.BAD_REQUEST, "the body is not JSON");
        }
        String reviewer = request.path("reviewer").stringValue("").strip();
        if (reviewer.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "a reviewer is required");
        }
        String note = request.path("note").stringValue("");
        try {
            ReviewResult result = review.apply(new CaseId(disputeId), reviewer, note);
            return ResponseEntity.ok(JSON.writeValueAsString(JSON.createObjectNode()
                    .put("dispute_id", result.disputeId().value())
                    .put("state", result.state().name())
                    .put("detail", result.detail())));
        } catch (ReviewException.NotFound e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (ReviewException.Conflict e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(JSON.writeValueAsString(JSON.createObjectNode()
                    .put("error", e.getMessage()).put("state", e.state().name())));
        }
    }

    /** Null when the key matches; compared in constant time. */
    private ResponseEntity<String> denied(String key) {
        if (apiKey.length == 0) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "the reviewer API is not configured (no reviewer key)");
        }
        if (key == null || !MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
            return error(HttpStatus.UNAUTHORIZED, "a valid " + KEY_HEADER + " header is required");
        }
        return null;
    }

    private static ResponseEntity<String> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(JSON.writeValueAsString(JSON.createObjectNode().put("error", message)));
    }
}
