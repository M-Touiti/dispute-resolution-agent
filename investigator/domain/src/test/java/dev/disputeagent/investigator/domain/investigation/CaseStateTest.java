package dev.disputeagent.investigator.domain.investigation;

import static dev.disputeagent.investigator.domain.investigation.CaseState.ACTED;
import static dev.disputeagent.investigator.domain.investigation.CaseState.ACTION_FAILED;
import static dev.disputeagent.investigator.domain.investigation.CaseState.APPROVED;
import static dev.disputeagent.investigator.domain.investigation.CaseState.AWAITING_APPROVAL;
import static dev.disputeagent.investigator.domain.investigation.CaseState.AWAITING_RETRY;
import static dev.disputeagent.investigator.domain.investigation.CaseState.DECIDED;
import static dev.disputeagent.investigator.domain.investigation.CaseState.ESCALATED;
import static dev.disputeagent.investigator.domain.investigation.CaseState.INVESTIGATING;
import static dev.disputeagent.investigator.domain.investigation.CaseState.RECEIVED;
import static dev.disputeagent.investigator.domain.investigation.CaseState.REJECTED;
import static dev.disputeagent.investigator.domain.investigation.CaseState.TRIAGED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The whole transition table, so a change to it is a deliberate change to this test. */
class CaseStateTest {

    @Test
    void theTransitionTable() {
        Map<CaseState, EnumSet<CaseState>> expected = Map.ofEntries(
                Map.entry(RECEIVED, EnumSet.of(TRIAGED, AWAITING_RETRY, ESCALATED)),
                Map.entry(TRIAGED, EnumSet.of(INVESTIGATING, AWAITING_RETRY, ESCALATED)),
                Map.entry(INVESTIGATING, EnumSet.of(DECIDED, AWAITING_RETRY, ESCALATED)),
                Map.entry(AWAITING_RETRY, EnumSet.of(RECEIVED, ESCALATED)),
                Map.entry(DECIDED, EnumSet.of(AWAITING_APPROVAL)),
                Map.entry(AWAITING_APPROVAL, EnumSet.of(APPROVED, REJECTED)),
                Map.entry(APPROVED, EnumSet.of(ACTED, ACTION_FAILED)),
                Map.entry(ESCALATED, EnumSet.noneOf(CaseState.class)),
                Map.entry(REJECTED, EnumSet.noneOf(CaseState.class)),
                Map.entry(ACTED, EnumSet.noneOf(CaseState.class)),
                Map.entry(ACTION_FAILED, EnumSet.noneOf(CaseState.class)));

        assertThat(expected).containsOnlyKeys(CaseState.values());
        expected.forEach((from, to) -> assertThat(from.next()).as(from.name()).isEqualTo(to));
    }

    @Test
    void terminalMeansNoWayOutAndTheInvestigationEndsAtItsOutcome() {
        assertThat(EnumSet.allOf(CaseState.class)).filteredOn(CaseState::isTerminal)
                .containsExactlyInAnyOrder(ESCALATED, REJECTED, ACTED, ACTION_FAILED);
        assertThat(EnumSet.allOf(CaseState.class)).filteredOn(CaseState::endsInvestigation)
                .containsExactlyInAnyOrder(DECIDED, ESCALATED);
    }

    @Test
    void noStateBeforeTheDecisionLeadsToApprovalAndNothingAfterItIsParked() {
        for (CaseState s : EnumSet.of(RECEIVED, TRIAGED, INVESTIGATING, AWAITING_RETRY)) {
            assertThat(s.next()).as(s.name()).doesNotContain(AWAITING_APPROVAL, APPROVED, ACTED);
        }
        for (CaseState s : EnumSet.of(DECIDED, AWAITING_APPROVAL, APPROVED)) {
            assertThat(s.next()).as(s.name()).doesNotContain(AWAITING_RETRY, ESCALATED);
        }
    }
}
