package ai.intentoms.learning.model;

import ai.intentoms.platform.error.ConflictException;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only status changes a proposal may make (§9.1): DRAFT → EVALUATED → APPROVED → CANARY / PROMOTED → ROLLED_BACK,
 * with REJECTED reachable from any open status. Re-evaluation is allowed only before approval. A canary that ends
 * returns its proposal to APPROVED or EVALUATED (or REJECTED when the candidate was worse), and a canary stopped for a
 * guardrail breach is ROLLED_BACK. REJECTED and ROLLED_BACK are final: a new attempt is a new proposal.
 *
 * <p>The repository enforces these with conditional updates, so two people acting at once cannot both win.
 */
public final class ProposalLifecycle {
    private ProposalLifecycle() {}

    private static final Map<ProposalStatus, Set<ProposalStatus>> ALLOWED = Map.of(
        ProposalStatus.DRAFT, EnumSet.of(ProposalStatus.EVALUATED, ProposalStatus.REJECTED),
        ProposalStatus.EVALUATED, EnumSet.of(ProposalStatus.EVALUATED, ProposalStatus.APPROVED, ProposalStatus.REJECTED, ProposalStatus.CANARY, ProposalStatus.PROMOTED),
        ProposalStatus.APPROVED, EnumSet.of(ProposalStatus.PROMOTED, ProposalStatus.CANARY, ProposalStatus.REJECTED),
        ProposalStatus.CANARY, EnumSet.of(ProposalStatus.APPROVED, ProposalStatus.EVALUATED, ProposalStatus.REJECTED, ProposalStatus.PROMOTED, ProposalStatus.ROLLED_BACK),
        ProposalStatus.PROMOTED, EnumSet.of(ProposalStatus.ROLLED_BACK),
        ProposalStatus.REJECTED, EnumSet.noneOf(ProposalStatus.class),
        ProposalStatus.ROLLED_BACK, EnumSet.noneOf(ProposalStatus.class));

    public static boolean allowed(ProposalStatus from, ProposalStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** Every status from which {@code to} can be reached, as names for a SQL {@code in (...)}. */
    public static List<String> sourcesOf(ProposalStatus to) {
        return ALLOWED.entrySet().stream().filter(e -> e.getValue().contains(to)).map(e -> e.getKey().name()).sorted().toList();
    }

    /** Throws a 409 with a sentence a person can act on when {@code action} would move {@code p} somewhere it may not go. */
    public static void require(Proposal p, ProposalStatus to, String action) {
        if (!allowed(p.status(), to)) throw refused(p.proposalId(), p.status(), action);
    }

    public static ConflictException refused(String proposalId, ProposalStatus current, String action) {
        return new ConflictException("Cannot " + action + " this change: it is " + words(current) + ". " + next(current),
            Map.of("proposalId", proposalId, "status", current.name(), "action", action));
    }

    static String words(ProposalStatus s) {
        return switch (s) {
            case DRAFT -> "a draft that has not been tested yet";
            case EVALUATED -> "tested and waiting for approval";
            case APPROVED -> "approved and waiting to go live";
            case CANARY -> "being tried on some customers";
            case PROMOTED -> "live";
            case REJECTED -> "rejected";
            case ROLLED_BACK -> "rolled back";
        };
    }

    static String next(ProposalStatus s) {
        return switch (s) {
            case DRAFT -> "Test it on past decisions first.";
            case EVALUATED -> "Approve, reject or try it first.";
            case APPROVED -> "It can go live, be tried on some customers, or be rejected.";
            case CANARY -> "Wait for the trial to finish, or roll it back.";
            case PROMOTED -> "The only thing left is to roll it back.";
            case REJECTED, ROLLED_BACK -> "It is closed; draft a new change to try again.";
        };
    }
}
