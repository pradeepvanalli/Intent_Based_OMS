package ai.intentoms.learning.model;

import ai.intentoms.platform.error.ConflictException;
import org.junit.jupiter.api.Test;

import static ai.intentoms.learning.model.ProposalStatus.APPROVED;
import static ai.intentoms.learning.model.ProposalStatus.CANARY;
import static ai.intentoms.learning.model.ProposalStatus.DRAFT;
import static ai.intentoms.learning.model.ProposalStatus.EVALUATED;
import static ai.intentoms.learning.model.ProposalStatus.PROMOTED;
import static ai.intentoms.learning.model.ProposalStatus.REJECTED;
import static ai.intentoms.learning.model.ProposalStatus.ROLLED_BACK;
import static org.assertj.core.api.Assertions.assertThat;

/** Defect 2: the moves a proposal may make, and the plain-language refusal for the rest. */
class ProposalLifecycleTest {

    @Test
    void theAllowedMoves() {
        assertThat(ProposalLifecycle.allowed(DRAFT, EVALUATED)).isTrue();
        assertThat(ProposalLifecycle.allowed(DRAFT, REJECTED)).isTrue();
        assertThat(ProposalLifecycle.allowed(EVALUATED, EVALUATED)).isTrue();   // re-test
        assertThat(ProposalLifecycle.allowed(EVALUATED, APPROVED)).isTrue();
        assertThat(ProposalLifecycle.allowed(EVALUATED, CANARY)).isTrue();
        assertThat(ProposalLifecycle.allowed(EVALUATED, PROMOTED)).isTrue();    // automatic promotion
        assertThat(ProposalLifecycle.allowed(APPROVED, PROMOTED)).isTrue();
        assertThat(ProposalLifecycle.allowed(APPROVED, CANARY)).isTrue();
        assertThat(ProposalLifecycle.allowed(CANARY, ROLLED_BACK)).isTrue();
        assertThat(ProposalLifecycle.allowed(CANARY, EVALUATED)).isTrue();      // trial finished, back for a decision
        assertThat(ProposalLifecycle.allowed(PROMOTED, ROLLED_BACK)).isTrue();
    }

    @Test
    void theRefusedMoves() {
        assertThat(ProposalLifecycle.allowed(DRAFT, APPROVED)).isFalse();       // test it first
        assertThat(ProposalLifecycle.allowed(DRAFT, PROMOTED)).isFalse();
        assertThat(ProposalLifecycle.allowed(PROMOTED, APPROVED)).isFalse();
        assertThat(ProposalLifecycle.allowed(PROMOTED, EVALUATED)).isFalse();
        assertThat(ProposalLifecycle.allowed(PROMOTED, REJECTED)).isFalse();
        assertThat(ProposalLifecycle.allowed(PROMOTED, PROMOTED)).isFalse();
        assertThat(ProposalLifecycle.allowed(APPROVED, EVALUATED)).isFalse();
        for (ProposalStatus to : ProposalStatus.values()) {
            assertThat(ProposalLifecycle.allowed(REJECTED, to)).as("REJECTED -> " + to).isFalse();
            assertThat(ProposalLifecycle.allowed(ROLLED_BACK, to)).as("ROLLED_BACK -> " + to).isFalse();
        }
    }

    @Test
    void sourcesListEveryStatusThatMayMoveThere() {
        assertThat(ProposalLifecycle.sourcesOf(APPROVED)).containsExactlyInAnyOrder("EVALUATED", "CANARY");
        assertThat(ProposalLifecycle.sourcesOf(ROLLED_BACK)).containsExactlyInAnyOrder("CANARY", "PROMOTED");
        assertThat(ProposalLifecycle.sourcesOf(EVALUATED)).contains("DRAFT", "EVALUATED").doesNotContain("PROMOTED", "APPROVED");
    }

    @Test
    void aRefusalSaysWhereTheChangeIsAndWhatIsLeft() {
        ConflictException e = ProposalLifecycle.refused("prop_1", PROMOTED, "approve");
        assertThat(e.getMessage()).isEqualTo("Cannot approve this change: it is live. The only thing left is to roll it back.");
        assertThat(ProposalLifecycle.refused("prop_1", ROLLED_BACK, "promote").getMessage()).contains("rolled back").contains("draft a new change");
    }
}
