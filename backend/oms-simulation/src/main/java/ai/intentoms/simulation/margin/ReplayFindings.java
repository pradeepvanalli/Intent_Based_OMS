package ai.intentoms.simulation.margin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plain-language findings for a Margin Replay, generated deterministically from the accumulated numbers (no model):
 * the largest node shifts by region, store-to-DC re-routing, margin-floor exposure, detected intents, on-time movement and
 * the annualised estimate. Also renders the paste-ready Markdown summary.
 */
public final class ReplayFindings {
    private static final Set<String> NORTHEAST = Set.of("NY", "NJ", "PA", "CT", "MA", "RI", "VT", "NH", "ME", "MD", "DE", "DC");
    private static final Set<String> SOUTHEAST = Set.of("FL", "GA", "SC", "NC", "TN", "AL", "MS", "KY", "VA", "WV");
    private static final Set<String> MIDWEST = Set.of("IL", "IN", "OH", "MI", "WI", "MN", "IA", "MO", "KS", "NE", "SD", "ND");
    private static final Set<String> SOUTH_CENTRAL = Set.of("TX", "OK", "LA", "AR");
    private static final Set<String> MOUNTAIN = Set.of("NV", "UT", "CO", "AZ", "NM", "MT", "WY", "ID");
    private static final Set<String> PNW = Set.of("WA", "OR");

    private ReplayFindings() {}

    public static String region(String state) {
        if (state == null) return "Unknown";
        String s = state.toUpperCase(Locale.ROOT);
        if (NORTHEAST.contains(s)) return "Northeast";
        if (SOUTHEAST.contains(s)) return "Southeast";
        if (MIDWEST.contains(s)) return "Midwest";
        if (SOUTH_CENTRAL.contains(s)) return "Texas & South Central";
        if (MOUNTAIN.contains(s)) return "Mountain West";
        if (PNW.contains(s)) return "Pacific Northwest";
        if (s.equals("CA")) return "California";
        if (s.equals("AK") || s.equals("HI")) return "Alaska & Hawaii";
        if (s.equals("PR")) return "Puerto Rico";
        return "Other";
    }

    /** Up to five sentences: the headline totals first, then the biggest node shift, intents, margin-floor exposure, store re-routing, a second shift, on-time, data caveats. */
    public static List<String> write(MarginReplayReport r) {
        List<String> out = new ArrayList<>();
        if (r.planned == 0) {
            out.add("No order could be replayed" + (r.skippedReasons.isEmpty() ? "." : ": " + describeSkips(r.skippedReasons) + "."));
            return out;
        }
        double recovered = r.intentMargin - r.baselineMargin;
        int days = r.days();
        out.add(String.format(Locale.ROOT, "%d orders over %d day%s: %s of contribution margin recovered (%s per order; carrier spend %s); at this run rate that is roughly %s a year (estimate).",
            r.planned, days, days == 1 ? "" : "s", usd(recovered), usd(recovered / r.planned), usd(r.baselineShipping - r.intentShipping), usd(recovered * 365.0 / days)));
        List<MarginReplayReport.Shift> shifts = new ArrayList<>(r.shifts.values());
        shifts.sort(Comparator.comparingDouble((MarginReplayReport.Shift s) -> s.saved).reversed());
        List<String> shiftSentences = new ArrayList<>();
        for (MarginReplayReport.Shift s : shifts) {
            if (shiftSentences.size() >= 2 || s.saved < 1.0 || s.orders < 2) break;
            shiftSentences.add(String.format(Locale.ROOT, "%d order%s to %s shipped from %s; routing %s from %s recovers %s%s.",
                s.orders, s.orders == 1 ? "" : "s", withThe(s.region), s.from, s.orders == 1 ? "it" : "them", s.to, usd(s.saved), s.shippingSaved >= 0.5 ? " (" + usd(s.shippingSaved) + " of it carrier cost)" : ""));
        }
        if (!shiftSentences.isEmpty()) out.add(shiftSentences.get(0));
        int together = r.intents.getOrDefault("together", 0), urgent = r.intents.getOrDefault("urgent", 0), gift = r.intents.getOrDefault("gift", 0), pickup = r.intents.getOrDefault("pickup", 0);
        if (together + urgent + gift + pickup > 0) {
            List<String> parts = new ArrayList<>();
            if (together > 0) parts.add(together + " asked to ship together (consolidated into one box" + (r.splitsAvoided > 0 ? ", " + r.splitsAvoided + " split" + (r.splitsAvoided == 1 ? "" : "s") + " avoided" : "") + ")");
            if (urgent > 0) parts.add(urgent + " flagged an item as urgent (expedited from the fastest node, the rest as available)");
            if (gift > 0) parts.add(gift + " " + (gift == 1 ? "is a gift" : "are gifts") + " (no receipt, gift note)");
            if (pickup > 0) parts.add(pickup + " asked for store pickup");
            out.add(String.format(Locale.ROOT, "%d of %d orders with notes carried a fulfilment intent the baseline ignored: %s.", r.intentOrders, r.notedOrders, String.join("; ", parts)));
        }
        if (r.belowFloor > 0) {
            double floorShare = r.belowFloor / (double) r.planned;
            out.add(String.format(Locale.ROOT, "%d order%s (%.0f%% of volume) would ship below the contribution-margin floor, carrying %s of margin under the baseline; Intent OMS holds them for approval instead of shipping blind.",
                r.belowFloor, r.belowFloor == 1 ? "" : "s", floorShare * 100, usd(r.belowFloorMargin)));
        }
        if (r.storeToDc > 0) {
            out.add(String.format(Locale.ROOT, "%d order%s went to a store because it was nearest; Intent OMS sources %s from distribution centres and partners with lower pick cost and better reliability.",
                r.storeToDc, r.storeToDc == 1 ? "" : "s", r.storeToDc == 1 ? "it" : "them"));
        }
        if (shiftSentences.size() > 1) out.add(shiftSentences.get(1));
        double before = r.onTimeBefore / r.planned, after = r.onTimeAfter / r.planned;
        if (Math.abs(after - before) >= 0.002) {
            out.add(String.format(Locale.ROOT, "Average on-time probability %s from %.1f%% to %.1f%%%s.", after > before ? "rises" : "falls", before * 100, after * 100,
                after < before && r.splitsAdded > 0 ? " because urgent lines are expedited ahead of the rest" : ""));
        }
        if (r.ordersWithUnknownSku > 0) {
            out.add(String.format(Locale.ROOT, "%d line%s on %d order%s had SKUs the catalogue does not know; their COGS was assumed at %.0f%% of price, so their margins are indicative.",
                r.unknownSkuLines, r.unknownSkuLines == 1 ? "" : "s", r.ordersWithUnknownSku, r.ordersWithUnknownSku == 1 ? "" : "s", r.options.unknownSkuCogsRatio() * 100));
        }
        if (r.skipped > 0) out.add(r.skipped + " order" + (r.skipped == 1 ? " was" : "s were") + " skipped: " + describeSkips(r.skippedReasons) + ".");
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    static String describeSkips(Map<String, Integer> reasons) {
        List<String> parts = new ArrayList<>();
        reasons.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(4).forEach(e -> parts.add(e.getValue() + " " + e.getKey()));
        return String.join(", ", parts);
    }

    private static String withThe(String region) {
        return switch (region) {
            case "Northeast", "Southeast", "Midwest", "Pacific Northwest", "Mountain West" -> "the " + region;
            default -> region;
        };
    }

    public static String usd(double v) {
        String s = String.format(Locale.ROOT, "%,.2f", Math.abs(v));
        return (v < 0 ? "−$" : "$") + s;
    }

    static String pct(Object v) { return v instanceof Number n ? String.format(Locale.ROOT, "%.1f%%", n.doubleValue() * 100) : "—"; }

    static String money(Object v) { return v instanceof Number n ? usd(n.doubleValue()) : "—"; }

    /** Paste-ready Markdown of the report. */
    @SuppressWarnings("unchecked")
    public static String markdown(String fileName, Map<String, Object> s, List<String> findings, List<Map<String, Object>> regions, List<String> assumptions, boolean partial) {
        StringBuilder md = new StringBuilder();
        md.append("# Margin Replay").append(fileName == null ? "" : " — " + fileName).append(partial ? " (partial)" : "").append("\n\n");
        md.append("Orders in file: ").append(s.get("orders")).append(" · replayed: ").append(s.get("planned")).append(" · skipped: ").append(s.get("skipped"));
        if (s.get("dateFrom") != null) md.append(" · window: ").append(String.valueOf(s.get("dateFrom")).substring(0, 10)).append(" → ").append(String.valueOf(s.get("dateTo")).substring(0, 10)).append(" (").append(s.get("annualisationDays")).append(" days)");
        md.append("\n\n## Headline\n\n");
        md.append("| Metric | Baseline | Intent OMS | Delta |\n|---|---:|---:|---:|\n");
        md.append("| Carrier shipping cost | ").append(money(s.get("baselineShippingCost"))).append(" | ").append(money(s.get("intentShippingCost"))).append(" | ").append(money(s.get("shippingSaved"))).append(" saved |\n");
        md.append("| Fulfilment cost (incl. labour, packaging, split penalty) | ").append(money(s.get("baselineFulfilmentCost"))).append(" | ").append(money(s.get("intentFulfilmentCost"))).append(" | ").append(money(s.get("fulfilmentSaved"))).append(" saved |\n");
        md.append("| Contribution margin | ").append(money(s.get("baselineMarginUsd"))).append(" (").append(pct(s.get("baselineMarginRatio"))).append(") | ").append(money(s.get("intentMarginUsd"))).append(" (").append(pct(s.get("intentMarginRatio"))).append(") | ").append(money(s.get("marginRecoveredUsd"))).append(" recovered |\n");
        md.append("| Split shipments | ").append(s.get("baselineSplits")).append(" | ").append(s.get("intentSplits")).append(" | ").append(s.get("splitsAvoided")).append(" avoided, ").append(s.get("splitsAdded")).append(" added for urgency |\n");
        md.append("| Avg on-time probability | ").append(pct(s.get("avgOnTimeBefore"))).append(" | ").append(pct(s.get("avgOnTimeAfter"))).append(" | |\n");
        md.append("| Orders below margin floor | ").append(s.get("baselineOrdersBelowFloor")).append(" shipped blind | ").append(s.get("ordersBelowFloorFlagged")).append(" flagged for approval | |\n");
        md.append("| Intents detected | — | ").append(s.get("ordersWithIntent")).append(" of ").append(s.get("ordersWithNotes")).append(" noted orders | ");
        Map<String, Object> intents = s.get("intentsDetected") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<String> parts = new ArrayList<>();
        intents.forEach((k, v) -> parts.add(k + " " + v));
        md.append(String.join(", ", parts)).append(" |\n");
        md.append("| Estimated annualised margin recovery | | | ").append(money(s.get("estimatedAnnualisedSavings"))).append(" (estimate) |\n\n");
        md.append("## Findings\n\n");
        for (String f : findings) md.append("- ").append(f).append('\n');
        if (regions != null && !regions.isEmpty()) {
            md.append("\n## By state (top 10 by margin recovered)\n\n| State | Region | Orders | Changed | Baseline cost | Intent cost | Margin recovered |\n|---|---|---:|---:|---:|---:|---:|\n");
            regions.stream().limit(10).forEach(r -> md.append("| ").append(r.get("state")).append(" | ").append(r.get("region")).append(" | ").append(r.get("orders")).append(" | ").append(r.get("changed"))
                .append(" | ").append(money(r.get("baselineCost"))).append(" | ").append(money(r.get("intentCost"))).append(" | ").append(money(r.get("saved"))).append(" |\n"));
        }
        md.append("\n## Assumptions\n\n");
        for (String a : assumptions) md.append("- ").append(a).append('\n');
        md.append("\n_Generated by Intent OMS Margin Replay. Nothing from the file was persisted as an order._\n");
        return md.toString();
    }
}
