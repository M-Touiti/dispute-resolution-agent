package dev.disputeagent.investigator.domain.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.decision.Money;
import dev.disputeagent.investigator.domain.decision.ProposedAction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalTokenTest {

    private static final Instant T0 = Instant.parse("2026-11-23T09:00:00Z");
    private static final CaseId ID = new CaseId("DSP-000018");
    private static final ProposedAction CREDIT = new ProposedAction.IssueProvisionalCredit(new Money(100, "EUR"));
    private static final String HASH = "0123456789abcdef".repeat(4);

    @Test
    void issuedForTheReviewedRequestAndValidForFiveMinutes() {
        var review = new Review("reviewer-1", "ok", T0);
        var token = ApprovalToken.issue(UUID.randomUUID(), new ApprovalRequest(ID, CREDIT, T0.minusSeconds(60)),
                HASH, review);

        assertThat(token.action()).isEqualTo("issue_provisional_credit");
        assertThat(token.reviewer()).isEqualTo("reviewer-1");
        assertThat(token.issuedAt()).isEqualTo(T0);
        assertThat(token.expiresAt()).isEqualTo(T0.plusSeconds(300));
        assertThat(token.bindsTo(ID, CREDIT)).isTrue();
        assertThat(token.bindsTo(new CaseId("DSP-000002"), CREDIT)).isFalse();
        assertThat(token.bindsTo(ID, new ProposedAction.CloseDispute("r", List.of()))).isFalse();
        assertThat(token.expiredAt(T0.plusSeconds(299))).isFalse();
        assertThat(token.expiredAt(T0.plusSeconds(300))).isTrue();
    }

    @Test
    void theArgumentHashIsLowerCaseHexSha256() {
        for (String bad : List.of("", "ABCDEF".repeat(10) + "abcd", "0".repeat(63), "g".repeat(64))) {
            assertThatThrownBy(() -> new ApprovalToken(UUID.randomUUID(), ID, "close_dispute", bad, "r", T0,
                    T0.plusSeconds(60))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aTokenExpiresAfterItsIssueAndLivesAtMostFiveMinutes() {
        assertThatThrownBy(() -> new ApprovalToken(UUID.randomUUID(), ID, "close_dispute", HASH, "r", T0, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApprovalToken(UUID.randomUUID(), ID, "close_dispute", HASH, "r", T0,
                T0.plusSeconds(301))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReviewNeedsAReviewer() {
        assertThatThrownBy(() -> new Review(" ", "note", T0)).isInstanceOf(IllegalArgumentException.class);
    }
}
