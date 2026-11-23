package dev.disputeagent.investigator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;

/** The customizer on Boot's own JSON writer, as the structured log formatters use it. */
class MaskingLogCustomizerTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void everyStringOfALogLineHasItsCardNumbersMasked() {
        JsonWriter<Map<String, String>> line = JsonWriter.of(members -> {
            members.add("message", m -> m.get("message"));
            members.add("dispute_id", m -> m.get("dispute_id"));
            new MaskingLogCustomizer().customize((JsonWriter.Members) members);
        });

        String json = line.writeToString(Map.of("message", "statement mentions 4111-1111-1111-1111",
                "dispute_id", "DSP-000018"));

        assertThat(json).isEqualTo("{\"message\":\"statement mentions ************1111\",\"dispute_id\":\"DSP-000018\"}");
    }
}
