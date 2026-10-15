package ai.intentoms.sourcing;

import ai.intentoms.finance.landedcost.LandedCost;
import ai.intentoms.finance.landedcost.LandedCostService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.pnl.DefaultOrderPnlCalculator;
import ai.intentoms.finance.pnl.OrderPnlCalculator;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.config.SourcingSettings;
import ai.intentoms.sourcing.economics.EconomicsCalculator;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.plan.BuildContext;
import ai.intentoms.sourcing.plan.PenaltyLookup;
import ai.intentoms.sourcing.rates.RateCard;
import ai.intentoms.sourcing.rates.ZipGeo;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared fixtures for unit tests: demo rate card, nodes, requests, Spring-free economics. */
public final class Fixtures {
    private Fixtures() {}

    public static final Path DEMO_DIR = Path.of("..", "..", "config", "tenants", "demo").toAbsolutePath().normalize();
    public static final Instant NOON_UTC = Instant.parse("2026-10-06T16:00:00Z"); // Tuesday 12:00 New York, 11:00 Chicago — before every cutoff

    public static RateCard demoCard() {
        try { return RateCard.fromYaml(Files.readString(DEMO_DIR.resolve("shipping-rates.yaml"))); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    public static TimeSource frozen(Instant at) {
        TimeSource t = new TimeSource();
        t.override(Clock.fixed(at, ZoneOffset.UTC));
        return t;
    }

    /** Landed cost stub: 10% duty + $5 brokerage for cross-border, zero domestic. */
    public static LandedCostService landed() {
        return (origin, dest, hs, value, at) -> {
            if (origin.equalsIgnoreCase(dest)) return LandedCost.zero(value.currency(), true);
            Money duty = value.multiply(0.10);
            Money brokerage = Money.of(5.0, value.currency());
            return new LandedCost(duty, brokerage, Money.zero(value.currency()), duty.add(brokerage), "stub", false);
        };
    }

    public static OrderPnlCalculator pnl() {
        return in -> DefaultOrderPnlCalculator.calculate(in, new BigDecimal("0.029"), new BigDecimal("0.30"), new BigDecimal("0.05"));
    }

    public static EconomicsCalculator economics() { return new EconomicsCalculator(landed(), pnl(), frozen(NOON_UTC)); }

    public static Node dc(String id, double lat, double lon, String state, double labour) {
        return new Node(id, id, Node.TYPE_DC, lat, lon, "US", state, null, Set.of("ship", "hazmat"), 10_000, LocalTime.of(17, 0), "America/New_York",
            Map.of("backlog", 100, "pick_rate", 0.97, "reject_rate", 0.01, "staffing", 0.9), Map.of("labour_per_unit", labour, "packaging", 0.65, "handling", 0.40), Map.of(), true, 0.0);
    }

    public static Node store(String id, double lat, double lon, String state) {
        return new Node(id, id, Node.TYPE_STORE, lat, lon, "US", state, null, Set.of("ship", "bopis", "sfs", "same_day"), 200, LocalTime.of(14, 0), "America/Chicago",
            Map.of("backlog", 10, "pick_rate", 0.9, "reject_rate", 0.05, "staffing", 0.8), Map.of("labour_per_unit", 2.40, "packaging", 0.90, "handling", 0.60), Map.of(), true, 0.0);
    }

    public static Node vendorMx() {
        return new Node("VND-MX", "Monterrey", Node.TYPE_VENDOR, 25.6866, -100.3161, "MX", "NL", null, Set.of("ship"), 2000, LocalTime.of(12, 0), "America/Monterrey",
            Map.of("reject_rate", 0.02, "staffing", 0.9), Map.of("labour_per_unit", 0.80, "packaging", 0.50, "handling", 0.30), Map.of("originCountry", "MX"), true, 0.0);
    }

    public static SourcingRequest.RequestLine line(String id, String sku, int qty, double price, double cogs) {
        return new SourcingRequest.RequestLine(id, sku, qty, Money.usd(price), Money.zero("USD"), Map.of("cogs", cogs, "weight_kg", 0.5));
    }

    public static SourcingRequest.RequestLine line(String id, String sku, int qty, double price, double cogs, Map<String, Object> extra) {
        Map<String, Object> attrs = new LinkedHashMap<>(Map.of("cogs", cogs, "weight_kg", 0.5));
        attrs.putAll(extra);
        return new SourcingRequest.RequestLine(id, sku, qty, Money.usd(price), Money.zero("USD"), attrs);
    }

    public static SourcingRequest request(String method, String zip, String state, List<SourcingRequest.RequestLine> lines) {
        return new SourcingRequest("ord_test", lines, new SourcingRequest.Destination(zip, state, "US"), method, "standard_ship", SourcingRequest.Constraints.none(), Money.zero("USD"));
    }

    /** Candidate with coverage computed from a stock map and distance/transit from the demo card. */
    public static Candidate candidate(Node node, SourcingRequest req, Map<String, Integer> stock, RateCard card) {
        var dest = ZipGeo.locate(req.destination().zip(), req.destination().state(), req.destination().country());
        double distance = dest.distanceKm(node.lat(), node.lon());
        Map<String, Integer> coverage = new java.util.TreeMap<>();
        boolean complete = true;
        for (var e : req.unitsBySku().entrySet()) {
            int q = Math.min(stock.getOrDefault(e.getKey(), 0), e.getValue());
            if (q > 0) coverage.put(e.getKey(), q);
            if (q < e.getValue()) complete = false;
        }
        int transit = req.isPickup() ? 0 : card.transitDays(req.shipMethod(), distance) + dest.transitPenaltyDays();
        return new Candidate(node, coverage, complete, Math.round(distance * 10) / 10.0, transit);
    }

    public static BuildContext context(SourcingRequest req, List<Candidate> candidates, Map<String, Double> soft, SourcingSettings.Weights weights, double floor, int k) {
        return new BuildContext(req, candidates, demoCard(), soft, NOON_UTC, NOON_UTC.atZone(ZoneOffset.UTC).toLocalDate(), k, false, 12.0, floor, 50.0, weights, PenaltyLookup.nodeLevel());
    }
}
