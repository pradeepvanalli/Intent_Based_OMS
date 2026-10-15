package ai.intentoms.sourcing;

import ai.intentoms.sourcing.model.Promise;
import ai.intentoms.sourcing.model.PromiseRequest;

/**
 * Delivery promise at product page, cart and checkout (FR-SRC-05, NFR-02 p95 &lt; 300 ms, NFR-01 20k reads/s): a
 * deterministic date and on-time probability from cached availability, the rate card's transit table and node
 * reliability — no model call on this path. Results are cached 30 s per (tenant, sku:qty set, ZIP3, method) in memory
 * and optionally in a second-level cache (see {@code PromiseCacheL2}). Alternatives carry the trade-offs of FR-SRC-06.
 */
public interface PromiseService {
    Promise promise(PromiseRequest request);
}
