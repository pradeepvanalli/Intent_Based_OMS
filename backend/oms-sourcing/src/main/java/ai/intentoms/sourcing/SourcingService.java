package ai.intentoms.sourcing;

import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.sourcing.model.SourcingPlan;
import ai.intentoms.sourcing.model.SourcingResult;

import java.util.List;

/**
 * Intent-driven sourcing (FR-SRC-02..04, FR-SRC-07; PRD section 9 rules 1, 3 and 5).
 * <ul>
 *   <li>{@link #options}: every applicable policy's best plan with economics and the Jev composite score, best first.</li>
 *   <li>{@link #source}: picks the policy from the order's {@code IntentProfile} (channel default when unclear), ranks,
 *       applies the margin floor (GR-01, FR-SRC-04) — a breach returns {@code REQUIRES_APPROVAL} with alternative offers
 *       and persists nothing — otherwise persists fulfilment groups and appends {@code order.sourced}.</li>
 *   <li>{@link #resource}: store reject / timeout: penalises the rejecting node per sku, marks its groups rejected and
 *       re-sources without it (FR-SRC-07).</li>
 * </ul>
 * AI problems never surface as exceptions: a failed decision degrades to neutral soft scores (NFR-05).
 */
public interface SourcingService {
    List<SourcingPlan> options(String orderId);

    SourcingResult source(String orderId, Actor actor);

    /** Re-sources after a reject from every node that currently holds an active fulfilment group. */
    SourcingResult resource(String orderId, String reason, Actor actor);

    /** Re-sources after a reject from {@code nodeId} (null = every node with an active group). */
    SourcingResult resource(String orderId, String nodeId, String reason, Actor actor);
}
