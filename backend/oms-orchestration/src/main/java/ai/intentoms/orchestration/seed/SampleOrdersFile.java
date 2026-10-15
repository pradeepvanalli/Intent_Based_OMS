package ai.intentoms.orchestration.seed;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.platform.json.Json;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code config/tenants/<tenant>/sample-orders.yaml} (docs/SAMPLE_ORDERS_FORMAT.md): the data-driven sample orders,
 * returns and exceptions the {@link DemoSeeder} captures for a tenant. Parsed with Jackson YAML into records, validated
 * (every error names the order key and the field), then converted into the same {@link DemoOrders.Sample} structures the
 * Java demo set uses so one seeding loop serves both. Pure: no Spring, no database; the seeder supplies the pickup-store
 * addresses and the catalogue / node / order-type / exception-type vocabularies for {@link #crossCheck}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SampleOrdersFile(List<OrderSpec> orders, List<ReturnSpec> returns, List<ExceptionSpec> exceptions) {
    public static final String FILE_NAME = "sample-orders.yaml";
    static final Set<String> EXCEPTION_SOURCES = Set.of("cs", "carrier", "wms", "payment", "system");
    static final Set<String> ADDRESS_KINDS = Set.of("residential", "commercial", "po_box", "freight_forwarder");
    static final BigDecimal DEFAULT_TENDER_RATIO = new BigDecimal("1.10");

    public SampleOrdersFile {
        orders = orders == null ? List.of() : List.copyOf(orders);
        returns = returns == null ? List.of() : List.copyOf(returns);
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
    }

    /** Thrown for a file that does not follow the format; the message lists every problem as {@code order <key>: <field> …}. */
    public static final class SampleOrdersException extends IllegalArgumentException {
        private final List<String> problems;
        public SampleOrdersException(List<String> problems) { super(FILE_NAME + ": " + String.join("; ", problems)); this.problems = List.copyOf(problems); }
        public List<String> problems() { return problems; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderSpec(String key, String channel, String channelOrderId, String orderType, String shipMethod, Instant placedAt, CustomerSpec customer,
                            List<LineSpec> lines, AddressSpec shipTo, String pickupNodeId, List<String> notes, List<String> intentChoices, TenderSpec tender,
                            Map<String, Object> extensions, ProvenanceSpec provenance, LifecycleSpec lifecycle) {
        public OrderSpec {
            lines = lines == null ? List.of() : List.copyOf(lines);
            notes = notes == null ? List.of() : List.copyOf(notes);
            intentChoices = intentChoices == null ? null : List.copyOf(intentChoices);
            extensions = extensions == null ? Map.of() : new LinkedHashMap<>(extensions);
            lifecycle = lifecycle == null ? LifecycleSpec.NONE : lifecycle;
        }
        public String effectiveOrderType() { return orderType == null || orderType.isBlank() ? CaptureRequest.DEFAULT_ORDER_TYPE : orderType.trim(); }
        public boolean isPickup() { return pickupNodeId != null && !pickupNodeId.isBlank(); }
        public String effectiveShipMethod() { return shipMethod == null || shipMethod.isBlank() ? (isPickup() ? "pickup" : "ground") : shipMethod.trim(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomerSpec(String ref, String name, String email, String phone, String tier, Boolean loyaltyVerified, Integer age, Map<String, Object> attributes) {
        public CustomerSpec { attributes = attributes == null ? Map.of() : new LinkedHashMap<>(attributes); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineSpec(String id, String sku, Integer qty, BigDecimal unitPrice, BigDecimal discount, Map<String, Object> attributes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddressSpec(String name, String line1, String line2, String city, String state, String zip, String country, String kind) {
        public String effectiveCountry() { return country == null || country.isBlank() ? "US" : country.trim().toUpperCase(Locale.ROOT); }
        public String effectiveKind() { return kind == null || kind.isBlank() ? "residential" : kind.trim(); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TenderSpec(String type, String token, BigDecimal amount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProvenanceSpec(String agentId, String mandateId, String protocol, BigDecimal spendLimit, String instructionText) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LifecycleSpec(Boolean ship, Boolean partialShip, Boolean deliver, Integer deliveredDaysAgo, Boolean bopisPickup, Boolean storeReject) {
        static final LifecycleSpec NONE = new LifecycleSpec(null, null, null, null, null, null);
        public boolean ships() { return Boolean.TRUE.equals(ship) || Boolean.TRUE.equals(partialShip) || delivers(); }
        public boolean delivers() { return Boolean.TRUE.equals(deliver) || (deliveredDaysAgo != null && deliveredDaysAgo > 0); }
        public boolean partial() { return Boolean.TRUE.equals(partialShip); }
        public boolean pickup() { return Boolean.TRUE.equals(bopisPickup); }
        public boolean rejects() { return Boolean.TRUE.equals(storeReject); }
        public int daysAgo() { return deliveredDaysAgo == null ? 0 : Math.max(0, deliveredDaysAgo); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReturnSpec(String order, String lineId, Integer qty, String reason, String text, Boolean receiptPresent, Integer backdateDeliveryDays) {
        public boolean receipt() { return receiptPresent == null || receiptPresent; }
        public int backdateDays() { return backdateDeliveryDays == null ? 0 : Math.max(0, backdateDeliveryDays); }
        public int quantity() { return qty == null ? 1 : qty; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExceptionSpec(String order, String source, String type, String code, String text, String playbook) {
        /** Raw signal code recorded on the exception; defaults to the taxonomy type. */
        public String effectiveCode() { return code == null || code.isBlank() ? type : code; }
    }

    /** What the seeder drives: capture requests with lifecycle flags, then returns and exceptions keyed by order key. */
    public record SampleSet(List<DemoOrders.Sample> orders, List<ReturnSpec> returns, List<ExceptionSpec> exceptions) {
        public SampleSet {
            orders = orders == null ? List.of() : List.copyOf(orders);
            returns = returns == null ? List.of() : List.copyOf(returns);
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        }
    }

    // ---- loading ------------------------------------------------------------------------------------------------

    public static Optional<Path> locate(Path tenantDir) {
        Path f = tenantDir.resolve(FILE_NAME);
        return Files.isRegularFile(f) ? Optional.of(f) : Optional.empty();
    }

    public static SampleOrdersFile load(Path file) {
        try { return parse(Files.readString(file)); }
        catch (IOException e) { throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e); }
    }

    /** Parses and validates; throws {@link SampleOrdersException} naming every offending order key and field. */
    public static SampleOrdersFile parse(String yaml) {
        SampleOrdersFile f;
        if (yaml == null || yaml.isBlank()) return new SampleOrdersFile(List.of(), List.of(), List.of());
        try { f = Json.YAML.readValue(yaml, SampleOrdersFile.class); }
        catch (IOException e) { throw new SampleOrdersException(List.of("cannot parse: " + (e instanceof com.fasterxml.jackson.core.JsonProcessingException j ? j.getOriginalMessage() : e.getMessage()))); }
        if (f == null) f = new SampleOrdersFile(List.of(), List.of(), List.of());
        List<String> problems = f.validate();
        if (!problems.isEmpty()) throw new SampleOrdersException(problems);
        return f;
    }

    // ---- validation ------------------------------------------------------------------------------------------------

    /** Structural checks (required fields, uniqueness, references between sections). Empty when valid. */
    public List<String> validate() {
        List<String> p = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        Set<String> channelIds = new LinkedHashSet<>();
        Map<String, OrderSpec> byKey = new LinkedHashMap<>();
        for (int i = 0; i < orders.size(); i++) {
            OrderSpec o = orders.get(i);
            String key = blank(o.key()) ? "orders[" + i + "]" : o.key();
            String at = "order " + key + ": ";
            if (blank(o.key())) p.add(at + "key is required");
            else if (!keys.add(o.key())) p.add(at + "key is duplicated");
            else byKey.put(o.key(), o);
            if (blank(o.channel())) p.add(at + "channel is required");
            if (blank(o.channelOrderId())) p.add(at + "channelOrderId is required");
            else if (!channelIds.add(o.channelOrderId())) p.add(at + "channelOrderId '" + o.channelOrderId() + "' is duplicated");
            if (o.customer() == null) p.add(at + "customer is required");
            else if (blank(o.customer().ref())) p.add(at + "customer.ref is required");
            if (o.lines().isEmpty()) p.add(at + "lines must contain at least one line");
            Set<String> lineIds = new LinkedHashSet<>();
            for (int j = 0; j < o.lines().size(); j++) {
                LineSpec l = o.lines().get(j);
                String lf = "lines[" + j + "].";
                if (l == null) { p.add(at + lf.substring(0, lf.length() - 1) + " is empty"); continue; }
                if (blank(l.id())) p.add(at + lf + "id is required");
                else if (!lineIds.add(l.id())) p.add(at + lf + "id '" + l.id() + "' is duplicated");
                if (blank(l.sku())) p.add(at + lf + "sku is required");
                if (l.qty() == null || l.qty() <= 0) p.add(at + lf + "qty must be a positive integer");
                if (l.unitPrice() == null || l.unitPrice().signum() < 0) p.add(at + lf + "unitPrice is required and must be >= 0");
                if (l.discount() != null && l.discount().signum() < 0) p.add(at + lf + "discount must be >= 0");
            }
            if (o.shipTo() == null && !o.isPickup()) p.add(at + "shipTo is required unless pickupNodeId is set");
            if (o.shipTo() != null) {
                AddressSpec a = o.shipTo();
                if (blank(a.line1())) p.add(at + "shipTo.line1 is required");
                if (blank(a.city())) p.add(at + "shipTo.city is required");
                if (blank(a.state())) p.add(at + "shipTo.state is required");
                if (blank(a.zip())) p.add(at + "shipTo.zip is required");
                if (!ADDRESS_KINDS.contains(a.effectiveKind())) p.add(at + "shipTo.kind '" + a.kind() + "' must be one of " + ADDRESS_KINDS);
            }
            if (o.tender() != null) {
                // Card-type tenders (the default) need a real or mock token; deferred/invoiced tenders (net_terms and
                // similar) carry no token by design and the builder leaves it blank rather than inventing a fake one.
                String tenderType = blank(o.tender().type()) ? "card" : o.tender().type();
                if ("card".equals(tenderType) && blank(o.tender().token())) p.add(at + "tender.token is required for a card tender");
                if (o.tender().amount() != null && o.tender().amount().signum() <= 0) p.add(at + "tender.amount must be > 0");
            }
            if (o.provenance() != null && blank(o.provenance().agentId())) p.add(at + "provenance.agentId is required when provenance is set");
            // AgentProvenanceStep blocks capture without it (the buyer's instruction is the evidence an agent order
            // was authorised); catching it here names the order instead of failing silently inside seedOrders().
            if (o.provenance() != null && blank(o.provenance().instructionText())) p.add(at + "provenance.instructionText is required when provenance is set (the buyer's instruction, kept as evidence)");
            if (o.lifecycle().pickup() && !o.isPickup()) p.add(at + "lifecycle.bopisPickup needs pickupNodeId");
            if (o.lifecycle().deliveredDaysAgo != null && o.lifecycle().deliveredDaysAgo < 0) p.add(at + "lifecycle.deliveredDaysAgo must be >= 0");
            if (o.placedAt() != null && o.placedAt().isAfter(Instant.now().plusSeconds(60))) p.add(at + "placedAt must not be in the future");
        }
        for (int i = 0; i < returns.size(); i++) {
            ReturnSpec r = returns.get(i);
            String at = "return[" + i + "]" + (blank(r.order()) ? "" : " (order " + r.order() + ")") + ": ";
            if (blank(r.order())) p.add(at + "order is required");
            else if (!byKey.containsKey(r.order())) p.add(at + "order '" + r.order() + "' is not an order key");
            else if (!blank(r.lineId()) && byKey.get(r.order()).lines().stream().noneMatch(l -> l != null && r.lineId().equals(l.id())))
                p.add(at + "lineId '" + r.lineId() + "' is not a line of order " + r.order());
            if (blank(r.lineId())) p.add(at + "lineId is required");
            if (r.qty() != null && r.qty() <= 0) p.add(at + "qty must be a positive integer");
            if (blank(r.reason())) p.add(at + "reason is required");
            if (r.backdateDeliveryDays() != null && r.backdateDeliveryDays() < 0) p.add(at + "backdateDeliveryDays must be >= 0");
        }
        for (int i = 0; i < exceptions.size(); i++) {
            ExceptionSpec e = exceptions.get(i);
            String at = "exception[" + i + "]" + (blank(e.order()) ? "" : " (order " + e.order() + ")") + ": ";
            if (blank(e.order())) p.add(at + "order is required");
            else if (!byKey.containsKey(e.order())) p.add(at + "order '" + e.order() + "' is not an order key");
            if (blank(e.source())) p.add(at + "source is required");
            else if (!EXCEPTION_SOURCES.contains(e.source())) p.add(at + "source '" + e.source() + "' must be one of " + EXCEPTION_SOURCES);
            if (blank(e.type())) p.add(at + "type is required");
            if (blank(e.text())) p.add(at + "text is required");
        }
        return p;
    }

    /**
     * Checks the file against the tenant's vocabularies (catalogue skus, node ids, order types, exception types); any
     * null set is skipped. Empty when consistent.
     */
    public List<String> crossCheck(Set<String> skus, Set<String> nodeIds, Set<String> orderTypes, Set<String> exceptionTypes) {
        List<String> p = new ArrayList<>();
        for (OrderSpec o : orders) {
            String at = "order " + o.key() + ": ";
            if (orderTypes != null && !orderTypes.contains(o.effectiveOrderType())) p.add(at + "orderType '" + o.effectiveOrderType() + "' is not in order-types.yaml");
            if (nodeIds != null && o.isPickup() && !nodeIds.contains(o.pickupNodeId())) p.add(at + "pickupNodeId '" + o.pickupNodeId() + "' is not in nodes.yaml");
            if (skus != null) for (int j = 0; j < o.lines().size(); j++) {
                LineSpec l = o.lines().get(j);
                if (l != null && l.sku() != null && !skus.contains(l.sku())) p.add(at + "lines[" + j + "].sku '" + l.sku() + "' is not in catalog.yaml");
            }
        }
        if (exceptionTypes != null) for (int i = 0; i < exceptions.size(); i++) {
            ExceptionSpec e = exceptions.get(i);
            if (e.type() != null && !exceptionTypes.contains(e.type())) p.add("exception[" + i + "] (order " + e.order() + "): type '" + e.type() + "' is not in exception-taxonomy.yaml");
        }
        return p;
    }

    // ---- conversion ------------------------------------------------------------------------------------------------

    /**
     * Converts to capture requests. {@code pickupAddress} resolves a node id to the store address used for the pickup
     * address of BOPIS orders that carry no {@code shipTo}; unknown nodes are reported as a validation problem.
     */
    public SampleSet toSampleSet(Function<String, Optional<AddressSpec>> pickupAddress) {
        List<DemoOrders.Sample> out = new ArrayList<>(orders.size());
        List<String> problems = new ArrayList<>();
        for (OrderSpec o : orders) {
            try { out.add(toSample(o, pickupAddress)); }
            catch (IllegalArgumentException e) { problems.add("order " + o.key() + ": " + e.getMessage()); }
        }
        if (!problems.isEmpty()) throw new SampleOrdersException(problems);
        return new SampleSet(out, returns, exceptions);
    }

    static DemoOrders.Sample toSample(OrderSpec o, Function<String, Optional<AddressSpec>> pickupAddress) {
        CustomerSpec c = o.customer();
        Map<String, Object> history = new LinkedHashMap<>(c.attributes());
        if (c.loyaltyVerified() != null) history.put("loyalty_verified", c.loyaltyVerified());
        CaptureRequest.CustomerInput customer = new CaptureRequest.CustomerInput(c.ref(), c.name(), c.email(), c.phone(),
            blank(c.tier()) ? "standard" : c.tier(), c.age() != null, c.age(), history);

        List<CaptureRequest.LineInput> lines = new ArrayList<>();
        for (LineSpec l : o.lines())
            lines.add(new CaptureRequest.LineInput(l.id(), l.sku(), null, l.qty(), Money.usd(money(l.unitPrice())), l.discount() == null ? null : Money.usd(money(l.discount())), null, l.attributes()));

        List<CaptureRequest.AddressInput> addresses = new ArrayList<>();
        AddressSpec shipTo = o.shipTo();
        Map<String, Object> extensions = new LinkedHashMap<>(o.extensions());
        List<String> choices = o.intentChoices();
        if (o.isPickup()) {
            AddressSpec store = shipTo != null ? shipTo : pickupAddress.apply(o.pickupNodeId())
                .orElseThrow(() -> new IllegalArgumentException("pickupNodeId '" + o.pickupNodeId() + "' is unknown and no shipTo was given"));
            String name = c.name();
            addresses.add(new CaptureRequest.AddressInput("pickup", name, o.pickupNodeId(), null, store.city(), store.state(), store.zip(), store.effectiveCountry(), c.phone(), "commercial"));
            if (shipTo != null) addresses.add(shipToInput(shipTo, name));
            else addresses.add(new CaptureRequest.AddressInput("ship_to", name, "Store pickup " + o.pickupNodeId(), null, store.city(), store.state(), store.zip(), store.effectiveCountry(), c.phone(), "commercial"));
            extensions.putIfAbsent("pickupNodeId", o.pickupNodeId());
            if (choices == null) choices = List.of("pickup_consolidated");
        } else {
            addresses.add(shipToInput(shipTo, c.name()));
        }
        if (choices == null) choices = List.of();

        BigDecimal merchandise = BigDecimal.ZERO;
        for (LineSpec l : o.lines()) merchandise = merchandise.add(l.unitPrice().multiply(BigDecimal.valueOf(l.qty())).subtract(l.discount() == null ? BigDecimal.ZERO : l.discount()));
        TenderSpec t = o.tender();
        String tenderType = t == null || blank(t.type()) ? "card" : t.type();
        // The mock payment gateway (and DefaultPaymentService) require a non-blank token for every tender type; only
        // a "*_decline"/"*_expired"/"*_refund_fail" suffix is meaningful, so a deferred tender (net_terms etc.)
        // defaults to a plain type-named token rather than inventing a fake card token.
        String token = t != null && !blank(t.token()) ? t.token() : "card".equals(tenderType) ? "tok_visa_ok" : tenderType + "_ok";
        BigDecimal amount = t == null || t.amount() == null ? merchandise.multiply(DEFAULT_TENDER_RATIO) : t.amount();
        List<CaptureRequest.TenderInput> tenders = List.of(new CaptureRequest.TenderInput(tenderType, token, Money.usd(money(amount)), Map.of()));

        List<CaptureRequest.NoteInput> notes = o.notes().stream().filter(n -> !blank(n)).map(n -> new CaptureRequest.NoteInput("customer", c.email(), n, false)).toList();

        CaptureRequest.ProvenanceInput provenance = null;
        if (o.provenance() != null) {
            ProvenanceSpec pv = o.provenance();
            provenance = new CaptureRequest.ProvenanceInput(pv.agentId(), blank(pv.protocol()) ? "mcp" : pv.protocol(), pv.mandateId(),
                pv.spendLimit() == null ? null : Money.usd(money(pv.spendLimit())), pv.instructionText(), null, c.ref(), Map.of());
        }

        CaptureRequest req = new CaptureRequest(o.channel(), o.channelOrderId(), o.effectiveOrderType(), c.ref(), customer, lines, addresses, List.of(), tenders, notes,
            choices, o.effectiveShipMethod(), o.placedAt(), "USD", extensions, provenance);
        LifecycleSpec lc = o.lifecycle();
        return new DemoOrders.Sample(o.key(), req, lc.ships(), lc.delivers(), lc.pickup(), lc.partial(), lc.rejects(), lc.daysAgo());
    }

    private static CaptureRequest.AddressInput shipToInput(AddressSpec a, String fallbackName) {
        return new CaptureRequest.AddressInput("ship_to", blank(a.name()) ? fallbackName : a.name(), a.line1(), a.line2(), a.city(), a.state(), a.zip(), a.effectiveCountry(), null, a.effectiveKind());
    }

    static String money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
