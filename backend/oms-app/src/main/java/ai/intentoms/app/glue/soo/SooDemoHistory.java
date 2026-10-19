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
import ai.intentoms.learning.learner.PromiseConversionRepository;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    /** Customers in the demo history; large enough that the 5% holdout holds a usable number of them. */
    static final int CUSTOMERS = 300;
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

    /** Holdout orders the history tops up to (XP-01 needs at least 30 per arm for the perfect-order headline to be judged). */
    static final int MIN_HOLDOUT_ORDERS = 45;
    /** Matured payment-risk decisions (older than the chargeback window) the fraud learners train on (FD-01, FD-03). */
    static final int FRAUD_COHORT = 180;
    static final String FRAUD_PREFIX = "demo-fraud-";
    /**
     * How optimistic the static promise table is about each lane: the stated on-time probability on a package is the
     * node and carrier's nominal rate less this, so the lane calibration (CX-01) finds a correction of a few points.
     */
    static final double STATED_HAIRCUT = 0.2;
    /** Storefront promise buffers shown in the history (CX-02) and the share of shoppers who bought at each. */
    static final double[] BUFFER_CONVERSION = {0.36, 0.31, 0.25};

    private final DecisionRecordRepository records;
    private final DecisionDefinitionRepository definitions;
    private final EpisodeRepository episodes;
    private final OutcomeRepository outcomes;
    private final List<EventSubscriber> learnersFed;
    private final FraudLabelCapture fraudLabels;
    private final PromiseConversionRepository promiseConversions;
    private final TenantConfigStore config;
    /** The tenant's clock (tenant.timezone), for day parts. */
    private ZoneId zone = ZoneOffset.UTC;
    /** Every order generated in the last run, for the storefront impressions. */
    private final List<Order> generated = new ArrayList<>();
    private int fraudCohortRecords;
    private int labelledResolutions;
    private int promiseImpressions;
    private int holdoutTopUp;
    private int recentHoldoutOrders;

    private int episodeCount;
    private int outcomeCount;
    private int eventCount;
    private final Map<String, Integer> recordCount = new TreeMap<>();
    /** Whether the order being generated belongs to a holdout customer (decisions tagged, outcomes at baseline). */
    private boolean holdoutOrder;
    /** The running holdout experiment the history is tagged with. */
    private String holdoutExperimentId;
    /** Holdout and exception-check counts of the last run, for the seed marker. */
    private int holdoutOrders;
    private int exceptionRecords;

    /** T0 demo: exception classification acts from 0.50; the seeded proposal raises it to 0.55. */
    static final double EXCEPTION_ACT = 0.50;
    static final double EXCEPTION_PROPOSED_ACT = 0.55;
    static final List<String> EXCEPTION_TYPES = List.of("address", "inventory_short", "carrier_delay", "payment", "store_reject", "system", "other", "unclear");

    public SooDemoHistory(DecisionRecordRepository records, DecisionDefinitionRepository definitions, EpisodeRepository episodes, OutcomeRepository outcomes,
                          NodeReliabilityLearner nodeReliability, LaneDeliveryLearner laneTransit, CiPreferenceLearner customerPreferences, FraudLabelCapture fraudLabels,
                          PromiseConversionRepository promiseConversions, TenantConfigStore config) {
        this.records = records;
        this.definitions = definitions;
        this.episodes = episodes;
        this.outcomes = outcomes;
        this.learnersFed = List.of(nodeReliability, laneTransit, customerPreferences, fraudLabels);
        this.fraudLabels = fraudLabels;
        this.promiseConversions = promiseConversions;
        this.config = config;
    }

    /**
     * What one run produced. {@code fraudCohortRecords}: matured, labelled payment-risk decisions; {@code
     * labelledResolutions}: decisions a person resolved, labelled {@code human_resolution}; {@code promiseImpressions}:
     * storefront promise impressions; {@code holdoutTopUp}: extra holdout orders added to reach {@link #MIN_HOLDOUT_ORDERS}.
     */
    public record Result(Map<String, Integer> recordsByDecision, int episodes, int outcomes, int learnerEvents, int holdoutOrders, int exceptionRecords,
                         int fraudCohortRecords, int labelledResolutions, int promiseImpressions, int holdoutTopUp) {}

    /** One synthetic order and everything that happened to it. */
    record Order(String id, Instant at, String customer, String channel, String shipMethod, String orderType, String node, String secondNode, String skuClass,
                 String carrier, String zip3, int packages, int units, double value, double cost, boolean delivered, boolean onTime, int lateByHours,
                 Instant shippedAt, Instant deliveredAt, LocalDate eta, boolean wismo, boolean chargeback, boolean cod, boolean refused, boolean returned,
                 boolean storeRejected) {}

    /**
     * Generates the history for the current tenant. Call once per tenant (the seeder's marker guarantees that).
     *
     * <p>XP-01 needs a holdout to compare against. Holdout membership comes from the live router itself
     * ({@code inHoldout}, sticky per customer for the running holdout experiment), so seeded and live traffic agree on
     * who is held out. A holdout customer's decisions are tagged with that experiment and its outcomes are drawn at
     * baseline rates (no learned node reliability, no tuned breach threshold, no split avoidance). Labelled demo data.
     */
    public Result generate(Instant now, java.util.function.Predicate<String> inHoldout, String holdoutExperimentId) {
        recordCount.clear();
        generated.clear();
        episodeCount = 0;
        outcomeCount = 0;
        eventCount = 0;
        holdoutOrders = 0;
        exceptionRecords = 0;
        fraudCohortRecords = 0;
        labelledResolutions = 0;
        promiseImpressions = 0;
        holdoutTopUp = 0;
        recentHoldoutOrders = 0;
        zone = ai.intentoms.learning.model.DayPart.zone(config);
        this.holdoutExperimentId = holdoutExperimentId;
        Random rnd = new Random(20261019L);
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (String d : List.of("sourcing_node_score", "sla_breach_risk", "payment_risk", "rto_risk", "return_risk", "address_risk", "exception_classify")) versions.put(d, liveVersion(d));

        for (int i = 0; i < TAIL + ORDERS; i++) {
            // A short tail older than every maturity window (so some episodes are MATURE), then 90 days of recent history.
            double ageDays = i < TAIL ? 135.0 - i : 90.0 - 88.0 * (i - TAIL) / ORDERS + rnd.nextDouble() * 0.25;
            Order o = order(i, now.minus(Duration.ofMinutes(Math.round(ageDays * 1440))), ageDays, rnd);
            emit(o, ageDays, holdoutExperimentId != null && inHoldout.test(o.customer()), rnd, versions, now);
        }
        topUpHoldout(inHoldout, versions, now);
        holdoutOrder = false;
        returnGroups(versions.get("return_risk"), now);
        breachEscalations(versions.get("sla_breach_risk"), now);
        fraudCohort(versions.get("payment_risk"), now);
        storefrontImpressions();
        return new Result(Map.copyOf(recordCount), episodeCount, outcomeCount, eventCount, holdoutOrders, exceptionRecords,
            fraudCohortRecords, labelledResolutions, promiseImpressions, holdoutTopUp);
    }

    /** One order's decisions, episodes, outcomes and learner events, drawn from {@code rnd} in a fixed order. */
    private void emit(Order order, double ageDays, boolean heldOut, Random rnd, Map<String, Integer> versions, Instant now) {
        Order o = order;
        holdoutOrder = heldOut;
        if (holdoutOrder) holdoutOrders++;
        // Inside the lift window (the holdout started 92 days ago) and old enough to have been delivered.
        if (holdoutOrder && ageDays >= 4 && ageDays <= 88) recentHoldoutOrders++;
        // Draw for every order, held out or not, so which customers the (randomly named) holdout picks never shifts
        // the random sequence the rest of the history is drawn from: every other seeded outcome stays the same.
        double splitDraw = rnd.nextDouble();
        if (holdoutOrder && o.packages() == 1 && splitDraw < 0.25) o = split(o);

        double snsConf = 0.55 + rnd.nextDouble() * 0.44;
        double slaConf = 0.5 + rnd.nextDouble() * 0.49;
        // About half of orders hit an exception that is classified; nearly a third of those land just above the act threshold.
        double excDraw = rnd.nextDouble();
        Double excConf = rnd.nextDouble() < 0.5 ? (excDraw < 0.3 ? EXCEPTION_ACT + rnd.nextDouble() * (EXCEPTION_PROPOSED_ACT - EXCEPTION_ACT)
            : excDraw < 0.35 ? 0.35 + rnd.nextDouble() * 0.15 : EXCEPTION_PROPOSED_ACT + rnd.nextDouble() * 0.44) : null;
        o = shapeOutcome(o, snsConf, slaConf, excConf, holdoutOrder, rnd);
        if (o.delivered() && o.deliveredAt().isAfter(now.minus(Duration.ofHours(1)))) o = inTransit(o);
        int slaScore = o.onTime() ? rnd.nextInt(2) : 2 + rnd.nextInt(3);

        List<String> orderEpisodes = new ArrayList<>();
        orderEpisodes.add(sourcingNodeScore(o, snsConf, versions.get("sourcing_node_score")));
        orderEpisodes.add(slaBreachRisk(o, slaConf, slaScore, versions.get("sla_breach_risk"), rnd));
        orderEpisodes.add(paymentRisk(o, versions.get("payment_risk"), rnd));
        if (rnd.nextDouble() < 0.7) orderEpisodes.add(bandit(o, rnd));
        if (o.cod()) orderEpisodes.add(rtoRisk(o, versions.get("rto_risk"), rnd));
        if (excConf != null) orderEpisodes.add(exceptionClassify(o, excConf, versions.get("exception_classify"), rnd));
        boolean addressChecked = ageDays >= 3 && ageDays <= 14 ? rnd.nextDouble() < 0.9 : rnd.nextDouble() < 0.12;
        String addressEpisode = addressChecked ? addressRisk(o, versions.get("address_risk"), rnd) : null;

        for (String ep : orderEpisodes) orderOutcomes(ep, o);
        // Address-check outcomes stopped arriving for most recent orders (OC-07's silent data loss).
        if (addressEpisode != null && (ageDays > 14 || rnd.nextDouble() < 0.5)) orderOutcomes(addressEpisode, o);
        if (o.delivered() && o.returned()) returnRisk(o, versions.get("return_risk"), rnd, now);
        feedLearners(o, now);
        generated.add(o);
    }

    /**
     * XP-01 / §12: the holdout is 5% of customers, picked by the live router's sticky hash of a random experiment id, so
     * on some databases it holds too few orders for the perfect-order headline to be judged (30 per arm). Extra orders
     * for holdout customers, spread over the 90 days and drawn from their own random sequence (the rest of the history
     * is unchanged), top it up to {@link #MIN_HOLDOUT_ORDERS}. Labelled demo data like the rest.
     */
    private void topUpHoldout(java.util.function.Predicate<String> inHoldout, Map<String, Integer> versions, Instant now) {
        if (holdoutExperimentId == null || recentHoldoutOrders >= MIN_HOLDOUT_ORDERS) return;
        List<String> held = new ArrayList<>();
        for (int c = 1; c <= CUSTOMERS; c++) {
            String customer = String.format(Locale.ROOT, "cust-demo-%03d", c);
            if (inHoldout.test(customer)) held.add(customer);
        }
        if (held.isEmpty()) return;
        Random rnd = new Random(20261021L);
        int missing = MIN_HOLDOUT_ORDERS - recentHoldoutOrders;
        for (int k = 0; k < missing; k++) {
            double ageDays = 6.0 + 80.0 * k / missing + rnd.nextDouble() * 0.5;
            Order o = withId(order(k, now.minus(Duration.ofMinutes(Math.round(ageDays * 1440))), ageDays, rnd),
                ORDER_PREFIX + String.format(Locale.ROOT, "h%03d", k + 1), held.get(k % held.size()));
            emit(o, ageDays, true, rnd, versions, now);
            holdoutTopUp++;
        }
    }

    private static Order withId(Order o, String id, String customer) {
        return new Order(id, o.at(), customer, o.channel(), o.shipMethod(), o.orderType(), o.node(), o.secondNode(), o.skuClass(), o.carrier(), o.zip3(),
            o.packages(), o.units(), o.value(), o.cost(), o.delivered(), o.onTime(), o.lateByHours(), o.shippedAt(), o.deliveredAt(), o.eta(), o.wismo(),
            o.chargeback(), o.cod(), o.refused(), o.returned(), o.storeRejected());
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
        String customer = String.format(Locale.ROOT, "cust-demo-%03d", 1 + rnd.nextInt(CUSTOMERS));
        return new Order(id, at, customer, CHANNELS.get(rnd.nextInt(CHANNELS.size())), shipMethod, orderType, node, second,
            SKU_CLASSES.get(rnd.nextInt(SKU_CLASSES.size())), carrier, ZIP3.get(rnd.nextInt(ZIP3.size())), packages, units, value, cost,
            delivered, true, 0, shippedAt, null, eta, false, chargeback, cod, refused, returned, storeRejected);
    }

    /**
     * Whether the order arrived on time, from the node's and carrier's reliability, a store reject, and the decisions:
     * a node score held in the confirm band waited for a person (later), a breach score that acted on its own with low
     * confidence missed more, and one a person re-promised did a little better. An exception classified just above the
     * act threshold (0.50-0.55) was often the wrong type, so its playbook did not fix the order (the T0 demo proposal).
     */
    private static Order shapeOutcome(Order o, double snsConf, double slaConf, Double excConf, boolean holdout, Random rnd) {
        double p = NODE_ON_TIME.get(o.node()) * CARRIER_FACTOR.get(o.carrier()) - (o.storeRejected() ? 0.2 : 0);
        if (holdout) p -= 0.12; // baseline: sourcing without learned node reliability or lane transit times
        if (excConf != null && excConf >= EXCEPTION_ACT && excConf < EXCEPTION_PROPOSED_ACT) p -= 0.75;
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

    /** T0 exception classification for an order that hit an exception: classified from 0.50, "unclear" (triage) below. */
    private String exceptionClassify(Order o, double conf, int version, Random rnd) {
        String truth = o.storeRejected() ? "store_reject" : !o.onTime() ? "carrier_delay" : o.refused() ? "address" : rnd.nextDouble() < 0.5 ? "inventory_short" : "system";
        boolean acts = conf >= EXCEPTION_ACT;
        boolean nearThreshold = acts && conf < EXCEPTION_PROPOSED_ACT;
        String value = !acts ? "unclear" : nearThreshold && rnd.nextDouble() < 0.7 ? "other" : truth;
        Map<String, Object> input = exceptionInput(o.id(), truth, o.channel());
        String id = recordId("exception_classify", o.id());
        insert(id, "exception_classify", version, "order", o.id(), o.id(), Layer.L1_SYSTEM_ONE, acts ? Band.ACT : Band.ESCALATE, value, round4(conf), input, List.of(),
            acts ? List.of() : List.of("low_confidence"), details("T0", Map.of()), Map.of(), o.at().plus(Duration.ofHours(1)), EXCEPTION_TYPES, value, 1.0);
        exceptionRecords++;
        return episode(id, "exception_classify", version, o, "order", o.id(), o.at().plus(Duration.ofHours(1)));
    }

    static Map<String, Object> exceptionInput(String orderId, String type, String channel) {
        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("source", "carrier_delay".equals(type) ? "carrier_webhook" : "store_reject".equals(type) ? "wms" : "workflow");
        exception.put("code", type.toUpperCase(Locale.ROOT));
        exception.put("message", "Order " + orderId + ": " + type.replace('_', ' '));
        return Map.of("exception", exception, "order", Map.of("channel", channel, "status", "released"));
    }

    /**
     * Shadow evidence for the T0 demo proposal (EX-02): {@code n} exception checks evaluated live on the current version
     * and, in shadow, on the candidate version, as the decision runtime records them while a shadow experiment runs.
     * Same input, same subject, a second apart, so the experiment's own paired comparison measures them.
     */
    public int pairShadow(int liveVersion, int shadowVersion, Instant at, int n) {
        Random rnd = new Random(20261020L);
        int pairs = 0;
        for (int i = 0; i < n; i++) {
            double conf = i == 7 ? EXCEPTION_ACT + 0.02 : 0.6 + rnd.nextDouble() * 0.39; // one near-threshold case: the candidate sends it to triage
            String type = EXCEPTION_TYPES.get(rnd.nextInt(6));
            String subject = "demo-shadow-" + String.format(Locale.ROOT, "%03d", i + 1);
            Map<String, Object> input = exceptionInput(subject, type, CHANNELS.get(i % CHANNELS.size()));
            Instant when = at.plusMillis(i * 50L);
            boolean liveActs = conf >= EXCEPTION_ACT, shadowActs = conf >= EXCEPTION_PROPOSED_ACT;
            Map<String, Object> d = details("T0", Map.of());
            records.insert(new DecisionRecord("dr-demo-shadow-live-" + subject, "exception_classify", liveVersion, Mode.LIVE, "exception", subject, subject, Layer.L1_SYSTEM_ONE,
                liveActs ? Band.ACT : Band.ESCALATE, "act", liveActs ? type : "unclear", Map.of(), round4(conf), "heuristic", "demo-history", Json.sha256(input), input, input,
                List.of(), List.of(), 3, 0.0, 0, false, null, ACTOR, d, null, null, null, null, Map.of(), when, input, EXCEPTION_TYPES, liveActs ? type : "unclear", 1.0, liveVersion));
            Map<String, Object> sd = new LinkedHashMap<>(d);
            sd.put("mode", "SHADOW");
            records.insert(new DecisionRecord("dr-demo-shadow-cand-" + subject, "exception_classify", shadowVersion, Mode.SHADOW, "exception", subject, subject, Layer.L1_SYSTEM_ONE,
                shadowActs ? Band.ACT : Band.ESCALATE, "act", shadowActs ? type : "unclear", Map.of(), round4(conf), "heuristic", "demo-history", Json.sha256(input), input, input,
                List.of(), List.of(), 3, 0.0, 0, false, null, ACTOR, sd, null, null, null, null, Map.of(), when.plusMillis(20), input, EXCEPTION_TYPES,
                shadowActs ? type : "unclear", 1.0, shadowVersion));
            pairs++;
        }
        return pairs;
    }

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
                    details("T2", Map.of()), Map.of("human_resolution", resolution(value, value, "confirmed_recommendation", "user:risk")),
                    at, SCORES, String.valueOf(value), 1.0);
                labelledResolutions++;
            }
        }
        // 62-84 days ago: inside the override learner's 90-day window, outside the golden-set learner's 60 days (both
        // key a situation by decision and input hash, so the same group would otherwise be claimed by whichever runs first).
        Map<String, Object> repeat = returnInput(true, 2, 0, 45.0);
        for (int k = 0; k < 21; k++) {
            Instant at = now.minus(Duration.ofDays(62 + k));
            String rma = "rma-demo-override-" + k;
            insert(recordId("return_risk", rma), "return_risk", version, "return", rma, null, Layer.L1_SYSTEM_ONE, Band.CONFIRM, 2, 0.66, repeat, List.of(), List.of(),
                details("T2", Map.of()), Map.of("human_resolution", resolution(1, 2, "receipt_present_low_value", k % 2 == 0 ? "user:risk" : "user:cs")),
                at, SCORES, "2", 1.0);
            labelledResolutions++;
        }
    }

    /**
     * OC-06's label of a person's resolution, the shape {@code HumanResolutionCapture} writes on {@code task.completed}:
     * the value chosen ({@code value}, repeated as {@code action} for readers of the earlier shape), what was clicked
     * ({@code outcome}: accept when the value is the recommendation, else override), why, by whom, and the recommendation.
     */
    static Map<String, Object> resolution(Object value, Object recommended, String reasonCode, String resolvedBy) {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("value", String.valueOf(value));
        label.put("action", String.valueOf(value));
        label.put("outcome", String.valueOf(value).equals(String.valueOf(recommended)) ? "accept" : "override");
        label.put("reasonCode", reasonCode);
        label.put("resolvedBy", resolvedBy);
        label.put("recommendedValue", String.valueOf(recommended));
        return label;
    }

    /**
     * DF-02: breach-risk scores a person was asked to confirm over the last 60 days, each a different order, labelled
     * with the score the person chose. People agreed with about half of the barely-confident ones and most of the
     * confident ones, so the calibration curve has three bins with enough cases (20 or more) to correct. Every input
     * differs, so no golden-case or rule candidate forms from them.
     */
    private void breachEscalations(int version, Instant now) {
        Random rnd = new Random(20261023L);
        double[][] bins = {{0.50, 0.50}, {0.60, 0.62}, {0.70, 0.86}}; // {bin start, share of people who agreed}
        for (int b = 0; b < bins.length; b++) {
            for (int k = 0; k < 24; k++) {
                String subject = String.format(Locale.ROOT, "demo-esc-%d%02d", b, k + 1);
                double conf = round4(bins[b][0] + 0.005 + rnd.nextDouble() * 0.09);
                int recommended = 1 + rnd.nextInt(3);
                boolean agreed = rnd.nextDouble() < bins[b][1];
                int chosen = agreed ? recommended : recommended + (recommended >= 3 || rnd.nextBoolean() && recommended > 0 ? -1 : 1);
                Instant at = now.minus(Duration.ofHours(30 + rnd.nextInt(60 * 24)));
                Map<String, Object> input = Map.of("order", Map.of("id", subject, "state", "released", "hours_in_state", 4 + rnd.nextInt(40),
                    "promise_hours_remaining", 6 + rnd.nextInt(60), "ship_method", rnd.nextDouble() < 0.75 ? "ground" : "express"),
                    "node", Map.of("backlog_units", 60 + rnd.nextInt(500), "reject_rate", round2(0.02 + rnd.nextDouble() * 0.1), "minutes_to_cutoff", 20 + rnd.nextInt(300)),
                    "carrier", Map.of("on_time_rate_7d", round2(0.82 + rnd.nextDouble() * 0.12)));
                String reason = agreed ? "confirmed_recommendation" : chosen > recommended ? "carrier_backlog_known" : "promise_still_reachable";
                insert(recordId("sla_breach_risk", subject), "sla_breach_risk", version, "order", subject, subject, Layer.L1_SYSTEM_ONE, Band.CONFIRM, recommended, conf,
                    input, List.of(), List.of(), details("T1", Map.of()), Map.of("human_resolution", resolution(chosen, recommended, reason, k % 3 == 0 ? "user:ops" : "user:admin")),
                    at, SCORES, String.valueOf(recommended), 1.0);
                labelledResolutions++;
            }
        }
    }

    /**
     * FD-01 / FD-03: payment-risk decisions old enough that their chargeback window (120 days by default) has closed,
     * so {@code FraudLabels.sampled} counts them: 180 orders aged 125 to 300 days, about a third inside the tuner's
     * 30-day cohort. Fraud follows the velocity and linkage signals; each label comes from the real
     * {@code FraudLabelCapture} reading a chargeback (fraud) or a delivery with no chargeback (not fraud). Orders the
     * model flagged with a confidence just above its act threshold were mostly good customers, so the fraud threshold
     * tuner finds a better threshold to propose. Only the fraud-label capture sees these events.
     */
    private void fraudCohort(int version, Instant now) {
        Random rnd = new Random(20261024L);
        for (int i = 0; i < FRAUD_COHORT; i++) {
            String orderId = FRAUD_PREFIX + String.format(Locale.ROOT, "%04d", i + 1);
            double ageDays = i % 3 == 0 ? 122.0 + rnd.nextDouble() * 26.0 : 152.0 + rnd.nextDouble() * 148.0;
            Instant at = now.minus(Duration.ofMinutes(Math.round(ageDays * 1440)));
            double r1 = rnd.nextDouble(), r24 = rnd.nextDouble(), ra = rnd.nextDouble(), rd = rnd.nextDouble();
            int v1 = r1 < 0.6 ? 0 : r1 < 0.85 ? 1 : 2 + rnd.nextInt(4);
            int v24 = v1 + (r24 < 0.55 ? 0 : r24 < 0.85 ? 1 : 2 + rnd.nextInt(4));
            int address = ra < 0.7 ? 0 : ra < 0.85 ? 1 : ra < 0.95 ? 2 : 3 + rnd.nextInt(4);
            int device = rd < 0.75 ? 0 : rd < 0.9 ? 1 : 2 + rnd.nextInt(3);
            // Sandbox and gateway test tokens are shared by many customers, so the card-sharing count says little here
            // (payment_risk.yaml leaves it to the model); fraud does not depend on it.
            int tokenCustomers = rnd.nextDouble() < 0.4 ? 1 : 4 + rnd.nextInt(20);
            // About one order in ten was fraud, nearly all of them with a velocity or linkage signal; a clean order
            // almost never was, so a clean live order's learned signal rates stay low and its L0 rule still clears it.
            double risk = 0.01 + (v1 >= 2 ? 0.2 : 0) + (v24 >= 4 ? 0.12 : 0) + (address >= 3 ? 0.25 : address == 2 ? 0.08 : 0) + (device >= 2 ? 0.25 : 0);
            boolean fraud = rnd.nextDouble() < clamp(risk, 0.01, 0.85);
            double total = round2(40 + rnd.nextDouble() * 260);
            Map<String, Object> payment = new LinkedHashMap<>();
            payment.put("velocity_1h", v1);
            payment.put("velocity_24h", v24);
            payment.put("address_linkage_count", address);
            payment.put("device_linkage_count", device);
            payment.put("token_customer_count", tokenCustomers);
            payment.put("billing_shipping_mismatch", address > 1 && rnd.nextDouble() < 0.5);
            Map<String, Object> input = Map.of("order", Map.of("id", orderId, "total", total, "channel", CHANNELS.get(rnd.nextInt(CHANNELS.size()))),
                "payment", payment, "agent", Map.of("mandate_present", false));
            // The decision's own L0 rule (no_risk_signals) cleared these without a model call.
            boolean clean = v1 <= 1 && v24 <= 2 && address <= 2 && device <= 1 && !Boolean.TRUE.equals(payment.get("billing_shipping_mismatch"));
            String id = recordId("payment_risk", orderId);
            if (clean) {
                insert(id, "payment_risk", version, "order", orderId, orderId, Layer.L0_RULE, Band.ACT, false, 1.0, input, List.of("no_risk_signals"), List.of("no_risk_signals"),
                    details("T2", Map.of()), Map.of(), at, BOOLS, "false", 1.0);
            } else {
                // Flagged: fraud mostly scores well above the act threshold; good customers cluster just above it.
                boolean flagged = fraud ? rnd.nextDouble() < 0.85 : rnd.nextDouble() < 0.45;
                double conf = round4(!flagged ? 0.6 + rnd.nextDouble() * 0.35 : fraud ? 0.9 + rnd.nextDouble() * 0.09 : 0.88 + rnd.nextDouble() * 0.06);
                insert(id, "payment_risk", version, "order", orderId, orderId, Layer.L1_SYSTEM_ONE, band(conf, 0.9, 0.5), flagged, conf, input, List.of(),
                    flagged ? List.of("high_risk_payment") : List.of(), details("T2", Map.of()), Map.of(), at, BOOLS, String.valueOf(flagged), 1.0);
            }
            fraudCohortRecords++;
            // The label, from the real capture: a fraud chargeback a month after delivery, or a delivery nobody disputed.
            Instant delivered = at.plus(Duration.ofDays(4));
            Event e = fraud
                ? new Event("evt-demo-" + orderId + "-chargeback", TenantContext.require(), "order", orderId, 0, "payment.chargeback",
                    Map.of("orderId", orderId, "fraud", true, "amount", total, "reasonCode", "fraudulent"), Actor.system("demo-seed"), null, orderId, Map.of(), delivered.plus(Duration.ofDays(30)))
                : new Event("evt-demo-" + orderId + "-delivered", TenantContext.require(), "order", orderId, 0, "order.delivered",
                    Map.of("orderId", orderId, "onTime", true, "lateByHours", 0), Actor.system("demo-seed"), null, orderId, Map.of(), delivered);
            fraudLabels.on(e);
            eventCount++;
        }
    }

    /**
     * CX-02: storefront promise impressions for the history's orders. Each order was bought after seeing a promise with
     * a buffer of 0, 1 or 2 days (its promise date is then that much later); for every purchase there are the
     * impressions of shoppers who did not buy, more of them the longer the buffer. The promise-buffer learner joins
     * them to the deliveries the history fed to the learners and weighs conversion against keeping the promise.
     */
    private void storefrontImpressions() {
        Random rnd = new Random(20261025L);
        for (Order o : generated) {
            int buffer = rnd.nextInt(BUFFER_CONVERSION.length);
            int promisedDays = ("express".equals(o.shipMethod()) ? 2 : 4) + buffer;
            String segment = "channel:" + o.channel();
            if (promiseConversions.record("imp-demo-" + o.id(), segment, o.id(), promisedDays, buffer, true, o.at())) promiseImpressions++;
            double lost = (1 - BUFFER_CONVERSION[buffer]) / BUFFER_CONVERSION[buffer];
            int missed = (int) lost + (rnd.nextDouble() < lost - (int) lost ? 1 : 0);
            for (int k = 0; k < missed; k++) {
                if (promiseConversions.record("imp-demo-" + o.id() + "-" + k, segment, null, promisedDays, buffer, false, o.at().minus(Duration.ofMinutes(5 + k)))) promiseImpressions++;
            }
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
        String dayPart = dayPart(o.at());
        packages.add(pkg(o.node(), o.carrier(), o.skuClass(), o.eta(), dayPart));
        if (o.packages() > 1) packages.add(pkg(o.secondNode(), o.carrier(), o.skuClass(), o.eta(), dayPart));
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

    /**
     * A package as {@code order.sourced} carries it. The stated on-time probability ({@code onTime}, the same before any
     * learned correction: {@code onTimeBase}) is the node and carrier's nominal rate less {@link #STATED_HAIRCUT}; real
     * orders also waited for confirmations and hit exceptions, so they arrived on time a few points less often than
     * stated, which is the gap the lane calibration (CX-01) learns to correct.
     */
    static Map<String, Object> pkg(String node, String carrier, String skuClass, LocalDate eta, String dayPart) {
        double stated = statedOnTime(node, carrier);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", node);
        m.put("skuClass", skuClass);
        m.put("eta", eta.toString());
        m.put("onTime", stated);
        m.put("onTimeBase", stated);
        m.put("dayPart", dayPart);
        m.put("backlogUnits", 40 + Math.abs(node.hashCode() % 300));
        return m;
    }

    static double statedOnTime(String node, String carrier) {
        return round2(clamp(NODE_ON_TIME.get(node) * CARRIER_FACTOR.get(carrier) - STATED_HAIRCUT, 0.05, 0.99));
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
        if (holdoutOrder && holdoutExperimentId != null) d.put("experiment", Map.of("experimentId", holdoutExperimentId, "arm", "holdout"));
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

    /** FS-02: the one day-part definition, on the tenant's clock (tenant.timezone), as the live sourcing stamps it. */
    private String dayPart(Instant at) { return ai.intentoms.learning.model.DayPart.of(at, zone); }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10_000) / 10_000.0; }
}
