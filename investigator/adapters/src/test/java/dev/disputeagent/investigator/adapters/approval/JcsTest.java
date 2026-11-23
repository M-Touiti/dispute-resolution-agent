package dev.disputeagent.investigator.adapters.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** What JCS refuses, and the RFC 8785 example's string and order rules; the vectors cover the rest. */
class JcsTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void membersAreSortedAndNothingIsAdded() {
        assertThat(Jcs.canonical(JSON.readTree("{ \"b\" : [ 1 , \"x\" ], \"a\" : { \"d\": null, \"c\": true } }")))
                .isEqualTo("{\"a\":{\"c\":true,\"d\":null},\"b\":[1,\"x\"]}");
    }

    @Test
    void theRfcExampleStrings() {
        // RFC 8785 section 3.2.2.2: only the JSON.stringify escapes; "/" and non-ASCII stay as they are.
        assertThat(Jcs.canonical(JSON.readTree("\"\\u20ac$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"")))
                .isEqualTo("\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"");
    }

    @Test
    void whatCannotBeCanonicalisedExactlyIsRefused() {
        for (String json : new String[] {"1.5", "9007199254740992", "-9007199254740992", "12345678901234567890"}) {
            assertThatThrownBy(() -> Jcs.canonical(JSON.readTree(json))).as(json)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Jcs.canonical(JSON.getNodeFactory().stringNode("a\ud800b")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("surrogate");
        assertThatThrownBy(() -> Jcs.canonical(JSON.getNodeFactory().stringNode("\udc00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
