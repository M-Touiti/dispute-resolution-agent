package dev.disputeagent.investigator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CaseIdTest {

    @Test
    void rejectsBlankValue() {
        assertThatThrownBy(() -> new CaseId("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalByValue() {
        assertThat(new CaseId("C-1")).isEqualTo(new CaseId("C-1"));
        assertThat(new CaseId("C-1")).hasToString("C-1");
    }

    @Test
    void onlyProvisionalCreditMovesMoney() {
        assertThat(DecisionOutcome.values())
                .filteredOn(DecisionOutcome::movesMoney)
                .containsExactly(DecisionOutcome.APPROVE_PROVISIONAL_CREDIT);
    }
}
