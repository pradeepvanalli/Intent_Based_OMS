package ai.intentoms.orchestration.seed;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ~25 sample orders of the demo tenant (UC-01..UC-08): web / marketplace / POS channels; ship-to-home, BOPIS and
 * ship-from-store types; gift / urgent / "together" notes for the intent decision; a hazmat perfume by ground; an
 * age-restricted wine without age verification (compliance hold); two heavy, cheap orders far from the stock that breach
 * the margin floor; one declined card. Channel order ids are stable so re-running the seeder replays instead of duplicating.
 *
 * <p>AG01-AG04 (Agentic Control Tower, added for the agentic-control-tower feature): orders engineered to stall for a
 * different root cause each, backdated well past {@code autonomy.stuck_after_minutes} so a scan finds them
 * immediately instead of waiting real time. AG01's decline and AG02's address are facts checked at capture time
 * (deterministic regardless of timing); AG03/AG04 pin a BOPIS pickup to node {@code ST-AUS-03} (the worst-health
 * store in {@code nodes.yaml}: backlog 40, reject rate 0.12) so the node signal is real, not fabricated. AG04 also
 * requests more of {@code CNDL-SOY} than the deterministic demo inventory generator gives that store (short by a
 * few units), so it carries two genuine, competing signals (inventory short + node backlog) at once — the
 * "ambiguous overlapping signals" case docs/UC_AGENTIC_CONTROL_TOWER.md walks through.</p>
 */
final class DemoOrders {
    private DemoOrders() {}

    /**
     * One sample: the capture request plus the lifecycle the seeder drives afterwards ({@code deliveredDaysAgo} back-dates
     * the delivery so return-window scenarios work on a fresh database).
     */
    record Sample(String key, CaptureRequest request, boolean ship, boolean deliver, boolean bopisPickup, boolean partialShip, boolean storeReject, int deliveredDaysAgo) {
        Sample(String key, CaptureRequest request, boolean ship, boolean deliver, boolean bopisPickup, boolean partialShip, boolean storeReject) {
            this(key, request, ship, deliver, bopisPickup, partialShip, storeReject, 0);
        }
    }

    /** The Java demo set as a {@link SampleOrdersFile.SampleSet}: orders, three returns and two exceptions (UC-02, UC-03). */
    static SampleOrdersFile.SampleSet sampleSet() {
        List<SampleOrdersFile.ReturnSpec> returns = List.of(
            // instant-refund candidate: cheap tote, receipt present, clean history
            new SampleOrdersFile.ReturnSpec("D03", "1", 1, "changed_mind", "Changed my mind", true, 0),
            // high-risk: flagged customer (returns.flagged_customers), high-value headphones
            new SampleOrdersFile.ReturnSpec("D04", "1", 1, "not_received", "Never arrived, box was empty", true, 0),
            // out of window: delivered 45 days ago
            new SampleOrdersFile.ReturnSpec("D05", "2", 1, "wrong_size", "Socks too small", true, 45));
        List<SampleOrdersFile.ExceptionSpec> exceptions = List.of(
            new SampleOrdersFile.ExceptionSpec("R01", "cs", "address", "address_unverifiable", "Customer reports the apartment number is missing from the address", null),
            new SampleOrdersFile.ExceptionSpec("S01", "carrier", "carrier_delay", "carrier_delay", "Carrier scan shows the parcel delayed at the hub for 2 days", null));
        return new SampleOrdersFile.SampleSet(all(), returns, exceptions);
    }

    static List<Sample> all() {
        List<Sample> s = new ArrayList<>();
        // Delivered orders (return candidates)
        s.add(new Sample("D01", web("DEMO-WEB-1001", cust("cust-1001", "Dana Whitfield", "dana@example.com", "gold", 0.2, 1), "standard_ship", List.of(l("1", "HOOD-GRY-M", 2, 68), l("2", "SNK-RUN-10", 1, 145)),
            addr("233 S Wacker Dr", "Chicago", "IL", "60606"), 281, "ground", List.of("This is a birthday gift, please send everything together in one box."), List.of()), true, true, false, false, false));
        s.add(new Sample("D02", web("DEMO-WEB-1002", cust("cust-1002", "Marcus Lee", "marcus@example.com", "silver", 0.8, 0), "standard_ship", List.of(l("1", "TEE-BLK-M", 3, 28), l("2", "CAP-NVY", 1, 32)),
            addr("1 Market St", "San Francisco", "CA", "94105"), 116, "ground", List.of(), List.of("ship_as_available")), true, true, false, false, false));
        s.add(new Sample("D03", mkt("DEMO-MKT-2001", cust("cust-2001", "Priya Natarajan", "priya@example.com", "standard", 0.5, 2), List.of(l("1", "BAG-TOTE", 1, 45), l("2", "MUG-CER", 2, 18)),
            addr("500 Boylston St", "Boston", "MA", "02116"), 81, "ground"), true, true, false, false, false));
        s.add(new Sample("D04", web("DEMO-WEB-1004", cust("cust-fraud-7", "Pat Flagged", "pat.flagged@example.com", "standard", 0.5, 7), "standard_ship", List.of(l("1", "HEAD-BT", 1, 199)),
            addr("1600 Amphitheatre Pkwy", "Mountain View", "CA", "94043"), 199, "two_day", List.of("Need it fast please"), List.of()), true, true, false, false, false));
        s.add(new Sample("D05", web("DEMO-WEB-1005", cust("cust-1005", "Olivia Brown", "olivia@example.com", "gold", 0.9, 0), "standard_ship", List.of(l("1", "JEAN-IND-32", 1, 128), l("2", "SOCK-3PK", 2, 36)),
            addr("350 5th Ave", "New York", "NY", "10118"), 200, "ground", List.of(), List.of()), true, true, false, false, false));
        s.add(new Sample("D06", web("DEMO-WEB-1006", cust("cust-1006", "Ethan Garcia", "ethan@example.com", "silver", 0.7, 1), "standard_ship", List.of(l("1", "YOGA-MAT", 1, 40), l("2", "BOTL-INS", 1, 35)),
            addr("1200 Pennsylvania Ave", "Washington", "DC", "20004"), 75, "ground", List.of("Urgent, I need the mat before Friday's class, the bottle can come later"), List.of()), true, true, false, false, false));
        // Shipped, not delivered
        s.add(new Sample("S01", web("DEMO-WEB-1007", cust("cust-1007", "Sophia Martinez", "sophia@example.com", "standard", 0.5, 0), "standard_ship", List.of(l("1", "JKT-DWN-L", 1, 260)),
            addr("1901 Main St", "Dallas", "TX", "75201"), 260, "ground", List.of(), List.of()), true, false, false, false, false));
        s.add(new Sample("S02", mkt("DEMO-MKT-2002", cust("cust-2002", "Liam Johnson", "liam@example.com", "standard", 0.5, 0), List.of(l("1", "COFF-MKR", 1, 89), l("2", "MUG-CER", 4, 18)),
            addr("400 Broad St", "Seattle", "WA", "98109"), 161, "ground"), true, false, false, false, false));
        s.add(new Sample("S03", web("DEMO-WEB-1009", cust("cust-1009", "Ava Wilson", "ava@example.com", "gold", 0.6, 1), "standard_ship", List.of(l("1", "TEE-WHT-L", 2, 28), l("2", "LAMP-DSK", 1, 65)),
            addr("1 Peachtree St", "Atlanta", "GA", "30303"), 121, "ground", List.of(), List.of()), true, false, false, true, false));
        s.add(new Sample("S04", web("DEMO-WEB-1010", cust("cust-1010", "Noah Davis", "noah@example.com", "silver", 0.5, 0), "standard_ship", List.of(l("1", "PERF-50ML", 1, 120)),
            addr("2 Rodeo Dr", "Beverly Hills", "CA", "90210"), 120, "ground", List.of(), List.of()), true, false, false, false, false));
        // Released / in fulfilment
        s.add(new Sample("R01", web("DEMO-WEB-1011", cust("cust-1011", "Mia Anderson", "mia@example.com", "standard", 0.5, 0), "standard_ship", List.of(l("1", "BLND-PRO", 1, 179)),
            addr("233 N Michigan Ave", "Chicago", "IL", "60601"), 179, "ground", List.of(), List.of()), false, false, false, false, false));
        s.add(new Sample("R02", web("DEMO-WEB-1012", cust("cust-1012", "James Taylor", "james@example.com", "gold", 0.3, 0), "standard_ship", List.of(l("1", "TENT-2P", 1, 240), l("2", "BOTL-INS", 2, 35)),
            addr("1600 Larimer St", "Denver", "CO", "80202"), 310, "ground", List.of("Camping trip next week, please ship everything together"), List.of()), false, false, false, false, false));
        s.add(new Sample("R03", mkt("DEMO-MKT-2003", cust("cust-2003", "Isabella Thomas", "isabella@example.com", "standard", 0.5, 1), List.of(l("1", "BOOK-COOK", 2, 35), l("2", "PUZZ-1000", 1, 22)),
            addr("100 Universal City Plaza", "Universal City", "CA", "91608"), 92, "ground"), false, false, false, false, false));
        s.add(new Sample("R04", web("DEMO-WEB-1014", cust("cust-1014", "Benjamin Moore", "ben@example.com", "silver", 0.9, 0), "standard_ship", List.of(l("1", "SKIN-SRM", 1, 58), l("2", "SUN-SPF50", 2, 24)),
            addr("1 Infinite Loop", "Cupertino", "CA", "95014"), 106, "two_day", List.of(), List.of("ship_as_available")), false, false, false, false, false));
        s.add(new Sample("R05", web("DEMO-WEB-1015", cust("cust-1015", "Charlotte Jackson", "charlotte@example.com", "standard", 0.5, 0), "standard_ship", List.of(l("1", "TOY-BLOCK", 1, 45), l("2", "GIFT-CARD", 1, 50)),
            addr("700 Clark Ave", "St. Louis", "MO", "63102"), 95, "ground", List.of("It is a gift for my nephew"), List.of()), false, false, false, false, false));
        s.add(new Sample("R06", web("DEMO-WEB-1016", cust("cust-1016", "Lucas White", "lucas@example.com", "standard", 0.5, 0), "standard_ship", List.of(l("1", "WATCH-GPS", 1, 349)),
            addr("1 Rocket Rd", "Hawthorne", "CA", "90250"), 349, "next_day", List.of(), List.of()), false, false, false, false, true));
        // Ship from store
        s.add(new Sample("F01", of("web", "DEMO-WEB-1017", cust("cust-1017", "Amelia Harris", "amelia@example.com", "gold", 0.5, 0), "ship_from_store", List.of(l("1", "CAP-NVY", 1, 32), l("2", "SOCK-3PK", 1, 36)),
            addr("875 N Michigan Ave", "Chicago", "IL", "60611"), 68, "same_day", List.of(), List.of()), true, false, false, false, false));
        s.add(new Sample("F02", of("web", "DEMO-WEB-1018", cust("cust-1018", "Henry Martin", "henry@example.com", "standard", 0.5, 0), "ship_from_store", List.of(l("1", "TEE-BLK-M", 1, 28)),
            addr("6333 W 3rd St", "Los Angeles", "CA", "90036"), 28, "ground", List.of(), List.of()), false, false, false, false, false));
        // BOPIS
        s.add(new Sample("B01", bopis("DEMO-WEB-1019", cust("cust-1019", "Evelyn Thompson", "evelyn@example.com", "silver", 0.5, 0), List.of(l("1", "HOOD-GRY-M", 1, 68)), "ST-CHI-01", "Chicago", "IL", "60611", 68), false, false, true, false, false));
        s.add(new Sample("B02", bopis("DEMO-WEB-1020", cust("cust-1020", "Alexander Lewis", "alex@example.com", "standard", 0.5, 0), List.of(l("1", "SNK-RUN-10", 1, 145), l("2", "SOCK-3PK", 1, 36)), "ST-LA-02", "Los Angeles", "CA", "90036", 181), false, false, false, false, false));
        s.add(new Sample("B03", bopis("DEMO-WEB-1021", cust("cust-1021", "Harper Walker", "harper@example.com", "gold", 0.5, 0), List.of(l("1", "BAG-TOTE", 2, 45)), "ST-AUS-03", "Austin", "TX", "78758", 90), false, false, false, false, false));
        // POS
        s.add(new Sample("P01", of("pos", "DEMO-POS-3001", cust("cust-3001", "Walk-in", null, "standard", 0.5, 0), "standard_ship", List.of(l("1", "PAN-CI-12", 1, 48)),
            addr("1 Michigan Ave", "Chicago", "IL", "60611"), 48, "ground", List.of(), List.of()), false, false, false, false, false));
        // Compliance hold: age-restricted wine without age verification
        s.add(new Sample("H01", of("web", "DEMO-WEB-1022", new CaptureRequest.CustomerInput("cust-1022", "Daniel Hall", "daniel@example.com", null, "standard", false, null, Map.of("split_acceptance_rate", 0.5)),
            "standard_ship", List.of(l("1", "WINE-RED", 2, 42)), addr("5 Columbus Cir", "New York", "NY", "10019"), 84, "ground", List.of(), List.of()), false, false, false, false, false));
        // Below margin floor: heavy, cheap, far from stock
        s.add(new Sample("M01", of("web", "DEMO-WEB-1023", cust("cust-1023", "Grace Allen", "grace@example.com", "standard", 0.5, 0), "standard_ship", List.of(l("1", "DUMB-10KG", 1, 70)),
            addr("1001 4th Ave", "Seattle", "WA", "98154"), 70, "ground", List.of(), List.of()), false, false, false, false, false));
        s.add(new Sample("M02", of("marketplace", "DEMO-MKT-2004", cust("cust-2004", "Owen Young", "owen@example.com", "standard", 0.5, 0), "standard_ship", List.of(l("1", "PAN-CI-12", 1, 48)),
            addr("1 Aloha Tower Dr", "Honolulu", "HI", "96813"), 48, "ground", List.of(), List.of()), false, false, false, false, false));
        // Declined card
        s.add(new Sample("X01", new CaptureRequest("web", "DEMO-WEB-1024", "standard_ship", "cust-1024", cust("cust-1024", "Zoe King", "zoe@example.com", "standard", 0.5, 0),
            List.of(l("1", "BOOT-LTH-9", 1, 220)), List.of(addr("200 S Biscayne Blvd", "Miami", "FL", "33131")), List.of(),
            List.of(new CaptureRequest.TenderInput("card", "tok_visa_decline", Money.usd("250.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null), false, false, false, false, false));

        // --- Agentic Control Tower demo orders (AG01-AG04): see the class Javadoc for why each is genuinely stuck.
        Instant now = Instant.now();
        // AG01: payment declined, retries remaining (payment_retry_needed, L0 rule). ReauthorizeAction only touches
        // open authorisations, not a payment that already failed outright, so the autonomous attempt finds nothing
        // to reauthorise and honestly escalates instead of pretending to fix it.
        s.add(new Sample("AG01", new CaptureRequest("web", "DEMO-WEB-1025", "standard_ship", "cust-1025",
            cust("cust-1025", "Noah Castillo", "noah.castillo@example.com", "standard", 0.5, 0),
            List.of(l("1", "BOOT-LTH-9", 1, 220)), List.of(addr("200 S Biscayne Blvd", "Miami", "FL", "33131")), List.of(),
            List.of(new CaptureRequest.TenderInput("card", "tok_visa_decline", Money.usd("242.00"), Map.of())), List.of(), List.of(), "ground",
            now.minus(Duration.ofDays(2)), "USD", Map.of(), null), false, false, false, false, false));

        // AG02: shipping address fails verification (address_confirmation_needed, L0 rule). A non-US address is the
        // one deterministic, non-blocking way MockAddressVerifier reports verified=false (an invalid US zip/state
        // combination blocks capture outright, so it cannot be used here).
        s.add(new Sample("AG02", new CaptureRequest("web", "DEMO-WEB-1026", "standard_ship", "cust-1026",
            cust("cust-1026", "Avery Tremblay", "avery.tremblay@example.com", "standard", 0.5, 0),
            List.of(l("1", "HOOD-GRY-M", 1, 68)),
            List.of(new CaptureRequest.AddressInput("ship_to", "Avery Tremblay", "77 Bloor St W", null, "Toronto", "ON", "M5S 1M2", "CA", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_visa_ok", Money.usd("74.80"), Map.of())), List.of(), List.of(), "ground",
            now.minus(Duration.ofDays(3)), "USD", Map.of(), null), false, false, false, false, false));

        // AG03: BOPIS pinned to ST-AUS-03 (backlog 40, reject 0.12 in nodes.yaml) with safe stock at that store
        // (node_capacity_backlog, no inventory confound — a clean single-cause case).
        s.add(new Sample("AG03", bopisAt("DEMO-WEB-1027", cust("cust-1027", "Logan Pierce", "logan.pierce@example.com", "standard", 0.5, 0),
            List.of(l("1", "MUG-CER", 1, 18), l("2", "CAP-NVY", 1, 32)), "ST-AUS-03", "Austin", "TX", "78758", 50, now.minus(Duration.ofDays(2))),
            false, false, false, false, false));

        // AG04: the same backed-up store, but also short on CNDL-SOY against the generated demo stock there — two
        // real, competing signals (inventory short and node capacity) at once with no L0 rule covering the overlap,
        // so the decision genuinely has to weigh them (the "ambiguous" demo case).
        s.add(new Sample("AG04", bopisAt("DEMO-WEB-1028", cust("cust-1028", "Harper Delgado", "harper.delgado@example.com", "standard", 0.5, 0),
            List.of(l("1", "CNDL-SOY", 8, 34)), "ST-AUS-03", "Austin", "TX", "78758", 272, now.minus(Duration.ofDays(2))),
            false, false, false, false, false));
        return s;
    }

    static CaptureRequest web(String channelOrderId, CaptureRequest.CustomerInput c, String type, List<CaptureRequest.LineInput> lines, CaptureRequest.AddressInput to, double total, String method,
                              List<String> notes, List<String> choices) {
        return of("web", channelOrderId, c, type, lines, to, total, method, notes, choices);
    }

    static CaptureRequest mkt(String channelOrderId, CaptureRequest.CustomerInput c, List<CaptureRequest.LineInput> lines, CaptureRequest.AddressInput to, double total, String method) {
        return of("marketplace", channelOrderId, c, "standard_ship", lines, to, total, method, List.of(), List.of());
    }

    static CaptureRequest of(String channel, String channelOrderId, CaptureRequest.CustomerInput c, String type, List<CaptureRequest.LineInput> lines, CaptureRequest.AddressInput to, double total,
                             String method, List<String> notes, List<String> choices) {
        List<CaptureRequest.NoteInput> ns = notes.stream().map(t -> new CaptureRequest.NoteInput("customer", c.email(), t, false)).toList();
        return new CaptureRequest(channel, channelOrderId, type, c.ref(), c, lines, List.of(to), List.of(),
            List.of(new CaptureRequest.TenderInput("card", "tok_visa_ok", Money.usd(String.format(java.util.Locale.ROOT, "%.2f", total * 1.10)), Map.of())), ns, choices, method, null, "USD", Map.of(), null);
    }

    static CaptureRequest bopis(String channelOrderId, CaptureRequest.CustomerInput c, List<CaptureRequest.LineInput> lines, String storeNode, String city, String state, String zip, double total) {
        return bopisAt(channelOrderId, c, lines, storeNode, city, state, zip, total, null);
    }

    static CaptureRequest bopisAt(String channelOrderId, CaptureRequest.CustomerInput c, List<CaptureRequest.LineInput> lines, String storeNode, String city, String state, String zip,
                                  double total, java.time.Instant placedAt) {
        CaptureRequest.AddressInput pickup = new CaptureRequest.AddressInput("pickup", c.name(), storeNode, null, city, state, zip, "US", null, "commercial");
        return new CaptureRequest("web", channelOrderId, "bopis", c.ref(), c, lines, List.of(pickup, new CaptureRequest.AddressInput("ship_to", c.name(), "Store pickup " + storeNode, null, city, state, zip, "US", null, "commercial")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_visa_ok", Money.usd(String.format(java.util.Locale.ROOT, "%.2f", total * 1.10)), Map.of())), List.of(), List.of("pickup_consolidated"),
            "pickup", placedAt, "USD", Map.of("pickupNodeId", storeNode), null);
    }

    static CaptureRequest.LineInput l(String id, String sku, int qty, double price) { return new CaptureRequest.LineInput(id, sku, null, qty, Money.usd(String.format(java.util.Locale.ROOT, "%.2f", price)), null, null, null); }

    static CaptureRequest.AddressInput addr(String line1, String city, String state, String zip) { return new CaptureRequest.AddressInput("ship_to", null, line1, null, city, state, zip, "US", null, "residential"); }

    static CaptureRequest.CustomerInput cust(String ref, String name, String email, String tier, double splitAcceptance, int returns12m) {
        return new CaptureRequest.CustomerInput(ref, name, email, null, tier, true, 34, Map.of("split_acceptance_rate", splitAcceptance, "returns_12m", returns12m, "orders_12m", 3 + returns12m));
    }
}
