package ai.intentoms.learning.hooks;

import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * D4-1 = B4-2 (audit 4, EX-01/OC-01/NF-04): a cache hit of an exploring T0 decision used to copy the donor record's
 * {@code exploration} block into its details but persist the default propensity 1.0 on the column — "the record's
 * column says 1.0 while its details say 0.9" — and only a cache miss ever drew, so repeated identical inputs (an
 * 8-arm decision like {@code exception_classify}, cache {@code ttl PT1H}) realised far less than the configured
 * exploration share (the audit measured 1 draw in 28). Fixed: a cache hit draws its own exploration exactly like a
 * miss, so the realised share matches the configured one over many decisions and the propensity column is always the
 * one its own {@code details.exploration} carries. Runs on its own tenant (not the shared "test" one) so no other
 * test's holdout or kill-switch state can leak into the count.
 */
class CacheHitExplorationIntegrationTest extends DecisionFabricTestBase {
    @Autowired TenantConfigStore config;

    private static final int ARMS = 8; // matches the real exception_classify decision's 8 criteria (the audit's repro)
    private String tenant;

    @BeforeEach
    void ownTenant() {
        tenant = "d4x1-" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
    }

    @AfterEach
    void back() { TenantContext.set(TENANT); }

    private static String cachedYaml(String name) {
        return """
            decision: %s
            version: 1
            owner: test
            tier: T0
            provider: heuristic
            default: unclear
            state:
              include:
                - exception.code
                - exception.message
            questions:
              kind:
                type: choice
                instructions: What kind of exception is this?
                criteria:
                  address: Address problem such as invalid or undeliverable address
                  inventory_short: The node is short of stock
                  carrier_delay: The carrier is late with the parcel
                  payment: Payment authorization declined or capture failure
                  store_reject: Store or node rejected the release
                  system: System error, timeout or bad data
                  other: A real exception that fits none of the categories
                  unclear: Not enough information to tell
                escapeOption: unclear
            bands:
              act: { confidence_gte: 0.5 }
              escalate: { otherwise: true }
            cache:
              ttl: PT1H
            """.formatted(name);
    }

    private String install() {
        String name = "cacheexp_" + Ids.shortId().toLowerCase();
        DecisionDefinition d = studio.createDraft(name, cachedYaml(name));
        studio.publish(name, d.version(), "live", true);
        return name;
    }

    @Test
    void cacheHitsDrawTheirOwnExplorationAtTheGovernedShareInsteadOfLoggingPropensityOne() {
        String name = install();
        config.set(ExplorationFloor.KEY_PREFIX + name, 0.10);
        // Identical input every time (same exception text, nothing subject-specific): after the first cache miss,
        // every later decision is a cache hit by input hash, exactly like the audit's 25 identical POSTs.
        Map<String, Object> input = Map.of("exception", Map.of("code", "late_scan", "message", "the carrier is late with the parcel"));

        List<DecisionRecord> logged = new ArrayList<>();
        int n = 200;
        for (int i = 0; i < n; i++) {
            String id = "exc_" + i + "_" + Ids.shortId();
            DecisionResult r = decisions.decide(name, input, DecisionOptions.live("exception", id, Actor.system("test")));
            logged.add(records.find(r.recordId()).orElseThrow());
        }

        long cacheHits = logged.stream().filter(r -> Boolean.TRUE.equals(r.details().get("cached"))).count();
        assertThat(cacheHits).as("almost every decision after the first should be a cache hit (fixed TTL, identical input)").isGreaterThan(n - 5);

        // Realised exploration (over both hits and misses) should match the configured 10% share within noise, not
        // collapse to "only misses draw" (the audit measured 1/28 against a 10% share).
        List<DecisionRecord> explored = logged.stream().filter(r -> r.reasonCodes() != null && r.reasonCodes().contains("explored")).toList();
        assertThat(explored.size()).as("about 10%% of %d decisions explored: %d", n, explored.size()).isBetween(8, 35);

        // Every record's propensity column is honest: it always equals what its own details.exploration says, cache
        // hit or miss alike — never a hardcoded 1.0 while details disagree.
        for (DecisionRecord r : logged) {
            assertThat(r.details()).as("every acting T0 record draws and records it, cached or not").containsKey("exploration");
            @SuppressWarnings("unchecked") Map<String, Object> x = (Map<String, Object>) r.details().get("exploration");
            double detailPropensity = ((Number) x.get("propensity")).doubleValue();
            assertThat(r.propensity()).as("record %s: column must match details.exploration.propensity", r.id()).isEqualTo(detailPropensity);
        }

        // A cache hit that did NOT explore logs the normal arm's true propensity (1 - share = 0.9), not the old
        // hardcoded 1.0 that defeated the floor.
        List<DecisionRecord> normalCacheHits = logged.stream()
            .filter(r -> Boolean.TRUE.equals(r.details().get("cached")) && !r.reasonCodes().contains("explored")).toList();
        assertThat(normalCacheHits).as("most cache hits should not explore").isNotEmpty();
        for (DecisionRecord r : normalCacheHits) assertThat(r.propensity()).as("record %s", r.id()).isEqualTo(0.9);

        // An explored record's propensity is the floor's true draw probability (share / other arms), never silently
        // rounded up to 1.0 by caching, and matches the audit's own math for this 8-arm decision.
        for (DecisionRecord r : explored) assertThat(r.propensity()).as("record %s", r.id()).isCloseTo(0.10 / (ARMS - 1), within(1e-9));
    }
}
