package ai.intentoms.sourcing;

import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.SourcingRequest;

import java.util.List;

/**
 * Deterministic candidate generation (FR-SRC-01): up to {@code limit} feasible nodes for a request using inventory,
 * capabilities (ship-from-store, BOPIS, hazmat, same-day), remaining daily capacity, cutoffs and distance. Same input
 * and same inventory always yield the same ordered list, so replay (FR-SIM-01) reproduces the run.
 */
public interface CandidateGenerator {
    /** @param limit maximum candidates; {@code <= 0} means the tenant default {@code sourcing.candidate_limit} (100). */
    List<Candidate> generate(SourcingRequest req, int limit);

    default List<Candidate> generate(SourcingRequest req) { return generate(req, 0); }
}
