package dev.disputeagent.investigator.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.disputeagent.investigator.application.CaseOutcome;
import dev.disputeagent.investigator.application.approval.RejectAction;
import dev.disputeagent.investigator.application.port.store.CaseStore;
import dev.disputeagent.investigator.domain.CaseId;
import dev.disputeagent.investigator.domain.investigation.CaseState;
import dev.disputeagent.investigator.domain.investigation.DisputeCase;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** What the reviewer API answers when it is not set up; the working paths are in the bootstrap's end-to-end test. */
class ReviewControllerTest {

    static final CaseStore EMPTY = new CaseStore() {
        @Override
        public Optional<StoredCase> find(CaseId disputeId) {
            return Optional.empty();
        }

        @Override
        public List<StoredCase> awaitingApproval(int limit) {
            return List.of();
        }

        @Override
        public void save(DisputeCase disputeCase, CaseOutcome outcome) {
            throw new AssertionError("nothing is saved");
        }

        @Override
        public boolean update(DisputeCase disputeCase, CaseState expected, String approvalToken) {
            throw new AssertionError("nothing is updated");
        }
    };

    static final RejectAction REJECT = new RejectAction(EMPTY, event -> { }, Clock.systemUTC());

    @Test
    void withoutAReviewerKeyEverythingIsUnavailable() {
        var controller = new ReviewController(EMPTY, () -> null, REJECT, " ");

        assertThat(controller.queue("anything").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(controller.reject("anything", "DSP-1", "{\"reviewer\":\"r\"}").getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void withoutASignerApprovalIsUnavailableButRejectionWorks() {
        var controller = new ReviewController(EMPTY, () -> null, REJECT, "key");

        var approve = controller.approve("key", "DSP-1", "{\"reviewer\":\"r\"}");
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(approve.getBody()).contains("no approval signing key");
        assertThat(controller.reject("key", "DSP-1", "{\"reviewer\":\"r\"}").getStatusCode())
                .as("an unknown case, not an unavailable API").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.queue("key").getBody()).isEqualTo("{\"reviews\":[]}");
    }

    @Test
    void aBodyThatIsNotJsonIsABadRequest() {
        var controller = new ReviewController(EMPTY, () -> null, REJECT, "key");

        assertThat(controller.reject("key", "DSP-1", "reviewer=r").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
