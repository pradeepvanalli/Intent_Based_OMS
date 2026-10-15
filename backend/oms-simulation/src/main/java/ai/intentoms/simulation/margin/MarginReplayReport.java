package ai.intentoms.simulation.margin;

import ai.intentoms.simulation.margin.ReplayPlanner.Arm;
import ai.intentoms.simulation.margin.ReplayPlanner.Result;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulates a Margin Replay into the {@code job.results} document: a {@code summary}, {@code perOrder} rows (same shape as
 * {@code OrderSimulation.sampleRow} plus intent and flags), a {@code byRegion} roll-up, deterministic {@code topFindings},
 * the {@code assumptions} the numbers rest on and a paste-ready {@code markdownSummary}. All money is USD, rounded to cents.
 */
public final class MarginReplayReport {
    /** State roll-up. */
    public static final class Region {
        final String state, region;
        int orders, changed, baselineSplits, intentSplits;
        double baselineCost, intentCost, baselineShipping, intentShipping, baselineMargin, intentMargin;
        Region(String state) { this.state = state; this.region = ReplayFindings.region(state); }
        double saved() { return intentMargin - baselineMargin; }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("state", state);
            m.put("region", region);
            m.put("orders", orders);
            m.put("changed", changed);
            m.put("baselineCost", r2(baselineCost));
            m.put("intentCost", r2(intentCost));
            m.put("baselineShipping", r2(baselineShipping));
            m.put("intentShipping", r2(intentShipping));
            m.put("shippingSaved", r2(baselineShipping - intentShipping));
            m.put("saved", r2(saved()));
            m.put("baselineSplits", baselineSplits);
            m.put("intentSplits", intentSplits);
            return m;
        }
    }

    /** Orders whose node set moved from one set of nodes to another (the raw material of the findings). */
    public static final class Shift {
        final String from, to, region;
        int orders;
        double saved, shippingSaved;
        Shift(String from, String to, String region) { this.from = from; this.to = to; this.region = region; }
    }

    final MarginReplayOptions options;
    final List<Map<String, Object>> rows = new ArrayList<>();
    final Map<String, Region> regions = new TreeMap<>();
    final Map<String, Shift> shifts = new LinkedHashMap<>();
    final Map<String, Integer> skippedReasons = new LinkedHashMap<>();
    final Map<String, Integer> intents = new LinkedHashMap<>();
    final Map<String, Integer> intentBands = new LinkedHashMap<>();
    final List<String> warnings = new ArrayList<>();
    int ordersInFile, parsed, planned, skipped, changed, baselineSplits, intentSplits, splitsAvoided, splitsAdded, belowFloor, baselineBelowFloor, unknownSkuLines, ordersWithUnknownSku,
        poBoxOrders, notedOrders, intentOrders, storeToDc, hazmatOrders, units, lineCount, pickupOrders, nodesShifted;
    double baselineShipping, intentShipping, baselineCost, intentCost, baselineMargin, intentMargin, belowFloorMargin, revenue, netRevenue, onTimeBefore, onTimeAfter, decisionCost;
    Instant dateFrom, dateTo;

    public MarginReplayReport(MarginReplayOptions options) { this.options = options; }

    public void fileSkipped(OrderCsvParser.Skipped s) { ordersInFile++; skip(s.orderId(), s.reason(), s.row()); }

    public void skip(String orderId, String reason, int row) {
        skipped++;
        skippedReasons.merge(reason, 1, Integer::sum);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", orderId);
        m.put("skipped", true);
        m.put("error", reason);
        if (row > 0) m.put("row", row);
        rows.add(m);
    }

    public void add(Result r) {
        ImportedOrder o = r.order();
        if (o.placedAt() != null) {
            if (dateFrom == null || o.placedAt().isBefore(dateFrom)) dateFrom = o.placedAt();
            if (dateTo == null || o.placedAt().isAfter(dateTo)) dateTo = o.placedAt();
        }
        if (!r.planned()) { skip(o.orderId(), r.error() == null ? "no plan" : r.error(), o.sourceRow()); return; }
        Arm b = r.baseline(), i = r.intentArm();
        planned++;
        units += r.units();
        lineCount += r.lines();
        revenue += r.revenue();
        netRevenue += i.pnl().netRevenue().doubleValue();
        baselineShipping += b.shipping();
        intentShipping += i.shipping();
        baselineCost += b.fulfilmentCost();
        intentCost += i.fulfilmentCost();
        baselineMargin += b.marginUsd();
        intentMargin += i.marginUsd();
        onTimeBefore += b.onTime();
        onTimeAfter += i.onTime();
        if (b.split()) baselineSplits++;
        if (i.split()) intentSplits++;
        if (b.packages() > i.packages()) splitsAvoided++;
        if (i.packages() > b.packages()) splitsAdded++;
        if (!i.meetsFloor()) { belowFloor++; belowFloorMargin += b.marginUsd(); }
        if (!b.meetsFloor()) baselineBelowFloor++;
        if (r.unknownSkus() > 0) { unknownSkuLines += r.unknownSkus(); ordersWithUnknownSku++; }
        if (r.poBox()) poBoxOrders++;
        if (r.hazmat()) hazmatOrders++;
        if (r.req().isPickup()) pickupOrders++;
        if (o.hasNote()) notedOrders++;
        boolean changed = r.changed();
        if (changed) this.changed++;
        if (!b.nodeIds().equals(i.nodeIds())) {
            nodesShifted++;
            boolean fromStore = b.plan().packages().stream().anyMatch(p -> p.nodeId().startsWith("ST-"));
            boolean toDc = i.plan().packages().stream().noneMatch(p -> p.nodeId().startsWith("ST-"));
            if (fromStore && toDc) storeToDc++;
        }
        var intent = r.intent();
        if (intent.any()) {
            intentOrders++;
            intents.merge(intent.label(), 1, Integer::sum);
            if (intent.gift() && !"gift".equals(intent.label())) intents.merge("gift", 1, Integer::sum);
        }
        if (o.hasNote()) intentBands.merge(intent.band() != null ? intent.band() : "decision".equals(intent.source()) ? "NONE" : intent.source().toUpperCase(), 1, Integer::sum);

        String state = r.geo().state() == null ? "??" : r.geo().state();
        Region reg = regions.computeIfAbsent(state, Region::new);
        reg.orders++;
        if (changed) reg.changed++;
        reg.baselineCost += b.fulfilmentCost();
        reg.intentCost += i.fulfilmentCost();
        reg.baselineShipping += b.shipping();
        reg.intentShipping += i.shipping();
        reg.baselineMargin += b.marginUsd();
        reg.intentMargin += i.marginUsd();
        if (b.split()) reg.baselineSplits++;
        if (i.split()) reg.intentSplits++;
        if (!b.nodeIds().equals(i.nodeIds())) {
            String key = String.join("+", b.nodeIds()) + "→" + String.join("+", i.nodeIds()) + "@" + reg.region;
            Shift s = shifts.computeIfAbsent(key, k -> new Shift(String.join("+", b.nodeIds()), String.join("+", i.nodeIds()), reg.region));
            s.orders++;
            s.saved += i.marginUsd() - b.marginUsd();
            s.shippingSaved += b.shipping() - i.shipping();
        }
        rows.add(row(r, state, reg.region, changed));
    }

    static Map<String, Object> row(Result r, String state, String region, boolean changed) {
        ImportedOrder o = r.order();
        Arm b = r.baseline(), i = r.intentArm();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.orderId());
        m.put("placedAt", o.placedAt() == null ? null : o.placedAt().toString());
        m.put("city", r.geo().city());
        m.put("state", state);
        m.put("region", region);
        m.put("geoPrecision", r.geo().precision());
        m.put("lines", r.lines());
        m.put("units", r.units());
        m.put("revenue", r2(r.revenue()));
        m.put("shipMethod", r.req().shipMethod());
        m.put("costBefore", b.fulfilmentCost());
        m.put("costAfter", i.fulfilmentCost());
        m.put("shippingBefore", b.shipping());
        m.put("shippingAfter", i.shipping());
        m.put("packagesBefore", b.packages());
        m.put("packagesAfter", i.packages());
        m.put("nodesBefore", b.nodeIds());
        m.put("nodesAfter", i.nodeIds());
        m.put("onTimeBefore", b.onTime());
        m.put("onTimeAfter", i.onTime());
        m.put("marginBefore", r4(b.marginRatio()));
        m.put("marginAfter", r4(i.marginRatio()));
        m.put("marginBeforeUsd", r2(b.marginUsd()));
        m.put("marginAfterUsd", r2(i.marginUsd()));
        m.put("marginDeltaUsd", r2(i.marginUsd() - b.marginUsd()));
        m.put("flippedDecisions", List.of());
        m.put("blockedTransitions", 0);
        m.put("policy", i.policy());
        m.put("intent", r.intent().label());
        m.put("intentPolicy", r.intent().policy());
        m.put("intentSource", r.intent().source());
        m.put("intentBand", r.intent().band());
        m.put("intentRecordId", r.intent().recordId());
        m.put("isGift", r.intent().gift());
        m.put("urgent", r.intent().urgent());
        m.put("splitOk", r.intent().splitOk());
        m.put("belowFloor", !i.meetsFloor());
        m.put("baselineBelowFloor", !b.meetsFloor());
        m.put("poBox", r.poBox());
        m.put("hazmat", r.hazmat());
        m.put("unknownSkus", r.unknownSkus());
        m.put("changed", changed);
        m.put("reason", i.reason());
        m.put("baselineReason", b.reason());
        if (o.hasNote()) m.put("note", o.note().length() > 200 ? o.note().substring(0, 200) + "…" : o.note());
        return m;
    }

    // ---- output -----------------------------------------------------------------------------------------------------

    public int days() {
        if (dateFrom == null || dateTo == null) return 1;
        long d = Duration.between(dateFrom, dateTo).toDays() + 1;
        return (int) Math.max(1, d);
    }

    public Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("orders", ordersInFile);
        s.put("parsed", parsed);
        s.put("planned", planned);
        s.put("skipped", skipped);
        s.put("skippedReasons", new LinkedHashMap<>(skippedReasons));
        s.put("ordersChanged", changed);
        s.put("nodesShifted", nodesShifted);
        s.put("storeToDc", storeToDc);
        s.put("revenue", r2(revenue));
        s.put("units", units);
        s.put("baselineShippingCost", r2(baselineShipping));
        s.put("intentShippingCost", r2(intentShipping));
        s.put("shippingSaved", r2(baselineShipping - intentShipping));
        s.put("baselineFulfilmentCost", r2(baselineCost));
        s.put("intentFulfilmentCost", r2(intentCost));
        s.put("fulfilmentSaved", r2(baselineCost - intentCost));
        s.put("baselineMarginUsd", r2(baselineMargin));
        s.put("intentMarginUsd", r2(intentMargin));
        s.put("marginRecoveredUsd", r2(intentMargin - baselineMargin));
        s.put("marginRecoveredPerOrder", planned == 0 ? 0.0 : r2((intentMargin - baselineMargin) / planned));
        s.put("baselineMarginRatio", netRevenue <= 0 ? 0.0 : r4(baselineMargin / netRevenue));
        s.put("intentMarginRatio", netRevenue <= 0 ? 0.0 : r4(intentMargin / netRevenue));
        s.put("baselineSplits", baselineSplits);
        s.put("intentSplits", intentSplits);
        s.put("splitsAvoided", splitsAvoided);
        s.put("splitsAdded", splitsAdded);
        s.put("ordersBelowFloorFlagged", belowFloor);
        s.put("baselineOrdersBelowFloor", baselineBelowFloor);
        s.put("belowFloorMarginUsd", r2(belowFloorMargin));
        s.put("intentsDetected", new LinkedHashMap<>(intents));
        s.put("intentsByBand", new LinkedHashMap<>(intentBands));
        s.put("ordersWithIntent", intentOrders);
        s.put("ordersWithNotes", notedOrders);
        s.put("avgOnTimeBefore", planned == 0 ? 0.0 : r4(onTimeBefore / planned));
        s.put("avgOnTimeAfter", planned == 0 ? 0.0 : r4(onTimeAfter / planned));
        s.put("unknownSkuLines", unknownSkuLines);
        s.put("ordersWithUnknownSku", ordersWithUnknownSku);
        s.put("poBoxOrders", poBoxOrders);
        s.put("hazmatOrders", hazmatOrders);
        s.put("pickupOrders", pickupOrders);
        s.put("dateFrom", dateFrom == null ? null : dateFrom.toString());
        s.put("dateTo", dateTo == null ? null : dateTo.toString());
        int days = days();
        s.put("annualisationDays", days);
        double recovered = intentMargin - baselineMargin;
        s.put("estimatedAnnualisedSavings", r2(recovered * 365.0 / days));
        s.put("estimatedAnnualisedOrders", Math.round(planned * 365.0 / days));
        s.put("estimateLabel", "Estimate: scales the replayed window (" + days + " day" + (days == 1 ? "" : "s") + ") to 365 days; actual results depend on real inventory, carriers and demand mix.");
        return s;
    }

    public List<Map<String, Object>> byRegion() {
        return regions.values().stream().sorted(Comparator.comparingDouble(Region::saved).reversed().thenComparing(r -> r.state)).map(Region::toMap).toList();
    }

    public List<String> assumptions() {
        List<String> a = new ArrayList<>();
        a.add(options.assumeStock()
            ? "Every active node is assumed to hold stock of every SKU (assumeStock=true), so the comparison isolates network geometry, node economics and policy; real ATP would change individual routings."
            : "Coverage comes from the tenant's current ATP (assumeStock=false); orders with no stock anywhere were skipped and historic stock positions are not reconstructed.");
        a.add(options.singleDc() ? "Baseline = every order ships complete from " + options.singleDcNode() + " (single-DC); overflow lines fall back to the nearest capable node."
            : "Baseline = the conventional OMS: ship-as-available from the nearest capable node by great-circle distance, no margin floor, no split penalty in the choice, no intent handling.");
        a.add("Intent OMS = the tenant's live sourcing policy (rate card, node cost profiles, split penalty, margin floor, plan weights) with the fulfilment intent read from order notes; the node-health soft score is neutral in both arms.");
        a.add(options.useDecisions() ? "Intents come from the tenant's fulfilment_intent decision run in SIMULATION mode (no human tasks, no caching, records flagged SIMULATION) on orders with notes; a confirm-band recommendation is applied in the replay, where live it would follow the tenant's confirm policy. Keyword rules are the fallback."
            : "Intents come from deterministic keyword rules over order notes (useDecisions=false).");
        a.add(String.format(java.util.Locale.ROOT, "SKUs missing from the catalogue use COGS = %.0f%% of the selling price and 0.5 kg; catalogue SKUs use their real COGS, weight and flags.", options.unknownSkuCogsRatio() * 100));
        a.add("Both arms are costed identically: carrier rate card by distance zone and weight band, node labour/packaging/handling, landed cost for cross-border nodes, and the tenant's split penalty per extra package. Margin = contribution margin after payment fees and returns reserve.");
        a.add("Destinations are geocoded to city centres (largest ~" + UsGeocoder.cityCount() + " US cities), else ZIP3 or state centroid; non-US orders are skipped. Each order's own timestamp is 'now' for cutoffs and ETAs; daily capacity and historic penalties are not replayed.");
        a.add("Nothing is persisted as an order, plan, reservation or task: only this job record (and SIMULATION-mode decision records when decisions are on).");
        return a;
    }

    public Map<String, Object> results(String fileName, long bytes, OrderCsvParser.Format format, int rowsInFile, boolean partial, int processed, long durationMs, int rowCap) {
        Map<String, Object> summary = summary();
        List<String> findings = ReplayFindings.write(this);
        List<Map<String, Object>> regionRows = byRegion();
        List<String> assumptions = assumptions();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", MarginReplayService.KIND);
        r.put("options", options.toMap());
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("name", fileName);
        file.put("bytes", bytes);
        file.put("format", format == null ? null : format.name().toLowerCase());
        file.put("rows", rowsInFile);
        file.put("orders", ordersInFile);
        file.put("warnings", List.copyOf(warnings));
        r.put("file", file);
        r.put("summary", summary);
        r.put("topFindings", findings);
        r.put("byRegion", regionRows);
        r.put("assumptions", assumptions);
        r.put("perOrder", rowCap > 0 && rows.size() > rowCap ? new ArrayList<>(rows.subList(0, rowCap)) : new ArrayList<>(rows));
        r.put("perOrderTruncated", rowCap > 0 && rows.size() > rowCap);
        r.put("markdownSummary", ReplayFindings.markdown(fileName, summary, findings, regionRows, assumptions, partial));
        r.put("processed", processed);
        r.put("partial", partial);
        r.put("durationMs", durationMs);
        // Flat fields the generic Simulation page reads so an import job renders there too.
        r.put("orders", planned);
        r.put("costPerOrder", planned == 0 ? 0.0 : r2(intentCost / planned));
        r.put("baselineCostPerOrder", planned == 0 ? 0.0 : r2(baselineCost / planned));
        r.put("splitRate", rate(intentSplits, planned));
        r.put("baselineSplitRate", rate(baselineSplits, planned));
        r.put("onTimeRate", summary.get("avgOnTimeAfter"));
        r.put("baselineOnTimeRate", summary.get("avgOnTimeBefore"));
        r.put("marginRatio", summary.get("intentMarginRatio"));
        r.put("baselineMarginRatio", summary.get("baselineMarginRatio"));
        r.put("touchlessRate", rate(planned - belowFloor, planned));
        r.put("baselineTouchlessRate", planned == 0 ? 0.0 : 1.0);
        r.put("escalations", belowFloor);
        r.put("baselineEscalations", 0);
        return r;
    }

    static double rate(int part, int whole) { return whole == 0 ? 0.0 : r4(part / (double) whole); }
    static double r2(double v) { return Math.round(v * 100) / 100d; }
    static double r4(double v) { return Math.round(v * 10_000) / 10_000d; }
}
