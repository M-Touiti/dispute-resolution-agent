package dev.disputeagent.investigator.adapters.web;

import dev.disputeagent.investigator.adapters.fixtures.FaultSwitch;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fault switch over HTTP, for the failure-injection scripts (task 2.14). Registered only when
 * {@code investigator.faults.enabled} is set, and then only with the replay adapter; otherwise the path does not
 * exist.
 *
 * <ul>
 *   <li>{@code GET /faults}: {@code {"llm_overloaded_until": <instant or null>}}.</li>
 *   <li>{@code POST /faults/llm-overloaded?seconds=N}: model calls fail as overloaded for N seconds (1 to 600; 400
 *       otherwise).</li>
 *   <li>{@code DELETE /faults/llm-overloaded}: ends it now.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/faults", produces = MediaType.APPLICATION_JSON_VALUE)
public final class FaultController {

    static final int MAX_SECONDS = 600;

    private final FaultSwitch faults;

    public FaultController(FaultSwitch faults) {
        this.faults = Objects.requireNonNull(faults, "faults");
    }

    @GetMapping
    public ResponseEntity<String> state() {
        return ResponseEntity.ok(body(faults.overloadedUntil().orElse(null)));
    }

    @PostMapping("/llm-overloaded")
    public ResponseEntity<String> overload(@RequestParam("seconds") int seconds) {
        if (seconds < 1 || seconds > MAX_SECONDS) {
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"seconds must be between 1 and " + MAX_SECONDS + "\"}");
        }
        return ResponseEntity.ok(body(faults.overloadFor(Duration.ofSeconds(seconds))));
    }

    @DeleteMapping("/llm-overloaded")
    public ResponseEntity<String> clear() {
        faults.clear();
        return ResponseEntity.ok(body(null));
    }

    private static String body(Instant until) {
        return "{\"llm_overloaded_until\":" + (until == null ? "null" : "\"" + until + "\"") + "}";
    }
}
