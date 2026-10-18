package ai.intentoms.app.glue.soo;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.learner.CiPreferenceLearner;
import ai.intentoms.learning.learner.FraudLabelCapture;
import ai.intentoms.learning.learner.LaneDeliveryLearner;
import ai.intentoms.learning.learner.NodeReliabilityLearner;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * About 90 days of believable, back-dated order history for the demo tenant's self-optimizing layer: the LIVE
 * decision records learners read (with OC-01 arms and propensities), one episode per decision point, typed outcomes
 * (fulfilment, delivery, contacts, chargebacks, refused COD parcels, returns), and the order events the event-driven
 * learners consume. The events go to the real learners, so node, lane, customer and fraud-label facts come from their
 * own maths and versioning rather than from numbers written here.
 *
 * <p>Built straight into the stores, never through the live decision runtime, so it opens no escalation tasks and
 * costs nothing. Every record's actor is {@code system:demo-seed} and its details carry {@code demoData: true}.
 * Deterministic: a fixed random seed and ids derived from the synthetic order number.
 *
 * <p>The shape is deliberate so the evaluator and the learners find real signal: orders whose node score sat in the
 * confirm band shipped later (waiting for a person); breach-risk scores that acted with low confidence missed more
 * promises than the ones a person re-promised; store nodes reject now and then; and address-check outcomes stopped
 * arriving for most recent orders (the silent-data-loss case OC-07 guards against).
 */
@Component
public class SooDemoHistory {
    static final String ACTOR = "system:demo-seed";
    static final int ORDERS = 360;
    static final int TAIL = 40;
    static final String ORDER_PREFIX = "demo-hist-";
    static final List<String> NODES = List.of("DC-NJ", "DC-NV", "DC-TX", "ST-CHI-01", "ST-LA-02", "ST-AUS-03", "3PL-ATL");
    static final double[] NODE_WEIGHT = {0.26, 0.18, 0.16, 0.1, 0.1, 0.08, 0.12};
    static final Map<String, Double> NODE_ON_TIME = Map.of("DC-NJ", 0.95, "DC-NV", 0.92, "DC-TX", 0.88, "ST-CHI-01", 0.84, "ST-LA-02", 0.76,
        "ST-AUS-03", 0.86, "3PL-ATL", 0.9);
    static final List<String> CARRIERS = List.of("UPS", "FedEx", "USPS");
    static final Map<String, Double> CARRIER_FACTOR = Map.of("UPS", 1.0, "FedEx", 0.98, "USPS", 0.93);
    static final List<String> ZIP3 = List.of("100", "021", "606", "941", "900", "787", "303", "980");
    static final Map<String, String> ZIP_STATE = Map.of("100", "NY", "021", "MA", "606", "IL", "941", "CA", "900", "CA", "787", "TX", "303", "GA", "980", "WA");
    static final List<String> SKU_CLASSES = List.of("apparel", "footwear", "home", "beauty");
    static final List<String> CHANNELS = List.of("web", "app", "marketplace");
    static final List<String> CONTACT_CHANNELS = List.of("email", "sms", "chat");
    static final List<String> BANDIT_ARMS = List.of("ship_complete", "split_up_to_k", "ship_as_available");
    static final List<String> SCORES = List.of("0", "1", "2", "3", "4");
    static final List<String> BOOLS = List.of("true", "false");
    static final double EXPLORATION = 0.10;

    private final DecisionRecordRepository records;
    private final DecisionDefinitionRepository definitions;
    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;
    private final List<EventSubscriber> learnersFed;

    private int episodeCount;
    private int outcomeCount;
    private int eventCount;
    private final Map<String, Integer> recordCount = new TreeMap<>();
    /** Whether the order being generated belongs to a holdout customer (decisions tagged, outcomes at baseline). */
    private boolean holdoutOrder;

    /** Demo holdout tag on decision records; the seeder points the running holdout experiment at this history. */
    static final String HOLDOUT_TAG = "demo-holdout";

    /**
     * XP-01 needs a holdout to compare against: a sticky ~8% of demo customers stand for customers routed to the
     * pre-learning baseline. Their outcomes are drawn at baseline rates (no learned node reliability, no tuned breach
     * threshold, no split avoidance), so the Overview's lift report has something to show. Labelled demo data.
     */
    static boolean holdoutCustomer(String customer) { return Math.floorMod(customer.hashCode() * 31 + 7, 100) < 8; }

    public SooDemoHistory(DecisionRecordRepository records, DecisionDefinitionRepository definitions, EpisodeRepository episodes, OutcomeRepository outcomes,
                          NodeReliabilityLearner nodeReliability, LaneDeliveryLearner laneTransit, CiPreferenceLearner customerPreferences, FraudLabelCapture fraudLabels) {
        this.records = records;
        this.definitions = definitions;
        this.episodes = episodes;
        this.outcomes = outcomes;
        this.learnersFed = List.of(nodeReliability, laneTransit, customerPreferences, fraudLabels);
    }

    public record Result(Map<String, Integer> recordsByDecision, int episodes, int outcomes, int learnerEvents) {}

    /** One synthetic order and everything that happened to it. */
    record Order(String id, Instant at, String customer, String channel, String shipMethod, String orderType, String node, String secondNode, String skuClass,
                 String carrier, String zip3, int packages, int units, double value, double cost, boolean delivered, boolean onTime, int lateByHours,
                 Instant shippedAt, Instant deliveredAt, LocalDate eta, boolean wismo, boolean chargeback, boolean cod, boolean refused, boolean returned,
                 boolean storeRejected) {}

    /** Generates the history for the current tenant. Call once per tenant (the seeder's marker guarantees that). */
    public Result generate(Instant now) {
        recordCount.clear();
        episodeCount = 0;
        outcomeCount = 0;
        eventCount = 0;
        Random rnd = new Random(20261019L);
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (String d : List.of("sourcing_node_score", "sla_breach_risk", "payment_risk", "rto_risk", "return_risk", "address_risk")) versions.put(d, liveVersion(d));

        for (int i = 0; i < TAIL + ORDERS; i++) {
            // A short tail older than every maturity window (so some episodes are MATURE), then 90 days of recent history.
            double ageDays = i < TAIL ? 135.0 - i : 90.0 - 88.0 * (i - TAIL) / ORDERS + rnd.nextDouble() * 0.25;
            Order o = order(i, now.minus(Duration.ofMinutes(Math.round(ageDays * 1440))), ageDays, rnd);
            holdoutOrder = holdoutCustomer(o.customer());
            if (holdoutOrder && o.packages() == 1 && rnd.nextDouble() < 0.25) o = split(o);

            double snsConf = 0.55 + rnd.nextDouble() * 0.44;
            double slaConf = 0.5 + rnd.nextDouble() * 0.49;
            o = shapeOutcome(o, snsConf, slaConf, holdoutOrder, rnd);
            if (o.delivered() && o.deliveredAt().isAfter(now.minus(Duration.ofHours(1)))) o = inTransit(o);
            int slaScore = o.onTime() ? rnd.nextInt(2) : 2 + rnd.nextInt(3);

            List<String> orderEpisodes = new ArrayList<>();
            orderEpisodes.add(sourcingNodeScore(o, snsConf, versions.get("sourcing_node_score")));
            orderEpisodes.add(slaBreachRisk(o, slaConf, slaScore, versions.get("sla_breach_risk"), rnd));
            orderEpisodes.add(paymentRisk(o, versions.get("payment_risk"), rnd));
            if (rnd.nextDouble() < 0.7) orderEpisodes.add(bandit(o, rnd));
            if (o.cod()) orderEpisodes.add(rtoRisk(o, versions.get("rto_risk"), rnd));
            boolean addressChecked = ageDays >= 3 && ageDays <= 14 ? rnd.nextDouble() < 0.9 : rnd.nextDouble() < 0.12;
            String addressEpisode = addressChecked ? addressRisk(o, versions.get("address_risk"), rnd) : null;

            for (String ep : orderEpisodes) orderOutcomes(ep, o);
            // Address-check outcomes stopped arriving for most recent orders (OC-07's silent data loss).
            if (addressEpisode != null && (ageDays > 14 || rnd.nextDouble() < 0.5)) orderOutcomes(addressEpisode, o);
            if (o.delivered() && o.returned()) returnRisk(o, versions.get("return_risk"), rnd, now);
            feedLearners(o, now);
        }
        holdoutOrder = false;
        returnGroups(versions.get("return_risk"), now);
        return new Result(Map.copyOf(recordCount), episodeCount, outcomeCount, eventCount);
    }

    private Order order(int i, Instant at, double ageDays, Random rnd) {
        String id = ORDER_PREFIX + String.format(Locale.ROOT, "%04d", i + 1);
        String node = pickNode(rnd);
        String second = NODES.get((NODES.indexOf(node) + 1 + rnd.nextInt(NODES.size() - 1)) % NODES.size());
        String carrier = CARRIERS.get(rnd.nextInt(CARRIERS.size()));
        String shipMethod = rnd.nextDouble() < 0.75 ? "ground" : "express";
        String orderType = rnd.nextDouble() < 0.1 ? "pickup" : "standard";
        int packages = rnd.nextDouble() < 0.2 ? 2 : 1;
        int units = packages + rnd.nextInt(3);
        double value = round2(35 + rnd.nextDouble() * 180);
        double cost = round2(6 + packages * 5.5 + rnd.nextDouble() * 4);
        boolean storeRejected = node.startsWith("ST-") && rnd.nextDouble() < 0.12;
        boolean delivered = ageDays > 3 && rnd.nextDouble() < 0.96;
        int promisedDays = "express".equals(shipMethod) ? 2 : 4;
        Instant shippedAt = at.plus(Duration.ofHours(5 + rnd.nextInt(8)));
        LocalDate eta = at.atZone(ZoneOffset.UTC).toLocalDate().plusDays(promisedDays);
        boolean cod = rnd.nextDouble() < 0.15;
        boolean refused = cod && rnd.nextDouble() < 0.18;
        boolean chargeback = !cod && rnd.nextDouble() < 0.025;
        boolean returned = !refused && rnd.nextDouble() < 0.3;
        String customer = String.format(Locale.ROOT, "cust-demo-%03d", 1 + rnd.nextInt(160));
        return new Order(id, at, customer, CHANNELS.get(rnd.nextInt(CHANNELS.size())), shipMethod, orderType, node, second,
            SKU_CLASSES.get(rnd.nextInt(SKU_CLASSES.size())), carrier, ZIP3.get(rnd.nextInt(ZIP3.size())), packages, units, value, cost,
            delivered, true, 0, shippedAt, null, eta, false, chargeback, cod, refused, returned, storeRejected);
    }

    /**
     * Whether the order arrived on time, from the node's and carrier's reliability, a store reject, and the decisions:
     * a node score held in the confirm band waited for a person (later), a breach score that acted on its own with low
     * confidence missed more, and one a person re-promised did a little better.
     */
    private static Order shapeOutcome(Order o, double snsConf, double slaConf, boolean holdout, Random rnd) {
        double p = NODE_ON_TIME.get(o.node()) * CARRIER_FACTOR.get(o.carrier()) - (o.storeRejected() ? 0.2 : 0);
        if (holdout) p -= 0.12; // baseline: sourcing without learned node reliability or lane transit times
        if (snsConf >= 0.5 && snsConf < 0.80) p -= 0.25;
        if (slaConf >= 0.80 && slaConf < 0.86) p -= 0.45;
        if (slaConf < 0.80) p += 0.04;
        boolean onTime = !o.refused() && rnd.nextDouble() < clamp(p, 0.05, 0.99);
        int lateBy = onTime || o.refused() ? 0 : 12 + rnd.nextInt(60);
        Instant deliveredAt = onTime ? o.eta().minusDays(rnd.nextInt(2)).atTime(15, 0).toInstant(ZoneOffset.UTC)
            : o.eta().atTime(15, 0).toInstant(ZoneOffset.UTC).plus(Duration.ofHours(lateBy));
        if (!deliveredAt.isAfter(o.shippedAt())) deliveredAt = o.shippedAt().plus(Duration.ofHours(20));
        boolean wismo = !onTime ? rnd.nextDouble() < 0.55 : rnd.nextDouble() < 0.03;
        return new Order(o.id(), o.at(), o.customer(), o.channel(), o.shipMethod(), o.orderType(), o.node(), o.secondNode(), o.skuClass(), o.carrier(), o.zip3(),
            o.packages(), o.units(), o.value(), o.cost(), o.delivered(), onTime, lateBy, o.shippedAt(), deliveredAt, o.eta(), wismo, o.chargeback(), o.cod(),
            o.refused(), o.returned(), o.storeRejected());
    }

    /** Baseline sourcing splits more often: a second package from the second node, and the extra shipping cost. */
    private static Order split(Order o) {
        return new Order(o.id(), o.at(), o.customer(), o.channel(), o.shipMethod(), o.orderType(), o.node(), o.secondNode(), o.skuClass(), o.carrier(), o.zip3(),
            2, Math.max(2, o.units()), o.value(), round2(o.cost() + 5.5), o.delivered(), o.onTime(), o.lateByHours(), o.shippedAt(), o.deliveredAt(), o.eta(),
            o.wismo(), o.chargeback(), o.cod(), o.refused(), o.returned(), o.storeRejected());
    }

    /** Not delivered yet: its delivery date is still ahead. */
    private static Order inTransit(Order o) {
        return new Order(o.id(), o.at(), o.customer(), o.channel(), o.shipMethod(), o.orderType(), o.node(), o.secondNode(), o.skuClass(), o.carrier(), o.zip3(),
            o.packages(), o.units(), o.value(), o.cost(), false, o.onTime(), o.lateByHours(), o.shippedAt(), o.deliveredAt(), o.eta(), o.wismo(), o.chargeback(),
            o.cod(), o.refused(), o.returned(), o.storeRejected());
    }

    // ------------------------------------------------------------------ decisions

    private String sourcingNodeScore(Order o, double conf, int version) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("order", Map.of("id", o.id(), "destination_state", ZIP_STATE.get(o.zip3()), "ship_method", o.shipMethod(), "line_count", o.units(),
            "sku_class", o.skuClass(), "day_part", dayPart(o.at())));
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (String n : List.of(o.node(), o.secondNode())) {
            candidates.add(Map.of("id", n, "type", n.startsWith("ST-") ? "store" : n.startsWith("3PL") ? "3pl" : "dc", "backlog_units", 40 + Math.abs(n.hashCode() % 300),
                "reject_rate", n.startsWith("ST-") ? 0.12 : 0.03, "staffing", "normal", "minutes_to_cutoff", 180, "distance_km", 300 + Math.abs(n.hashCode() % 1800)));
        }
        input.put("candidates", candidates);
        String id = recordId("sourcing_node_score", o.id());
        insert(id, "sourcing_node_score", version, "order", o.id(), o.id(), Layer.L1_SYSTEM_ONE, band(conf, 0.80, 0.50), o.node(), conf, input, List.of(), List.of(),
            details("T1", Map.of("ranking", List.of(o.node(), o.secondNode()))), Map.of(), o.at(), List.of(o.node(), o.secondNode()), o.node(), 1.0);
        return episode(id, "sourcing_node_score", version, o, "order", o.id(), o.at());
    }

    private String slaBreachRisk(Order o, double conf, int score, int version, Random rnd) {
        Map<String, Object> input = Map.of("order", Map.of("id", o.id(), "state", "released", "hours_in_state", 2 + rnd.nextInt(30), "promise_hours_remaining", 12 + rnd.nextInt(80),
            "ship_method", o.shipMethod()), "node", Map.of("backlog_units", 40 + rnd.nextInt(400), "reject_rate", o.node().startsWith("ST-") ? 0.12 : 0.03, "minutes_to_cutoff", 30 + rnd.nextInt(400)),
            "carrier", Map.of("on_time_rate_7d", round2(CARRIER_FACTOR.get(o.carrier()) * 0.93)));
        String id = recordId("sla_breach_risk", o.id());
        Instant at = o.at().plus(Duration.ofHours(1));
        insert(id, "sla_breach_risk", version, "order", o.id(), o.id(), Layer.L1_SYSTEM_ONE, band(conf, 0.80, 0.50), score, conf, input, List.of(), List.of(), details("T1", Map.of()),
            Map.of(), at, SCORES, String.valueOf(score), 1.0);
        return episode(id, "sla_breach_risk", version, o, "order", o.id(), at);
    }

    private String paymentRisk(Order o, int version, Random rnd) {
        boolean clean = !o.chargeback() && rnd.nextDouble() < 0.85;
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("velocity_1h", clean ? 1 : 1 + rnd.nextInt(3));
        payment.put("velocity_24h", clean ? 1 : 2 + rnd.nextInt(4));
        payment.put("address_linkage_count", clean ? 0 : rnd.nextInt(5));
        payment.put("device_linkage_count", clean ? 0 : rnd.nextInt(3));
        payment.put("token_customer_count", 1);
        payment.put("billing_shipping_mismatch", !clean && rnd.nextDouble() < 0.4);
        Map<String, Object> input = Map.of("order", Map.of("id", o.id(), "total", o.value(), "channel", o.channel()), "payment", payment, "agent", Map.of("mandate_present", false));
        String id = recordId("payment_risk", o.id());
        if (clean) {
            insert(id, "payment_risk", version, "order", o.id(), o.id(), Layer.L0_RULE, Band.ACT, false, 1.0, input, List.of("no_risk_signals"), List.of("no_risk_signals"),
                details("T2", Map.of()), Map.of(), o.at(), BOOLS, "false", 1.0);
        } else {
            double conf = 0.55 + rnd.nextDouble() * 0.43;
            boolean high = o.chargeback() ? rnd.nextDouble() < 0.7 : rnd.nextDouble() < 0.25;
            insert(id, "payment_risk", version, "order", o.id(), o.id(), Layer.L1_SYSTEM_ONE, band(conf, 0.9, 0.5), high, conf, input, List.of(),
                high ? List.of("high_risk_payment") : List.of(), details("T2", Map.of()), Map.of(), o.at(), BOOLS, String.valueOf(high), 1.0);
        }
        return episode(id, "payment_risk", version, o, "order", o.id(), o.at());
    }

    /** FS-01 record: live use is not promoted, so the default policy was applied (propensity 1.0); the arm epsilon-greedy drew is the shadow, with its real probability. */
    private String bandit(Order o, Random rnd) {
        String applied = o.packages() > 1 ? "split_up_to_k" : "ship_complete";
        String top = BANDIT_ARMS.get(0);
        String shadow = rnd.nextDouble() >= EXPLORATION ? top : BANDIT_ARMS.get(rnd.nextInt(BANDIT_ARMS.size()));
        double shadowP = (shadow.equals(top) ? 1 - EXPLORATION : 0) + EXPLORATION / BANDIT_ARMS.size();
        Map<String, Object> ctx = Map.of("channel", o.channel(), "intent", o.packages() > 1 ? "split_ok" : "ship_complete",
            "basket", o.units() <= 1 ? "1" : o.units() <= 3 ? "2-3" : "4+", "region", ZIP_STATE.get(o.zip3()));
        Map<String, Object> d = details(null, Map.of());
        d.put("acted", false);
        d.put("autonomyLevel", "L1_RECOMMEND");
        d.put("gate", "ok");
        d.put("appliedPolicy", applied);
        d.put("exploration", EXPLORATION);
        d.put("weights", Map.of("ship_complete", 0.3333, "split_up_to_k", 0.3333, "ship_as_available", 0.3333));
        d.put("shadow", Map.of("arm", shadow, "propensity", round4(shadowP)));
        String id = recordId("sourcing_policy_bandit", o.id());
        Instant at = o.at().plus(Duration.ofMinutes(2));
        insert(id, "sourcing_policy_bandit", 1, "order", o.id(), o.id(), Layer.L1_SYSTEM_ONE, Band.ACT, applied, 1.0, ctx, List.of(), List.of(), d, Map.of(),
            at, BANDIT_ARMS, applied, 1.0);
        return episode(id, "sourcing_policy_bandit", 1, o, "order", o.id(), at);
    }

    private String rtoRisk(Order o, int version, Random rnd) {
        double conf = 0.55 + rnd.nextDouble() * 0.43;
        boolean risky = o.refused() ? rnd.nextDouble() < 0.6 : rnd.nextDouble() < 0.15;
        Map<String, Object> input = Map.of("order", Map.of("id", o.id(), "total", o.value(), "channel", o.channel(), "tender", "cod"),
            "address", Map.of("verified", rnd.nextDouble() < 0.7, "prior_refusals", o.refused() ? 1 : 0), "customer", Map.of("prior_orders", rnd.nextInt(6)));
        String id = recordId("rto_risk", o.id());
        insert(id, "rto_risk", version, "order", o.id(), o.id(), Layer.L1_SYSTEM_ONE, band(conf, 0.9, 0.5), risky, conf, input, List.of(), List.of(), details("T2", Map.of()),
            Map.of(), o.at(), BOOLS, String.valueOf(risky), 1.0);
        return episode(id, "rto_risk", version, o, "order", o.id(), o.at());
    }

    private String addressRisk(Order o, int version, Random rnd) {
        double conf = 0.6 + rnd.nextDouble() * 0.39;
        Map<String, Object> input = Map.of("order", Map.of("id", o.id(), "channel", o.channel()), "address", Map.of("token", "addr-" + Math.abs(o.customer().hashCode() % 997),
            "verified", rnd.nextDouble() < 0.85, "zip3", o.zip3()));
        String id = recordId("address_risk", o.id());
        insert(id, "address_risk", version, "order", o.id(), o.id(), Layer.L1_SYSTEM_ONE, band(conf, 0.9, 0.5), false, conf, input, List.of(), List.of(), details("T2", Map.of()),
            Map.of(), o.at(), BOOLS, "false", 1.0);
        return episode(id, "address_risk", version, o, "order", o.id(), o.at());
    }

    /** A return against a delivered order, decided per RMA, with its disposition as the outcome. */
    private void returnRisk(Order o, int version, Random rnd, Instant now) {
        String rma = "rma-" + o.id();
        Instant at = o.deliveredAt().plus(Duration.ofDays(2 + rnd.nextInt(10)));
        if (at.isAfter(now.minus(Duration.ofHours(6)))) return;
        int score = rnd.nextDouble() < 0.8 ? rnd.nextInt(2) : 2 + rnd.nextInt(3);
        double conf = 0.6 + rnd.nextDouble() * 0.39;
        Map<String, Object> input = returnInput(rnd.nextDouble() < 0.85, rnd.nextInt(4), score >= 3 ? 1 : 0, round2(o.value() * 0.6));
        String id = recordId("return_risk", rma);
        insert(id, "return_risk", version, "return", rma, o.id(), Layer.L1_SYSTEM_ONE, band(conf, 0.9, 0.5), score, conf, input, List.of(), List.of(), details("T2", Map.of()),
            Map.of(), at, SCORES, String.valueOf(score), 1.0);
        String ep = episode(id, "return_risk", version, o, "return", rma, at);
        // Returns refunded straight away on a barely-confident score were the ones that turned out to be abuse.
        boolean fraud = conf >= 0.9 && conf < 0.93 ? rnd.nextDouble() < 0.6 : score >= 3 && rnd.nextDouble() < 0.4;
        outcome(ep, "disposition", new OutcomeData.ReturnOutcome(fraud ? "not_as_described" : "size_fit", fraud ? "restock_denied" : "restocked", fraud, round2(o.value() * 0.6)),
            at.plus(Duration.ofDays(4)), "evt-demo-" + rma + "-disposition");
    }

    /**
     * Situations people keep resolving the same way, so the real golden-set and override-to-rule learners find them:
     * three small groups where a person confirmed the recommendation (golden-case candidates), and one larger group of
     * identical returns where people kept moving a "moderate risk" score down to "low" (an L0 rule candidate).
     */
    private void returnGroups(int version, Instant now) {
        Object[][] golden = {{true, 0, 0, 1}, {false, 2, 0, 2}, {true, 3, 1, 3}};
        for (int g = 0; g < golden.length; g++) {
            Map<String, Object> input = returnInput((boolean) golden[g][0], (int) golden[g][1], (int) golden[g][2], 80.0 + g);
            int value = (int) golden[g][3];
            for (int k = 0; k < 4; k++) {
                Instant at = now.minus(Duration.ofDays(6 + g * 9L + k * 2L));
                String rma = "rma-demo-golden-" + g + "-" + k;
                insert(recordId("return_risk", rma), "return_risk", version, "return", rma, null, Layer.L1_SYSTEM_ONE, Band.CONFIRM, value, 0.72, input, List.of(), List.of(),
                    details("T2", Map.of()), Map.of("human_resolution", Map.of("action", String.valueOf(value), "reasonCode", "confirmed_recommendation", "resolvedBy", "user:risk")),
                    at, SCORES, String.valueOf(value), 1.0);
            }
        }
        // 62-84 days ago: inside the override learner's 90-day window, outside the golden-set learner's 60 days (both
        // key a situation by decision and input hash, so the same group would otherwise be claimed by whichever runs first).
        Map<String, Object> repeat = returnInput(true, 2, 0, 45.0);
        for (int k = 0; k < 21; k++) {
            Instant at = now.minus(Duration.ofDays(62 + k));
            String rma = "rma-demo-override-" + k;
            insert(recordId("return_risk", rma), "return_risk", version, "return", rma, null, Layer.L1_SYSTEM_ONE, Band.CONFIRM, 2, 0.66, repeat, List.of(), List.of(),
                details("T2", Map.of()), Map.of("human_resolution", Map.of("action", "1", "reasonCode", "receipt_present_low_value", "resolvedBy", k % 2 == 0 ? "user:risk" : "user:cs")),
                at, SCORES, "2", 1.0);
        }
    }

    private static Map<String, Object> returnInput(boolean receipt, int returns12m, int notReceived, double valueUsd) {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("reason", "size_fit");
        ret.put("days_since_delivery", 9);
        ret.put("receipt", receipt);
        ret.put("item_condition", "as_new");
        ret.put("value_usd", valueUsd);
        return Map.of("return", ret, "order", Map.of("channel", "web"),
            "customer", Map.of("history", Map.of("returns_12m", returns12m, "not_received_claims", notReceived, "flags", List.of())), "policy", Map.of("window_days", 30));
    }

    // ------------------------------------------------------------------ outcomes

    /** The fulfilment outcome lands when the order ships; delivery, contacts and chargebacks only once it was delivered. */
    private void orderOutcomes(String episodeId, Order o) {
        String base = "evt-demo-" + o.id();
        List<String> nodes = o.packages() > 1 ? List.of(o.node(), o.secondNode()) : List.of(o.node());
        outcome(episodeId, "shipped", new OutcomeData.FulfillmentOutcome(o.packages(), false, nodes, o.storeRejected(), o.units(), o.units(), o.cost(), o.value()),
            o.shippedAt(), base + "-shipped");
        if (!o.delivered()) return;
        outcome(episodeId, "delivered", new OutcomeData.DeliveryOutcome(o.onTime(), o.lateByHours(), false, o.refused()), o.deliveredAt(), base + "-delivered");
        if (o.wismo()) outcome(episodeId, "wismo", new OutcomeData.ContactOutcome("cs", "wismo", !o.onTime() && o.lateByHours() > 48),
            o.deliveredAt().minus(Duration.ofHours(6)), base + "-wismo");
        if (o.chargeback()) outcome(episodeId, "chargeback", new OutcomeData.PaymentOutcome(true, false, o.value()), o.deliveredAt().plus(Duration.ofDays(30)), base + "-chargeback");
    }

    private void outcome(String episodeId, String kind, OutcomeData data, Instant observedAt, String sourceEventId) {
        Instant now = Instant.now();
        if (observedAt.isAfter(now)) return; // has not happened yet
        if (outcomes.insert(OutcomeRecord.of("out-" + episodeId.substring(4) + "-" + kind, episodeId, data, observedAt, sourceEventId, now))) outcomeCount++;
    }

    // ------------------------------------------------------------------ learner events

    private void feedLearners(Order o, Instant now) {
        String base = "evt-demo-" + o.id();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int k = 0; k < o.units(); k++) lines.add(Map.of("sku", "SKU-" + o.skuClass() + "-" + k, "qty", 1));
        Map<String, Object> captured = new LinkedHashMap<>();
        captured.put("orderId", o.id());
        captured.put("customerRef", o.customer());
        captured.put("channel", o.channel());
        captured.put("shipMethod", o.shipMethod());
        captured.put("orderType", o.orderType());
        captured.put("lines", lines);
        captured.put("shipTo", Map.of("state", ZIP_STATE.get(o.zip3()), "zip", o.zip3() + "01"));
        feed(base + "-captured", o.id(), "order.captured", captured, o.at());

        List<Map<String, Object>> packages = new ArrayList<>();
        packages.add(pkg(o.node(), o.skuClass(), o.eta()));
        if (o.packages() > 1) packages.add(pkg(o.secondNode(), o.skuClass(), o.eta()));
        feed(base + "-sourced", o.id(), "order.sourced", Map.of("orderId", o.id(), "packages", packages, "destinationZip3", o.zip3(), "shipMethod", o.shipMethod()),
            o.at().plus(Duration.ofMinutes(3)));
        if (o.storeRejected()) feed(base + "-resourced", o.id(), "order.resourced", Map.of("orderId", o.id(), "rejectedNodes", List.of(o.node())), o.at().plus(Duration.ofHours(2)));
        if (o.shippedAt().isAfter(now)) return;
        feed(base + "-shipped", o.id(), "order.shipped", Map.of("orderId", o.id(), "carrier", o.carrier(), "nodeId", o.node(), "packageCount", o.packages()), o.shippedAt());
        if (!o.delivered()) return;
        if (o.wismo()) feed(base + "-wismo", o.id(), "cs.wismo", Map.of("orderId", o.id(), "customerRef", o.customer(),
            "channel", CONTACT_CHANNELS.get(Math.abs(o.customer().hashCode()) % 3)), o.deliveredAt().minus(Duration.ofHours(6)));
        if (o.refused()) feed(base + "-rts", o.id(), "order.returned_to_sender", Map.of("orderId", o.id(), "cod", true), o.deliveredAt());
        else feed(base + "-delivered", o.id(), "order.delivered", Map.of("orderId", o.id(), "onTime", o.onTime(), "lateByHours", o.lateByHours()), o.deliveredAt());
        Instant cbAt = o.deliveredAt().plus(Duration.ofDays(30));
        if (o.chargeback() && cbAt.isBefore(now)) feed(base + "-chargeback", o.id(), "payment.chargeback",
            Map.of("orderId", o.id(), "fraud", true, "amount", o.value(), "reasonCode", "fraudulent"), cbAt);
    }

    private static Map<String, Object> pkg(String node, String skuClass, LocalDate eta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", node);
        m.put("skuClass", skuClass);
        m.put("eta", eta.toString());
        m.put("onTime", 0.9);
        m.put("backlogUnits", 40 + Math.abs(node.hashCode() % 300));
        return m;
    }

    private void feed(String id, String orderId, String type, Map<String, Object> payload, Instant at) {
        Event e = new Event(id, TenantContext.require(), "order", orderId, 0, type, payload, Actor.system("demo-seed"), null, orderId, Map.of(), at);
        for (EventSubscriber s : learnersFed) {
            if (s.eventTypePrefixes().stream().anyMatch(type::startsWith)) { s.on(e); eventCount++; }
        }
    }

    // ------------------------------------------------------------------ helpers

    private void insert(String id, String decision, int version, String subjectType, String subjectId, String correlationId, Layer layer, Band band, Object value,
                        double confidence, Map<String, Object> input, List<String> rulesFired, List<String> reasonCodes, Map<String, Object> details,
                        Map<String, Object> labels, Instant at, List<String> arms, String chosenArm, double propensity) {
        String action = band == Band.ACT ? "act" : band == Band.CONFIRM ? "confirm" : "escalate";
        records.insert(new DecisionRecord(id, decision, version, Mode.LIVE, subjectType, subjectId, correlationId, layer, band, action, value, Map.of(), confidence,
            layer == Layer.L0_RULE ? null : "heuristic", layer == Layer.L0_RULE ? null : "demo-history", Json.sha256(input), input, input, rulesFired, reasonCodes,
            0, 0.0, 0, false, null, ACTOR, details, null, null, null, null, labels, at, input, arms, chosenArm, propensity, version));
        recordCount.merge(decision, 1, Integer::sum);
    }

    private String episode(String recordId, String decision, int version, Order o, String subjectType, String subjectId, Instant at) {
        String epi = "epi-" + recordId.substring(3);
        if (episodes.upsertDecisionPoint(Episode.open(epi, recordId, decision, o.id(), null, null, version, subjectType, subjectId, at))) episodeCount++;
        return epi;
    }

    private Map<String, Object> details(String tier, Map<String, Object> topK) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (tier != null) d.put("tier", tier);
        d.put("mode", "LIVE");
        d.put("demoData", true);
        if (holdoutOrder) d.put("experiment", Map.of("experimentId", HOLDOUT_TAG, "arm", "holdout"));
        if (!topK.isEmpty()) d.put("topK", topK);
        return d;
    }

    static String recordId(String decision, String subject) { return "dr-demo-" + decision + "-" + subject; }

    private int liveVersion(String decision) {
        return definitions.byStatus(decision, "live").stream().findFirst().map(r -> r.definition().version()).orElse(1);
    }

    private static Band band(double conf, double act, double confirm) { return conf >= act ? Band.ACT : conf >= confirm ? Band.CONFIRM : Band.ESCALATE; }

    private static String pickNode(Random rnd) {
        double r = rnd.nextDouble(), acc = 0;
        for (int i = 0; i < NODES.size(); i++) { acc += NODE_WEIGHT[i]; if (r < acc) return NODES.get(i); }
        return NODES.get(0);
    }

    private static String dayPart(Instant at) {
        int h = at.atZone(ZoneOffset.UTC).getHour();
        return h < 12 ? "morning" : h < 17 ? "afternoon" : "evening";
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10_000) / 10_000.0; }
}
