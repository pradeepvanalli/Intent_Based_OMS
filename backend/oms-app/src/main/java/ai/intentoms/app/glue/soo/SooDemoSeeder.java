package ai.intentoms.app.glue.soo;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.inventory.learning.InventoryLearningRepository;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.experiment.ExperimentManager;
import ai.intentoms.learning.explain.WeeklyLearningDigestService;
import ai.intentoms.learning.governance.GovernedLearner;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.governance.LiveFamilies;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.WeightLever;
import ai.intentoms.learning.promotion.PromotionService;
import ai.intentoms.learning.service.CoverageMonitor;
import ai.intentoms.learning.service.ObjectiveService;
import ai.intentoms.learning.service.RewardMaturityService;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.orchestration.seed.DemoSeedCompletedEvent;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.simulation.learning.ProposalEvaluationService;
import ai.intentoms.sourcing.config.SourcingConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference data for the self-optimizing layer on the demo tenant, so a first-time user (and the Playwright suite)
 * opens the Learning console to a populated, believable system instead of empty tables. Runs once per tenant after the
 * main demo seed ({@link DemoSeedCompletedEvent}), gated by {@code oms.demo.seed} and {@code oms.demo.soo-seed}.
 *
 * <p>Everything goes through the real services wherever one exists: history is fed to the real event-driven learners,
 * rewards come from {@link RewardMaturityService}, evaluation reports from the real off-policy evaluator, promotions and
 * rollbacks from {@link PromotionService} (so rollback snapshots exist), the canary and holdout from
 * {@link ExperimentManager}, the paused learner from {@link CoverageMonitor} finding a real coverage gap, the batch
 * learners' hypotheses and drafts from running them, and the objective from the draft-then-approve flow.
 *
 * <p>Every governance step it takes runs as a clearly labelled demo actor, never a real user: {@code
 * system:demo-seed.strategy} drafts, {@code system:demo-seed.admin} approves and promotes (so author-cannot-approve still
 * holds), and a proposal a learner would have written is authored by {@code system:demo-seed.<learner>}. Decision
 * records the seeder writes carry {@code system:demo-seed}; the tenant-config marker {@code learning.demo_seed} lists
 * every seeded id. The UI badges all of these as demo data, and guardrail monitors ignore them.
 */
@Component
public class SooDemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(SooDemoSeeder.class);
    public static final String MARKER = "learning.demo_seed";
    /** 3: live-use ranges checked against the family domains (B-1), and reference data for the fraud, calibration, lane and buffer learners. */
    public static final int VERSION = 3;
    static final String SEED = "system:demo-seed";
    /** Seeded promotions have no live evidence behind them; the EX-07 gate records this reason as the override. */
    static final String SEEDED_OVERRIDE = "Demo reference data: seeded promotion with no live evidence";
    static final String STRATEGY = "system:demo-seed.strategy";
    static final String ADMIN = "system:demo-seed.admin";
    /** FS-04: a weight learner raises a hypothesis and the (template, no-LLM) Author drafts the WeightChange; seeded on its behalf. */
    static final String TEMPLATE_AUTHOR = "system:demo-seed.template-author";
    /** A proposal the named learner would have drafted, written by the seeder on its behalf. */
    static String byLearner(String learnerKey) { return "system:demo-seed." + learnerKey; }

    // Deterministic ids F7 (UI) and F8 (Playwright) can rely on.
    public static final String PROP_LIVE_NODE_REJECT = "prop_demo_live_node_reject";
    public static final String PROP_LIVE_NODE_ON_TIME = "prop_demo_live_node_on_time";
    public static final String PROP_LIVE_LANE_TRANSIT = "prop_demo_live_lane_transit";
    public static final String PROP_SOURCING_THRESHOLD = "prop_demo_sourcing_threshold";
    public static final String PROP_WEIGHTS = "prop_demo_sourcing_weights";
    public static final String PROP_SLA_CANARY = "prop_demo_sla_canary";
    public static final String PROP_RETURN_THRESHOLD = "prop_demo_return_threshold";
    public static final String PROP_LIVE_LANE_CALIBRATION = "prop_demo_live_lane_calibration";
    public static final String PROP_RTO_THRESHOLD = "prop_demo_rto_threshold";
    public static final String PROP_APPEASEMENT = "prop_demo_appeasement";
    public static final String PROP_LIVE_RESERVATION_TTL = "prop_demo_live_reservation_ttl";
    public static final String PROP_T0_EXCEPTION = "prop_demo_t0_exception_triage";

    private final SooDemoHistory history;
    private final RewardMaturityService rewards;
    private final CoverageMonitor coverage;
    private final ProposalRepository proposals;
    private final HypothesisRepository hypotheses;
    private final ProposalEvaluationService evaluator;
    private final PromotionService promotion;
    private final ExperimentManager experiments;
    private final LearnerGovernance governance;
    private final LearnerRegistry registry;
    private final List<GovernedLearner> learners;
    private final ObjectiveService objectives;
    private final WeeklyLearningDigestService digests;
    private final InventoryLearningRepository inventory;
    private final DecisionDefinitionRepository definitions;
    private final LearningEvents events;
    private final TenantConfigStore config;
    private final JdbcClient jdbc;
    private final ai.intentoms.learning.governance.ParameterResolver parameters;
    private final ai.intentoms.app.glue.AgentDemoSeeder agentSeeder;
    private final ai.intentoms.agent.mandate.MandateRepository mandates;
    private final ai.intentoms.platform.ledger.EventStore eventStore;
    private final boolean enabled;
    private final String tenant;
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    public SooDemoSeeder(SooDemoHistory history, RewardMaturityService rewards, CoverageMonitor coverage, ProposalRepository proposals, HypothesisRepository hypotheses,
                         ProposalEvaluationService evaluator, PromotionService promotion, ExperimentManager experiments, LearnerGovernance governance,
                         LearnerRegistry registry, List<GovernedLearner> learners, ObjectiveService objectives, WeeklyLearningDigestService digests,
                         InventoryLearningRepository inventory, DecisionDefinitionRepository definitions, LearningEvents events,
                         TenantConfigStore config, JdbcClient jdbc, ai.intentoms.learning.governance.ParameterResolver parameters,
                         ai.intentoms.app.glue.AgentDemoSeeder agentSeeder, ai.intentoms.agent.mandate.MandateRepository mandates,
                         ai.intentoms.platform.ledger.EventStore eventStore,
                         @Value("${oms.demo.seed:false}") boolean demoSeed, @Value("${oms.demo.soo-seed:true}") boolean sooSeed,
                         @Value("${oms.demo.tenant:demo}") String tenant) {
        this.history = history;
        this.rewards = rewards;
        this.coverage = coverage;
        this.proposals = proposals;
        this.hypotheses = hypotheses;
        this.evaluator = evaluator;
        this.promotion = promotion;
        this.experiments = experiments;
        this.governance = governance;
        this.registry = registry;
        this.learners = learners;
        this.objectives = objectives;
        this.digests = digests;
        this.inventory = inventory;
        this.definitions = definitions;
        this.events = events;
        this.config = config;
        this.jdbc = jdbc;
        this.parameters = parameters;
        this.agentSeeder = agentSeeder;
        this.mandates = mandates;
        this.eventStore = eventStore;
        this.enabled = demoSeed && sooSeed;
        this.tenant = tenant;
    }

    public boolean enabled() { return enabled; }
    public String tenant() { return tenant; }
    public boolean running(String tenantId) { return running.getOrDefault(tenantId, false); }

    @EventListener
    public void onDemoSeedCompleted(DemoSeedCompletedEvent e) {
        if (!enabled) { log.info("Self-optimizing demo seed disabled (oms.demo.seed / oms.demo.soo-seed)"); return; }
        if (e.tenantIds() != null && !e.tenantIds().isEmpty() && !e.tenantIds().contains(tenant)) return;
        try { seed(tenant); }
        catch (RuntimeException ex) { log.error("Self-optimizing demo seed failed for tenant {}: {}", tenant, ex.toString(), ex); }
    }

    /** Seeds the tenant once; returns the marker (existing or new). Safe to call again: a seeded tenant is left alone. */
    public Map<String, Object> seed(String tenantId) {
        var prevAuth = SecurityContextHolder.getContext().getAuthentication();
        running.put(tenantId, true);
        try {
            SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(Principal.system(tenantId)));
            return TenantContext.supplyAs(tenantId, this::seedCurrentTenant);
        } finally {
            running.put(tenantId, false);
            SecurityContextHolder.getContext().setAuthentication(prevAuth);
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> marker() {
        return config.get(MARKER).filter(Map.class::isInstance).map(m -> (Map<String, Object>) m);
    }

    private Map<String, Object> seedCurrentTenant() {
        Optional<Map<String, Object>> existing = marker();
        if (existing.isPresent()) {
            log.info("Self-optimizing demo data already seeded for tenant {} (version {})", TenantContext.require(), existing.get().get("version"));
            return existing.get();
        }
        if (definitions.byStatus("sourcing_node_score", "live").isEmpty()) {
            log.warn("Self-optimizing demo seed skipped for tenant {}: the demo decisions are not loaded", TenantContext.require());
            return Map.of("complete", false, "skipped", "demo decisions not loaded");
        }
        long started = System.currentTimeMillis();
        Instant now = Instant.now();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> counts = new LinkedHashMap<>();
        // What the seed deliberately left out, and why (GET /learning/seed-status lists it next to what was seeded).
        List<Map<String, Object>> skipped = new ArrayList<>();

        Map<String, String> ids = new LinkedHashMap<>();
        // The holdout exists (back-dated) before the history, so the history is tagged by the live router's own assignment.
        String holdoutId = backdatedHoldout();
        ids.put("holdoutExperiment", holdoutId);
        SooDemoHistory.Result h = history.generate(now, customer -> parameters.inHoldout("customer:" + customer), holdoutId);
        counts.put("decisionRecords", h.recordsByDecision());
        counts.put("learnerEventsFed", h.learnerEvents());
        counts.put("holdoutOrders", h.holdoutOrders());
        counts.put("holdoutTopUpOrders", h.holdoutTopUp());
        counts.put("exceptionChecks", h.exceptionRecords());
        counts.put("maturedPaymentRiskDecisions", h.fraudCohortRecords());
        counts.put("labelledHumanResolutions", h.labelledResolutions());
        counts.put("promiseImpressions", h.promiseImpressions());
        step(warnings, "rewards", () -> counts.put("rewardsComputed", rewards.sweepOnce()));
        step(warnings, "supplier receipts", () -> counts.put("supplierReceipts", supplierReceipts(now)));
        step(warnings, "stock age", () -> counts.put("agedPositions", ageStock(now)));
        step(warnings, "agent mandates", () -> counts.put("agentMandates", agentHistory(now)));
        step(warnings, "autonomy", this::autonomy);

        step(warnings, "live use", () -> liveUse(ids, skipped));
        step(warnings, "decision threshold promoted", () -> promotedThreshold(ids, now));
        step(warnings, "weights rolled back", () -> rolledBackWeights(ids));
        step(warnings, "canary", () -> canary(ids));
        step(warnings, "approved", () -> approvedThreshold(ids));
        step(warnings, "evaluated", () -> evaluatedLiveUse(ids, skipped));
        step(warnings, "rejected", () -> rejectedAppeasement(ids));
        step(warnings, "t0 shadow", () -> t0WithShadow(ids));

        Map<String, String> learnerRuns = new TreeMap<>();
        for (GovernedLearner l : learners) {
            if (!l.runnable()) continue;
            try { l.runNow(); learnerRuns.put(l.descriptor().key(), "ran"); }
            catch (RuntimeException ex) { learnerRuns.put(l.descriptor().key(), "failed: " + ex.getMessage()); }
        }
        counts.put("learnerRuns", learnerRuns);
        step(warnings, "draft", () -> draftFallback(ids));
        step(warnings, "objective", () -> objective(ids));
        step(warnings, "coverage", coverage::sweepOnce);
        step(warnings, "digest", () -> ids.put("digest", digests.publish(7, SEED).digestId()));

        counts.putAll(tally());
        counts.put("referenceFacts", referenceFacts());
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("version", VERSION);
        marker.put("complete", true);
        marker.put("seededAt", Instant.now().toString());
        marker.put("durationMs", System.currentTimeMillis() - started);
        marker.put("counts", counts);
        marker.put("ids", ids);
        marker.put("liveFamilies", parameters == null ? List.of() : jdbc.sql("select parameter from learned_parameter_live where tenant_id = :t and status = 'LIVE' order by parameter")
            .param("t", TenantContext.require()).query(String.class).list());
        marker.put("demoActors", List.of(SEED, STRATEGY, ADMIN, byLearner("<learner>")));
        marker.put("pausedLearners", governance.states().stream().filter(s -> s.paused()).map(s -> Map.of("learnerKey", s.learnerKey(), "reason", s.pausedReason())).toList());
        marker.put("skipped", skipped);
        if (!warnings.isEmpty()) marker.put("warnings", warnings);
        config.set(MARKER, marker);
        log.info("Self-optimizing demo data seeded for tenant {} in {} ms: {}", TenantContext.require(), marker.get("durationMs"), counts);
        if (!skipped.isEmpty()) log.info("Self-optimizing demo seed left out: {}", skipped);
        if (!warnings.isEmpty()) log.warn("Self-optimizing demo seed warnings: {}", warnings);
        return marker;
    }

    private static void step(List<String> warnings, String name, Runnable body) {
        try { body.run(); }
        catch (RuntimeException e) {
            warnings.add(name + ": " + e.getMessage());
            log.warn("Self-optimizing demo seed step '{}' failed: {}", name, e.toString());
        }
    }

    // ------------------------------------------------------------------ side data

    /** IU-05: ASN receipts from three suppliers of different reliability (the inbound-reliability learner turns them into facts). */
    private int supplierReceipts(Instant now) {
        String[][] suppliers = {{"SUP-ACME", "0.99", "0"}, {"SUP-BOLT", "0.86", "2"}, {"SUP-CORAL", "0.7", "4"}};
        String[] skus = {"TEE-BLK-M", "HOOD-GRY-M", "JEAN-IND-32", "SNK-RUN-10"};
        int n = 0;
        for (String[] s : suppliers) {
            for (int k = 0; k < 8; k++) {
                int expected = 100 + 20 * (k % 3);
                int received = (int) Math.round(expected * Double.parseDouble(s[1]) + (k % 2));
                Instant expectedAt = now.minus(Duration.ofDays(60 - k * 7L));
                Instant receivedAt = expectedAt.plus(Duration.ofDays(Long.parseLong(s[2]) + (k % 2)));
                if (inventory.insertReceipt(new InventoryLearningRepository.Receipt(s[0], s[0] + "-ASN-" + (1000 + k), skus[k % skus.length], k % 2 == 0 ? "DC-NJ" : "DC-NV",
                    expected, Math.min(received, expected), expectedAt, receivedAt)).isPresent()) n++;
            }
        }
        return n;
    }

    /** IU-03: back-date the receipt of a few positions so the stock-age learner finds aged and stranded stock. */
    private int ageStock(Instant now) {
        Object[][] aged = {{"BOOT-LTH-9", "ST-LA-02", 140}, {"JKT-DWN-L", "DC-TX", 95}, {"PERF-50ML", "ST-AUS-03", 62}, {"CNDL-SOY", "3PL-ATL", 38}};
        int n = 0;
        for (Object[] a : aged) {
            n += jdbc.sql("update inventory_position set received_at = :at where tenant_id = :t and sku = :sku and node_id = :node and supply_type = 'on_hand'")
                .param("at", java.sql.Timestamp.from(now.minus(Duration.ofDays((int) a[2])))).param("t", TenantContext.require()).param("sku", a[0]).param("node", a[1]).update();
        }
        return n;
    }

    /**
     * FD-05 for real: mandates the three demo agents placed orders under, a prompt-injection hit and a chargeback dispute,
     * so the agent-reputation learner (run with the other batch learners below) computes each agent's reputation from
     * evidence instead of the seeder writing facts in its name. Agents are registered first (the agent seed normally
     * runs after this one).
     */
    private int agentHistory(Instant now) {
        agentSeeder.seed();
        Object[][] agents = {{"shopbot-ucp", 14, 1, 0, 1}, {"concierge-acp", 9, 0, 2, 0}, {"ops-copilot", 6, 0, 0, 0}};
        int n = 0;
        for (Object[] a : agents) {
            String agent = (String) a[0];
            int count = (int) a[1], invalid = (int) a[2], revoked = (int) a[3], disputes = (int) a[4];
            for (int k = 0; k < count; k++) {
                String id = String.format(java.util.Locale.ROOT, "mdt-demo-%s-%02d", agent, k + 1);
                if (mandates.find(id).isPresent()) continue;
                Instant issued = now.minus(Duration.ofDays(80 - k * 5L));
                boolean valid = k >= invalid;
                String status = k < revoked ? ai.intentoms.agent.mandate.Mandate.REVOKED : ai.intentoms.agent.mandate.Mandate.CONSUMED;
                mandates.insert(new ai.intentoms.agent.mandate.Mandate(id, agent, String.format(java.util.Locale.ROOT, "cust-demo-%03d", 10 + k), new ai.intentoms.agent.mandate.MandateScope("demo", List.of("apparel"), 3),
                    ai.intentoms.finance.model.Money.of("150.00", "USD"), "Buy everyday basics under $150 for the buyer", "demo-signature", valid, null, 0.02, null,
                    issued, issued.plus(Duration.ofDays(7)), status, List.of(), issued, issued));
                n++;
            }
            if ("concierge-acp".equals(agent)) agentEvent(agent, "agent.injection_flagged", Map.of("reason", "instruction text tried to raise the spend limit", "risk", 0.91), now.minus(Duration.ofDays(20)));
            for (int d = 0; d < disputes; d++) agentEvent(agent, "agent.dispute_recorded", Map.of("orderId", "demo-hist-0042", "reasonCode", "fraudulent", "amount", 88.0), now.minus(Duration.ofDays(12)));
        }
        return n;
    }

    private void agentEvent(String agent, String type, Map<String, Object> payload, Instant at) {
        Map<String, Object> p = new LinkedHashMap<>(payload);
        p.put("demoData", true);
        eventStore.append(ai.intentoms.agent.registry.AgentRegistry.AGGREGATE, agent, new ai.intentoms.platform.ledger.NewEvent(type, p, Actor.parse(SEED)));
    }

    private void autonomy() {
        registry.ensureRegistered();
        governance.setAutonomy("node_reliability", AutonomyLevel.L2_ACT_WITH_APPROVAL, STRATEGY);
        governance.setAutonomy("lane_transit", AutonomyLevel.L2_ACT_WITH_APPROVAL, STRATEGY);
        // EX-01: exception triage (T0) explores 10% of its live classifications, so its records log real propensities
        // and a threshold change on it is evaluated with IPS that has support (ExplorationFloor governs the rest).
        config.set(ai.intentoms.learning.hooks.ExplorationFloor.KEY_PREFIX + "exception_classify", 0.10);
    }

    // ------------------------------------------------------------------ proposals in every status

    /**
     * PROMOTED live use: learned values reach live sourcing, promise, monitoring and payment-risk decisions, each bounded
     * by a range in the family's own units (B-1, {@link LiveFamilies}). A family is switched on only when its range fits
     * the family's domain and a learner feeding it has learned something; otherwise it is left off and listed under
     * {@code skipped} with the reason. Warn-early is a 0-4 breach-risk score: a range of 1.5 to 3.5 keeps warnings for
     * orders that are really at risk (0.3-0.9 once warned every order that scored 1, audit defect B-1).
     */
    private void liveUse(Map<String, String> ids, List<Map<String, Object>> skipped) {
        Object[][] families = {
            {PROP_LIVE_NODE_REJECT, "sourcing.node_reject_rate", "node_reliability", 0.0, 0.5, 0.15, "Use each node's learned reject rate in live sourcing instead of the static store default"},
            {PROP_LIVE_NODE_ON_TIME, "sourcing.node_on_time_rate", "node_reliability", 0.5, 1.0, null, "Give the sourcing score each node's observed on-time rate"},
            {PROP_LIVE_LANE_TRANSIT, "promise.lane_transit_days", "lane_transit", 1.0, 8.0, 2.0, "Promise dates from learned carrier-lane transit times, within two days of the static table"},
            {PROP_LIVE_LANE_CALIBRATION, "promise.lane_calibration", "lane_transit", -0.3, 0.3, 0.3, "Correct the stated on-time probability by each lane's observed calibration gap"},
            // The bandit makes live sourcing explore, so new orders log real propensities for off-policy evaluation.
            {"prop_demo_live_bandit", "sourcing.policy_bandit", "sourcing_policy_bandit", null, null, null, "Let the sourcing policy explorer try alternative policies on up to 10% of orders"},
            {"prop_demo_live_reject_penalty", "sourcing.reject_penalty", "node_reliability", 0.1, 2.0, 0.5, "Use each node's learned, decaying reject penalty when a store rejects"},
            {"prop_demo_live_customer_prefs", "customer.preferences", "ci_preference", null, null, null, "Give decisions each customer's learned preferences (split tolerance, speed vs cost, pickup, channel)"},
            {"prop_demo_live_backlog", "sourcing.node_backlog_breach", "node_backlog_breach", 0.0, 1.0, 0.2, "Steer orders away from nodes whose backlog is about to cause missed promises"},
            {"prop_demo_live_warn_early", "monitoring.warn_early_threshold", "warn_early", 1.5, 3.5, 1.0, "Warn customers about a likely delay at the learned breach-risk score (0-4)"},
            {"prop_demo_live_buffer", "promise.buffer_days", "promise_buffer", 0.0, 3.0, 1.0, "Add the learned promise buffer per customer segment"},
            {"prop_demo_live_fraud_signals", "fraud.signal_risk", "fraud_signals", 0.0, 1.0, 0.2, "Give the payment-risk check the learned fraud rate of each signal level"},
            {"prop_demo_live_backorder", "inventory.backorder_conversion", "inventory_backorder", 0.0, 1.0, 0.2, "Give the backorder decision each option's learned conversion rate"},
            // DF-02: decisions whose learned curve has a well-filled bin (return_risk, sla_breach_risk) use it live.
            {"prop_demo_live_calibration", "decision.calibration", "confidence_calibrator", null, null, null, "Use each decision's learned confidence curve, so a 60%-sure answer is treated as right as often as such answers really are"},
        };
        List<String> promoted = new ArrayList<>();
        for (Object[] m : families) {
            try {
                if (liveUseProposal((String) m[0], (String) m[1], (String) m[2], (Double) m[3], (Double) m[4], (Double) m[5], (String) m[6], true, skipped)) promoted.add((String) m[0]);
            } catch (RuntimeException e) {
                log.warn("Demo live use of {} skipped: {}", m[1], e.getMessage());
                skip(skipped, (String) m[1], "could not be promoted: " + e.getMessage());
            }
        }
        ids.put("promotedLiveUse", String.join(",", promoted));
    }

    /** EVALUATED live use, waiting for an approver (deliberately not live, so the "not promoted" path stays visible). */
    private void evaluatedLiveUse(Map<String, String> ids, List<Map<String, Object>> skipped) {
        liveUseProposal(PROP_LIVE_RESERVATION_TTL, "inventory.reservation_ttl", "inventory_reservation_ttl", 5.0, 1440.0, 60.0,
            "Hold carts for the learned time before releasing their stock", false, skipped);
        ids.put("evaluated", PROP_LIVE_RESERVATION_TTL);
        ids.put("notLive", "inventory.reservation_ttl");
    }

    private static void skip(List<Map<String, Object>> skipped, String what, String reason) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("item", what);
        s.put("reason", reason);
        skipped.add(s);
    }

    /**
     * Why a family cannot be used live yet, if it cannot: no learner feeds it, or none of the learners that do has a
     * current learned value. Batch learners feeding it are run once first (the main learner run comes later in the seed).
     */
    private Optional<String> missingEvidence(String family) {
        List<GovernedLearner> feeders = learners.stream().filter(l -> l.descriptor().liveParameters().contains(family)).toList();
        if (feeders.isEmpty()) return Optional.of("no learner feeds " + family);
        List<String> keys = feeders.stream().map(l -> l.descriptor().key()).toList();
        if (currentFacts(keys) > 0) return Optional.empty();
        for (GovernedLearner l : feeders) {
            if (!l.runnable()) continue;
            try { l.runNow(); } catch (RuntimeException e) { log.warn("Demo seed: running {} for live-use evidence failed: {}", l.descriptor().key(), e.toString()); }
        }
        if (currentFacts(keys) > 0) return Optional.empty();
        String names = String.join(" / ", feeders.stream().map(l -> l.descriptor().displayName()).toList());
        return Optional.of(names + " has not learned any value yet, so there is nothing to use in live decisions");
    }

    private long currentFacts(List<String> learnerKeys) {
        return jdbc.sql("select count(*) from learned_fact where tenant_id = :t and learner in (:keys) and valid_to is null")
            .param("t", TenantContext.require()).param("keys", learnerKeys).query(Long.class).single();
    }

    /**
     * Drafts (and, with {@code promote}, evaluates, approves and promotes) a live-use proposal. Returns false, with the
     * reason added to {@code skipped}, when the range does not fit the family's domain or, for a promotion, when there is
     * no learned value behind it.
     */
    private boolean liveUseProposal(String id, String family, String learner, Double min, Double max, Double maxStep, String reason, boolean promote,
                                    List<Map<String, Object>> skipped) {
        List<String> problems = LiveFamilies.validate(family, min, max, maxStep);
        if (!problems.isEmpty()) {
            skip(skipped, family, "range does not fit the family's values: " + String.join("; ", problems));
            return false;
        }
        if (promote) {
            Optional<String> missing = missingEvidence(family);
            if (missing.isPresent()) {
                skip(skipped, family, missing.get());
                return false;
            }
        }
        Map<String, Object> to = new LinkedHashMap<>();
        to.put("enabled", true);
        to.put("learner", learner);
        if (min != null) to.put("min", min);
        if (max != null) to.put("max", max);
        if (maxStep != null) to.put("maxStep", maxStep);
        Proposal p = draft(id, new ArtifactRef("parameter", "live:" + family), 0,
            List.of(new PolicyChange.ParameterChange("live:" + family, Map.of("enabled", false), to)), reason,
            ai.intentoms.learning.promotion.LiveUseTier.of(family, definitions), STRATEGY, null); // D-3: payment_risk / appeasement families are T2
        evaluate(p);
        if (!promote) return true;
        approve(id, ADMIN);
        promotion.promote(id, Actor.parse(ADMIN), SEEDED_OVERRIDE);
        return true;
    }

    /** PROMOTED decision change with rollback available: the node score acts a little sooner (replay shows confirm-band orders shipped later). */
    private void promotedThreshold(Map<String, String> ids, Instant now) {
        int version = liveVersion("sourcing_node_score");
        String hyp = hypothesis("hyp_demo_sourcing_threshold", Map.of("decision", "sourcing_node_score"), "on_time", Direction.INCREASE,
            Map.of("action", "move_threshold", "decision", "sourcing_node_score", "band", "act", "from", 0.80, "to", 0.75),
            List.of("Node scores in the 0.75-0.80 confirm band waited a median 3.5 hours for a person", "Those orders missed their promise about 25 points more often",
                "Accuracy of the act band holds above 0.75 on the golden set"), "Acting slightly sooner on node scores cuts the wait for confirmation without hurting accuracy.");
        Proposal p = draft(PROP_SOURCING_THRESHOLD, new ArtifactRef("decision", "sourcing_node_score"), version,
            List.of(new PolicyChange.ThresholdChange("sourcing_node_score", "act", 0.80, 0.75)), "Act on node scores from 0.75 instead of 0.80",
            PolicyChangeset.RiskTier.T1, byLearner("band_threshold"), hyp);
        evaluate(p);
        approve(PROP_SOURCING_THRESHOLD, STRATEGY);
        promotion.promote(PROP_SOURCING_THRESHOLD, Actor.parse(ADMIN), SEEDED_OVERRIDE);
        ids.put("promotedDecision", PROP_SOURCING_THRESHOLD);
    }

    /**
     * ROLLED_BACK: a plan-weight change promoted, then reverted when on-time slipped in its first week. As FS-04 works
     * now, the sourcing weight learner raised a typed weight hypothesis ({@link WeightLever}) and the template Author
     * (no LLM) drafted the {@code WeightChange} from it, so the proposal is the Author's.
     */
    private void rolledBackWeights(Map<String, String> ids) {
        if (config.get(SourcingConfigKeys.WEIGHTS).isEmpty()) {
            config.set(SourcingConfigKeys.WEIGHTS, Map.of("cost", SourcingConfigKeys.DEFAULT_W_COST, "time", SourcingConfigKeys.DEFAULT_W_TIME,
                "soft", SourcingConfigKeys.DEFAULT_W_SOFT, "split", SourcingConfigKeys.DEFAULT_W_SPLIT));
        }
        Map<String, Double> current = new LinkedHashMap<>();
        current.put("cost", SourcingConfigKeys.DEFAULT_W_COST);
        current.put("time", SourcingConfigKeys.DEFAULT_W_TIME);
        current.put("soft", SourcingConfigKeys.DEFAULT_W_SOFT);
        current.put("split", SourcingConfigKeys.DEFAULT_W_SPLIT);
        Map<String, Double> proposed = new LinkedHashMap<>(current);
        proposed.put("cost", 0.45);
        WeightLever lever = new WeightLever(SourcingConfigKeys.WEIGHTS, current, proposed, Map.of("cost", 0.21, "time", 0.08, "soft", 0.02, "split", -0.05), 312, 90);
        String hyp = hypothesis("hyp_demo_sourcing_weights", Map.of("target", lever.target()), "reward", Direction.INCREASE, lever.toLever(),
            List.of("cost component vs reward correlation 0.21", "time component vs reward correlation 0.08"), "Plan-score weights should follow the components that go with better outcomes.");
        Proposal p = draft(PROP_WEIGHTS, new ArtifactRef("config", SourcingConfigKeys.WEIGHTS), 0, lever.changes(),
            "Weigh cost a little more in the sourcing plan score", PolicyChangeset.RiskTier.T1, TEMPLATE_AUTHOR, hyp);
        evaluate(p);
        approve(PROP_WEIGHTS, STRATEGY);
        promotion.promote(PROP_WEIGHTS, Actor.parse(ADMIN), SEEDED_OVERRIDE);
        promotion.rollback(PROP_WEIGHTS, Actor.parse(ADMIN), "On-time delivery slipped 2 points in the first week; reverting while we look at store nodes");
        ids.put("rolledBack", PROP_WEIGHTS);
    }

    /** CANARY: breach-risk acts only from 0.86; 10% of orders get the candidate, sticky per customer. */
    private void canary(Map<String, String> ids) {
        int version = liveVersion("sla_breach_risk");
        String hyp = hypothesis("hyp_demo_sla_canary", Map.of("decision", "sla_breach_risk"), "on_time", Direction.INCREASE,
            Map.of("action", "move_threshold", "decision", "sla_breach_risk", "band", "act", "from", 0.80, "to", 0.86),
            List.of("Breach scores that acted on their own between 0.80 and 0.86 missed their promise far more often",
                "Orders a person re-promised in the confirm band were contacted earlier and complained less"),
            "Sending low-confidence breach scores to a person before acting should keep more promises.");
        Proposal p = draft(PROP_SLA_CANARY, new ArtifactRef("decision", "sla_breach_risk"), version,
            List.of(new PolicyChange.ThresholdChange("sla_breach_risk", "act", 0.80, 0.86)), "Breach risk acts on its own only from 0.86",
            PolicyChangeset.RiskTier.T1, byLearner("band_threshold"), hyp);
        evaluate(p);
        if (!positive(proposals.get(PROP_SLA_CANARY))) approve(PROP_SLA_CANARY, STRATEGY);
        var e = experiments.start(PROP_SLA_CANARY, ExperimentMode.CANARY, 0.10, Map.of("durationDays", 14, "minPerArm", 200, "alpha", 0.05), SEED);
        ids.put("canary", PROP_SLA_CANARY);
        ids.put("canaryExperiment", e.experimentId());
    }

    /** APPROVED, not yet promoted: return risk refunds at once only from 0.93. */
    private void approvedThreshold(Map<String, String> ids) {
        int version = liveVersion("return_risk");
        String hyp = hypothesis("hyp_demo_return_threshold", Map.of("decision", "return_risk"), "fraud_and_abuse_loss", Direction.DECREASE,
            Map.of("action", "move_threshold", "decision", "return_risk", "band", "act", "from", 0.90, "to", 0.93),
            List.of("Returns refunded at once on a 0.90-0.93 score were confirmed abuse four times as often", "Below 0.93 an inspection first costs about a day"),
            "Inspect returns before refunding unless the score is clearly low risk.");
        Proposal p = draft(PROP_RETURN_THRESHOLD, new ArtifactRef("decision", "return_risk"), version,
            List.of(new PolicyChange.ThresholdChange("return_risk", "act", 0.90, 0.93)), "Return risk refunds at once only from 0.93",
            PolicyChangeset.RiskTier.T2, byLearner("band_threshold"), hyp);
        evaluate(p);
        approve(PROP_RETURN_THRESHOLD, STRATEGY);
        ids.put("approved", PROP_RETURN_THRESHOLD);
    }

    /** REJECTED: a higher default appeasement credit, over this quarter's budget. */
    private void rejectedAppeasement(Map<String, String> ids) {
        Proposal p = draft(PROP_APPEASEMENT, new ArtifactRef("config", "cs.appeasement_default_usd"), 0,
            List.of(new PolicyChange.ParameterChange("cs.appeasement_default_usd", 10, 15)), "Raise the default goodwill credit from $10 to $15",
            PolicyChangeset.RiskTier.T2, byLearner("appeasement_level"), null);
        evaluate(p);
        String reason = "Over this quarter's appeasement budget; revisit after peak";
        proposals.reject(PROP_APPEASEMENT, reason, Instant.now());
        events.proposalRejected(PROP_APPEASEMENT, Actor.parse(STRATEGY), Map.of("target", p.changeset().target().toString(), "reason", reason));
        ids.put("rejected", PROP_APPEASEMENT);
    }

    /**
     * EX-07's T0 path: exception classification may classify only from 0.55 (orders whose exception was classified just
     * above 0.50 were often given the wrong playbook). Replay on the seeded history, then a real shadow experiment whose
     * paired live/shadow checks the experiment itself measures. With replay above zero and a passed shadow, the
     * automatic promotion sweep may promote it on its own (T0).
     */
    private void t0WithShadow(Map<String, String> ids) {
        int version = liveVersion("exception_classify");
        String hyp = hypothesis("hyp_demo_t0_exception", Map.of("decision", "exception_classify"), "on_time", Direction.INCREASE,
            Map.of("action", "move_threshold", "decision", "exception_classify", "band", "act", "from", 0.50, "to", 0.55),
            List.of("Exceptions classified between 0.50 and 0.55 got the wrong type about 70% of the time", "Those orders missed their promise far more often than ones sent to triage"),
            "Sending borderline exceptions to triage instead of an automatic playbook fixes more orders.");
        Proposal p = draft(PROP_T0_EXCEPTION, new ArtifactRef("decision", "exception_classify"), version,
            List.of(new PolicyChange.ThresholdChange("exception_classify", "act", 0.50, 0.55)), "Classify exceptions automatically only from 0.55; send the rest to triage",
            PolicyChangeset.RiskTier.T0, byLearner("band_threshold"), hyp);
        evaluate(p);
        var shadow = experiments.start(PROP_T0_EXCEPTION, ExperimentMode.SHADOW, 1.0, Map.of("minRecords", 30, "durationDays", 7), SEED);
        history.pairShadow(version, shadow.candidateVersion(), Instant.now(), 40);
        experiments.complete(shadow.experimentId(), Actor.parse(SEED));
        ids.put("t0Shadow", PROP_T0_EXCEPTION);
        ids.put("t0ShadowExperiment", shadow.experimentId());
    }

    /** DRAFT: if the fraud threshold tuner (run with the other learners) drafted nothing, its usual proposal on COD refusals. */
    private void draftFallback(Map<String, String> ids) {
        List<Proposal> drafts = proposals.byStatus("DRAFT", 50);
        if (!drafts.isEmpty()) {
            // Prefer the band tuner's decision draft (a stable target the UI and Playwright can shadow-test), else the newest.
            ids.put("draft", drafts.stream().filter(d -> "fulfilment_intent".equals(d.changeset().target().name())).findFirst().orElse(drafts.get(0)).proposalId());
            return;
        }
        int version = liveVersion("rto_risk");
        draft(PROP_RTO_THRESHOLD, new ArtifactRef("decision", "rto_risk"), version, List.of(new PolicyChange.ThresholdChange("rto_risk", "act", 0.90, 0.93)),
            "Hold more first-time COD orders for address confirmation", PolicyChangeset.RiskTier.T2, byLearner("fraud_threshold_tuner"), null);
        ids.put("draft", PROP_RTO_THRESHOLD);
    }

    /** XP-07: an objective drafted by the strategy lead and approved by the admin, with a peak-season freeze window ahead. */
    private void objective(Map<String, String> ids) {
        LocalDate today = LocalDate.now(ai.intentoms.learning.model.DayPart.zone(config)); // the tenant's own calendar (tenant.timezone)
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("freezeWindows", List.of(Map.of("from", today.plusDays(37).toString(), "to", today.plusDays(44).toString(), "reason", "Peak season: no automatic changes")));
        constraints.put("marginFloorPct", 0.18);
        Map<String, String> rationale = new LinkedHashMap<>();
        rationale.put("otd", "On-time delivery matters most going into peak");
        rationale.put("fraud", "Chargebacks cost more than a held order");
        rationale.put("split", "A second package is acceptable when it keeps the promise");
        ObjectiveProfile draft = objectives.draft(new ObjectiveWeights(1.0, 1.3, 1.0, 0.8, 1.2, 1.0, 0.6), constraints, STRATEGY,
            "Protect on-time delivery through peak, keep fraud losses low, and accept a little more split shipping to do it.", "manual", rationale);
        objectives.approve(draft.version(), ADMIN);
        ids.put("objectiveVersion", String.valueOf(draft.version()));
    }

    // ------------------------------------------------------------------ helpers

    private Proposal draft(String id, ArtifactRef target, int baseVersion, List<PolicyChange> changes, String motivation, PolicyChangeset.RiskTier tier, String by, String hypothesisId) {
        Optional<Proposal> existing = proposals.find(id);
        if (existing.isPresent()) return existing.get();
        Instant now = Instant.now();
        proposals.insert(new Proposal(id, new PolicyChangeset(target, baseVersion, changes, motivation, tier), hypothesisId, tier, ProposalStatus.DRAFT, null, null,
            by, now, now, null, null, null, null, null, null, null, null));
        return proposals.get(id);
    }

    private void evaluate(Proposal p) {
        if (p.status() == ProposalStatus.DRAFT) evaluator.evaluate(p.proposalId(), STRATEGY);
    }

    private void approve(String id, String by) {
        Proposal p = proposals.get(id);
        if (p.status() != ProposalStatus.EVALUATED && p.status() != ProposalStatus.DRAFT) return;
        proposals.approve(id, by, Instant.now());
        events.proposalApproved(id, Actor.parse(by), Map.of("target", p.changeset().target().toString(), "tier", p.tier().name(), "author", p.createdBy()));
    }

    private String hypothesis(String id, Map<String, Object> scope, String metric, Direction direction, Map<String, Object> lever, List<String> evidence, String rationale) {
        if (hypotheses.find(id).isEmpty()) {
            hypotheses.insert(new Hypothesis(id, scope, metric, direction, lever, 0.6, evidence, rationale, Hypothesis.Status.DRAFTED, Instant.now()));
        }
        return id;
    }

    private static boolean positive(Proposal p) {
        Map<String, Object> r = p.evaluationReport();
        if (r == null) return false;
        Object ci = r.get("confidenceInterval");
        return ci instanceof Map<?, ?> m && m.get("lower") instanceof Number lower && lower.doubleValue() > 0;
    }

    private int liveVersion(String decision) {
        return definitions.byStatus(decision, "live").stream().findFirst().map(r -> r.definition().version()).orElse(1);
    }

    /** What the tenant now holds, for the marker and {@code GET /learning/seed-status}. */
    private Map<String, Object> tally() {
        String t = TenantContext.require();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("episodes", jdbc.sql("select count(*) from episode where tenant_id = :t").param("t", t).query(Long.class).single());
        m.put("outcomes", jdbc.sql("select count(*) from outcome where tenant_id = :t").param("t", t).query(Long.class).single());
        m.put("facts", jdbc.sql("select count(*) from learned_fact where tenant_id = :t and valid_to is null").param("t", t).query(Long.class).single());
        m.put("factVersions", jdbc.sql("select count(*) from learned_fact where tenant_id = :t").param("t", t).query(Long.class).single());
        Map<String, Object> byStatus = new TreeMap<>();
        jdbc.sql("select status, count(*) as n from proposal where tenant_id = :t group by status").param("t", t).query().listOfRows()
            .forEach(r -> byStatus.put((String) r.get("status"), ((Number) r.get("n")).longValue()));
        m.put("proposalsByStatus", byStatus);
        m.put("hypotheses", jdbc.sql("select count(*) from hypothesis where tenant_id = :t").param("t", t).query(Long.class).single());
        m.put("experiments", jdbc.sql("select count(*) from experiment where tenant_id = :t").param("t", t).query(Long.class).single());
        m.put("segments", jdbc.sql("select count(*) from segment where tenant_id = :t").param("t", t).query(Long.class).single());
        return m;
    }

    /**
     * What the learners made of the reference data, by fact: fraud rates per signal level (FD-01), the tuner's
     * false-positive rate and draft (FD-03), confidence curves (DF-02), lane corrections (CX-01), promise buffers (CX-02),
     * aged stock (IU-03) and the warn-early level (CX-03).
     */
    private Map<String, Object> referenceFacts() {
        String t = TenantContext.require();
        Map<String, Object> m = new LinkedHashMap<>();
        for (String key : List.of("FRAUD_SIGNAL_RATE", "FALSE_POSITIVE_RATE", "CALIBRATION_CURVE", "LANE_CALIBRATION_OFFSET", "PROMISE_BUFFER_DAYS", "STOCK_AGE_DAYS",
                "WARN_EARLY_THRESHOLD")) {
            m.put(key, jdbc.sql("select count(*) from learned_fact where tenant_id = :t and fact_key = :k and valid_to is null").param("t", t).param("k", key).query(Long.class).single());
        }
        m.put("fraudTunerDrafts", jdbc.sql("select count(*) from proposal where tenant_id = :t and created_by = 'system:fraud_threshold_tuner'").param("t", t).query(Long.class).single());
        return m;
    }

    /**
     * The persistent holdout, started as if it had been running through the seeded history (about 92 days), so the
     * lift report compares the seeded holdout customers with everyone else. Demo tenant only, labelled demo data.
     */
    private String backdatedHoldout() {
        String id = experiments.ensureHoldout(0.05, SEED).experimentId();
        jdbc.sql("update experiment set start_at = now() - interval '92 days' where tenant_id = :t and experiment_id = :id and created_by = :by")
            .param("t", ai.intentoms.platform.tenant.TenantContext.require()).param("id", id).param("by", SEED).update();
        return id;
    }
}
