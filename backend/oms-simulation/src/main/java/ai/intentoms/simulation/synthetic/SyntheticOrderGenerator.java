package ai.intentoms.simulation.synthetic;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.capture.CatalogService;
import ai.intentoms.order.model.CatalogItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;

/**
 * Deterministic synthetic order generator (FR-SIM-05). Produces {@link CaptureRequest}s with realistic variety: weighted
 * channel and order-type mixes, 1..N lines drawn from a SKU pool with catalogue prices, destinations drawn from a ZIP pool,
 * card tenders covering the merchandise total plus shipping, a free-text intent note on a share of orders, and agent
 * provenance on {@code agent}-channel orders. The same spec and seed always yield the same requests, so load tests and
 * demo seeds are reproducible.
 */
@Component
public class SyntheticOrderGenerator {
    static final List<String> DEFAULT_SKUS = List.of("TEE-BLK-M", "TEE-WHT-L", "HOOD-GRY-M", "JEAN-IND-32", "SNK-RUN-10", "CAP-NVY", "SOCK-3PK", "BAG-TOTE", "MUG-CER", "YOGA-MAT", "BOTL-INS", "LAMP-DSK");
    static final List<String> DEFAULT_ZIPS = List.of("10001|New York|NY", "60611|Chicago|IL", "75201|Dallas|TX", "94105|San Francisco|CA", "98101|Seattle|WA", "33101|Miami|FL", "30301|Atlanta|GA", "89501|Reno|NV", "07102|Newark|NJ");
    static final Map<String, Double> DEFAULT_CHANNELS = Map.of("web", 0.65, "marketplace", 0.2, "pos", 0.05, "agent", 0.1);
    static final Map<String, Double> DEFAULT_ORDER_TYPES = Map.of("standard_ship", 1.0);
    static final List<String> DEFAULT_NOTES = List.of(
        "Please ship everything together in one delivery",
        "Send each item as soon as it is ready, I do not mind separate boxes",
        "I need the sneakers before Friday, the rest can come later",
        "This is a gift for my sister, please no receipt in the box",
        "Leave the parcel with the concierge",
        "Can I pick this up in your downtown store instead?");
    static final List<String> FIRST_NAMES = List.of("Jane", "Omar", "Priya", "Luis", "Mei", "Noah", "Ava", "Kwame", "Sofia", "Ethan");
    static final List<String> LAST_NAMES = List.of("Doe", "Haddad", "Natarajan", "Ortega", "Chen", "Okafor", "Rossi", "Mensah", "Novak", "Park");
    static final List<String> TIERS = List.of("standard", "standard", "standard", "silver", "gold", "platinum");

    private final Function<String, Optional<Money>> prices;

    @Autowired
    public SyntheticOrderGenerator(CatalogService catalog) {
        this(sku -> catalog.find(sku).map(i -> i.price("USD")));
    }

    /** Price lookup injected directly (unit tests, offline generation). */
    public SyntheticOrderGenerator(Function<String, Optional<Money>> prices) { this.prices = prices; }

    public List<CaptureRequest> generate(SyntheticSpec spec) {
        Random rnd = new Random(spec.seed());
        List<String> skus = spec.skuPool().isEmpty() ? DEFAULT_SKUS : spec.skuPool();
        List<String> zips = spec.zipPool().isEmpty() ? DEFAULT_ZIPS : spec.zipPool();
        List<String> notes = spec.intentNoteSamples().isEmpty() ? DEFAULT_NOTES : spec.intentNoteSamples();
        Weighted channels = new Weighted(spec.channelMix().isEmpty() ? DEFAULT_CHANNELS : spec.channelMix());
        Weighted types = new Weighted(spec.orderTypeMix().isEmpty() ? DEFAULT_ORDER_TYPES : spec.orderTypeMix());
        Map<String, Money> priceCache = new LinkedHashMap<>();
        List<CaptureRequest> out = new ArrayList<>(spec.count());
        for (int i = 0; i < spec.count(); i++) {
            String channel = channels.pick(rnd);
            String orderType = types.pick(rnd);
            String channelOrderId = String.format(Locale.ROOT, "%s-%d-%05d", spec.channelOrderPrefix(), spec.seed(), i + 1);
            int customerNo = 1 + rnd.nextInt(Math.max(10, spec.count() / 2));
            String first = FIRST_NAMES.get(rnd.nextInt(FIRST_NAMES.size())), last = LAST_NAMES.get(rnd.nextInt(LAST_NAMES.size()));
            String customerRef = "syn-cust-" + customerNo;
            Map<String, Object> history = Map.of("split_acceptance_rate", round(0.2 + rnd.nextDouble() * 0.75), "orders_12m", rnd.nextInt(12), "returns_12m", rnd.nextInt(3));
            CaptureRequest.CustomerInput customer = new CaptureRequest.CustomerInput(customerRef, first + " " + last, (first + "." + last + customerNo + "@example.com").toLowerCase(Locale.ROOT),
                "+1415555" + String.format(Locale.ROOT, "%04d", rnd.nextInt(10_000)), TIERS.get(rnd.nextInt(TIERS.size())), rnd.nextInt(10) < 3, null, history);

            int lineCount = 1 + rnd.nextInt(spec.maxLinesPerOrder());
            List<CaptureRequest.LineInput> lines = new ArrayList<>(lineCount);
            List<String> used = new ArrayList<>();
            Money merchandise = Money.zero("USD");
            for (int l = 0; l < lineCount; l++) {
                String sku = skus.get(rnd.nextInt(skus.size()));
                if (used.contains(sku)) continue;
                used.add(sku);
                int qty = 1 + rnd.nextInt(spec.maxQtyPerLine());
                Money price = priceCache.computeIfAbsent(sku, s -> prices.apply(s).orElseGet(() -> pseudoPrice(s)));
                lines.add(new CaptureRequest.LineInput("L" + (lines.size() + 1), sku, null, qty, price, null, null, null));
                merchandise = merchandise.add(price.multiply(qty));
            }

            String[] zip = zip(zips.get(rnd.nextInt(zips.size())));
            boolean pickup = "bopis".equals(orderType);
            CaptureRequest.AddressInput shipTo = new CaptureRequest.AddressInput(pickup ? "pickup" : "ship_to", first + " " + last, (100 + rnd.nextInt(9_900)) + " " + STREETS.get(rnd.nextInt(STREETS.size())),
                null, zip[1], zip[2], zip[0], "US", customer.phone(), rnd.nextInt(10) < 8 ? "residential" : "commercial");
            String shipMethod = pickup ? "pickup" : rnd.nextInt(10) < 8 ? "ground" : "express";
            Money shipping = pickup ? Money.zero("USD") : Money.usd(shipMethod.equals("express") ? "14.00" : merchandise.amount().compareTo(new BigDecimal("75")) >= 0 ? "0.00" : "6.00");
            List<CaptureRequest.ChargeInput> charges = shipping.isZero() ? List.of() : List.of(new CaptureRequest.ChargeInput("shipping", shipMethod, shipping, "Shipping"));
            Money tendered = merchandise.add(shipping).multiply(new BigDecimal("1.10")).add(Money.usd("5.00"));
            tendered = new Money(tendered.amount().setScale(2, RoundingMode.CEILING), "USD");

            List<CaptureRequest.NoteInput> noteInputs = rnd.nextDouble() < spec.noteRate()
                ? List.of(new CaptureRequest.NoteInput("customer", customer.email(), notes.get(rnd.nextInt(notes.size())), false)) : List.of();
            List<String> intentChoices = rnd.nextInt(10) < 2 ? List.of(rnd.nextBoolean() ? "ship_complete" : "ship_as_available") : List.of();

            CaptureRequest.ProvenanceInput provenance = null;
            List<CaptureRequest.TenderInput> tenders;
            if ("agent".equals(channel)) {
                String agentId = "shopbot-" + (1 + rnd.nextInt(3));
                String mandateId = "mandate-" + spec.seed() + "-" + (i + 1);
                provenance = new CaptureRequest.ProvenanceInput(agentId, "ucp", mandateId, tendered.add(Money.usd("50.00")), "Buy " + describe(lines) + " for " + first + ", budget $" + tendered.amount().intValue(),
                    null, customerRef, Map.of("synthetic", true));
                tenders = List.of(new CaptureRequest.TenderInput("agent_token", mandateId, tendered, Map.of("agentId", agentId)));
            } else {
                tenders = List.of(new CaptureRequest.TenderInput("card", "tok_visa_syn_" + (i + 1), tendered, Map.of()));
            }
            out.add(new CaptureRequest(channel, channelOrderId, orderType, customerRef, customer, lines, List.of(shipTo), charges, tenders, noteInputs, intentChoices, shipMethod, null, "USD",
                Map.of("synthetic", true, "seed", spec.seed()), provenance));
        }
        return out;
    }

    static final List<String> STREETS = List.of("Market St", "Michigan Ave", "Main St", "Broadway", "Pine St", "Elm St", "Ocean Dr", "Peachtree St", "Lake Shore Dr", "5th Ave");

    /** "zip" or "zip|city|state"; unknown zips get a plausible city/state from the ZIP3 prefix region. */
    static String[] zip(String entry) {
        String[] parts = entry.split("\\|");
        if (parts.length >= 3) return new String[]{parts[0].trim(), parts[1].trim(), parts[2].trim()};
        String z = parts[0].trim();
        for (String d : DEFAULT_ZIPS) { String[] dp = d.split("\\|"); if (dp[0].substring(0, Math.min(3, dp[0].length())).equals(z.substring(0, Math.min(3, z.length())))) return new String[]{z, dp[1], dp[2]}; }
        return new String[]{z, "Springfield", "IL"};
    }

    /** Deterministic price for a SKU the catalogue does not know: 9.00..129.00 from the SKU's hash. */
    static Money pseudoPrice(String sku) {
        int h = Math.abs(sku.hashCode() % 121);
        return Money.usd(String.format(Locale.ROOT, "%d.00", 9 + h));
    }

    private static String describe(List<CaptureRequest.LineInput> lines) {
        List<String> parts = new ArrayList<>();
        for (CaptureRequest.LineInput l : lines) parts.add(l.qty() + "x " + l.sku());
        return String.join(", ", parts);
    }

    private static double round(double d) { return Math.round(d * 100) / 100d; }

    /** Weighted choice with a stable iteration order (keys sorted) so a seed reproduces the same picks. */
    static final class Weighted {
        private final List<String> keys = new ArrayList<>();
        private final List<Double> cumulative = new ArrayList<>();
        private final double total;

        Weighted(Map<String, Double> weights) {
            double sum = 0;
            for (String k : weights.keySet().stream().sorted().toList()) {
                double w = weights.get(k) == null ? 0 : Math.max(0, weights.get(k));
                if (w == 0) continue;
                sum += w;
                keys.add(k);
                cumulative.add(sum);
            }
            if (keys.isEmpty()) throw new IllegalArgumentException("A mix needs at least one positive weight");
            total = sum;
        }

        String pick(Random rnd) {
            double r = rnd.nextDouble() * total;
            for (int i = 0; i < keys.size(); i++) if (r < cumulative.get(i)) return keys.get(i);
            return keys.get(keys.size() - 1);
        }
    }

    /** Catalogue attributes for a SKU (digital, hazmat...), exposed for callers filtering pools. */
    public static boolean flag(CatalogItem item, String key) { return item != null && item.flag(key); }
}
