package dev.disputeagent.investigator.bootstrap.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The generated CSV files are written by Python's csv module; a misread statement would change the prompt. */
class CsvTest {

    @Test
    void quotedFieldsWithCommasQuotesAndLineBreaks() {
        String text = "id,summary,amount\n"
                + "DOC-1,\"Order from Ashgrove: total 965.72 EUR, expected 2026-09-10.\",1\n"
                + "DOC-2,\"He said \"\"never\"\"\nthen left\",2\r\n"
                + "DOC-3,plain,3";

        assertThat(Csv.parse(text)).containsExactly(
                List.of("id", "summary", "amount"),
                List.of("DOC-1", "Order from Ashgrove: total 965.72 EUR, expected 2026-09-10.", "1"),
                List.of("DOC-2", "He said \"never\"\nthen left", "2"),
                List.of("DOC-3", "plain", "3"));
    }

    @Test
    void anEmptyLastFieldIsKept() {
        assertThat(Csv.parse("a,b\nx,\n")).containsExactly(List.of("a", "b"), List.of("x", ""));
    }
}
