package ai.intentoms.simulation.margin;

import ai.intentoms.platform.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a CSV export into {@link ImportedOrder}s (Margin Replay). Two layouts are understood and auto-detected from the
 * header row:
 * <ul>
 *   <li><b>Shopify "Orders export"</b>: one row per line item, rows sharing a {@code Name} form one order; the header
 *       columns ({@code Email, Created at, Shipping ...}) are read from the first row of the group that carries them.</li>
 *   <li><b>Generic</b>: {@code order_id, placed_at, sku, qty, unit_price, city, state, zip, country, ship_method, note,
 *       customer_tier} (+ optional {@code shipping_paid, address1, email}), one row per line.</li>
 * </ul>
 * Lines without a SKU are kept under a synthetic {@code NAME:<item>} key and flagged so the report can say their COGS was
 * assumed. Orders are skipped (with a reason) when cancelled, without any line, or without a destination.
 */
public final class OrderCsvParser {
    public enum Format { SHOPIFY, GENERIC }

    public record Skipped(String orderId, String reason, int row) {}

    public record Parsed(Format format, List<ImportedOrder> orders, List<Skipped> skipped, int rows, List<String> warnings) {}

    private static final List<DateTimeFormatter> DATE_TIMES = List.of(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT),
        DateTimeFormatter.ofPattern("M/d/yyyy H:mm", Locale.ROOT),
        DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss", Locale.ROOT),
        DateTimeFormatter.ofPattern("d/M/yyyy H:mm", Locale.ROOT));
    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ROOT), DateTimeFormatter.ofPattern("d.M.yyyy", Locale.ROOT));
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]+");

    private OrderCsvParser() {}

    /** Detects the layout from the header row; {@code requested} may force {@code shopify} or {@code generic}. */
    public static Format detect(CsvReader.Table t, String requested) {
        String r = requested == null ? "auto" : requested.trim().toLowerCase(Locale.ROOT);
        if (r.equals("shopify")) return Format.SHOPIFY;
        if (r.equals("generic")) return Format.GENERIC;
        if (!r.equals("auto") && !r.isEmpty()) throw new ValidationException("Unknown format '" + requested + "' (auto | shopify | generic)");
        if (t.has("Name") && (t.has("Lineitem sku") || t.has("Lineitem quantity") || t.has("Lineitem name"))) return Format.SHOPIFY;
        if (t.has("order_id") && t.has("sku")) return Format.GENERIC;
        throw new ValidationException("Could not recognise the CSV layout: expected Shopify columns (Name, Lineitem sku, Lineitem quantity, ...) or generic columns (order_id, sku, qty, ...)",
            Map.of("headers", t.headers()));
    }

    public static Parsed parse(String csv, String requestedFormat, int maxOrders, int maxRows) {
        CsvReader.Table t = CsvReader.parse(csv, maxRows);
        Format f = detect(t, requestedFormat);
        Parsed p = f == Format.SHOPIFY ? shopify(t) : generic(t);
        if (p.orders().size() > maxOrders) throw new ValidationException("The file has " + p.orders().size() + " orders; at most " + maxOrders + " are accepted per replay", Map.of("orders", p.orders().size(), "max", maxOrders));
        return p;
    }

    // ---- Shopify ----------------------------------------------------------------------------------------------------

    private static Parsed shopify(CsvReader.Table t) {
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        Map<String, Integer> firstRow = new LinkedHashMap<>();
        int rowNo = 1;
        for (Map<String, String> row : t.rows()) {
            rowNo++;
            String name = CsvReader.Table.get(row, "Name");
            if (name.isEmpty()) name = CsvReader.Table.get(row, "Id");
            if (name.isEmpty()) continue;
            groups.computeIfAbsent(name, k -> new ArrayList<>()).add(row);
            firstRow.putIfAbsent(name, rowNo);
        }
        List<ImportedOrder> orders = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int missingSku = 0, badDates = 0;
        for (var e : groups.entrySet()) {
            String id = e.getKey();
            List<Map<String, String>> rows = e.getValue();
            int at = firstRow.get(id);
            if (!first(rows, "Cancelled at").isEmpty()) { skipped.add(new Skipped(id, "cancelled", at)); continue; }
            List<ImportedOrder.Line> lines = new ArrayList<>();
            for (Map<String, String> row : rows) {
                int qty = intOf(CsvReader.Table.get(row, "Lineitem quantity"), 1);
                if (qty <= 0) continue;
                boolean requiresShipping = !"false".equalsIgnoreCase(CsvReader.Table.get(row, "Lineitem requires shipping"));
                if (!requiresShipping) continue;
                String sku = CsvReader.Table.get(row, "Lineitem sku");
                String name = CsvReader.Table.get(row, "Lineitem name");
                if (sku.isEmpty() && name.isEmpty()) continue;
                boolean missing = sku.isEmpty();
                if (missing) { sku = "NAME:" + slug(name); missingSku++; }
                lines.add(new ImportedOrder.Line(sku, name.isEmpty() ? sku : name, qty, money(CsvReader.Table.get(row, "Lineitem price")), missing));
            }
            if (lines.isEmpty()) { skipped.add(new Skipped(id, "no shippable line items", at)); continue; }
            String country = first(rows, "Shipping Country");
            String state = first(rows, "Shipping Province Code");
            if (state.isEmpty()) state = first(rows, "Shipping Province");
            if (state.isEmpty()) state = first(rows, "Shipping Province Name");
            String city = first(rows, "Shipping City");
            String zip = first(rows, "Shipping Zip");
            String address1 = first(rows, "Shipping Address1");
            if (address1.isEmpty()) address1 = first(rows, "Shipping Street");
            String created = first(rows, "Created at");
            if (created.isEmpty()) created = first(rows, "Paid at");
            Instant placed = date(created);
            if (placed == null && !created.isEmpty()) badDates++;
            String tier = tier(first(rows, "Tags"));
            orders.add(new ImportedOrder(id, placed, first(rows, "Email"), tier, lines, address1, city, state, zip, country, first(rows, "Shipping Method"),
                first(rows, "Notes"), money(first(rows, "Shipping")), money(first(rows, "Total")), at));
        }
        if (missingSku > 0) warnings.add(missingSku + " line item(s) had no SKU; they were kept under their item name and priced as unknown SKUs");
        if (badDates > 0) warnings.add(badDates + " order(s) had an unreadable 'Created at'; the replay date is used for them");
        return new Parsed(Format.SHOPIFY, orders, skipped, t.rows().size(), warnings);
    }

    // ---- generic ----------------------------------------------------------------------------------------------------

    private static Parsed generic(CsvReader.Table t) {
        for (String required : List.of("order_id", "sku", "qty")) {
            if (!t.has(required)) throw new ValidationException("Generic CSV is missing the required column '" + required + "'", Map.of("headers", t.headers()));
        }
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        Map<String, Integer> firstRow = new LinkedHashMap<>();
        int rowNo = 1;
        for (Map<String, String> row : t.rows()) {
            rowNo++;
            String id = CsvReader.Table.get(row, "order_id");
            if (id.isEmpty()) continue;
            groups.computeIfAbsent(id, k -> new ArrayList<>()).add(row);
            firstRow.putIfAbsent(id, rowNo);
        }
        List<ImportedOrder> orders = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int missingSku = 0, badDates = 0;
        for (var e : groups.entrySet()) {
            String id = e.getKey();
            List<Map<String, String>> rows = e.getValue();
            int at = firstRow.get(id);
            List<ImportedOrder.Line> lines = new ArrayList<>();
            for (Map<String, String> row : rows) {
                int qty = intOf(CsvReader.Table.get(row, "qty"), 1);
                if (qty <= 0) continue;
                String sku = CsvReader.Table.get(row, "sku");
                String name = CsvReader.Table.get(row, "name");
                if (name.isEmpty()) name = CsvReader.Table.get(row, "item");
                if (sku.isEmpty() && name.isEmpty()) continue;
                boolean missing = sku.isEmpty();
                if (missing) { sku = "NAME:" + slug(name); missingSku++; }
                lines.add(new ImportedOrder.Line(sku, name.isEmpty() ? sku : name, qty, money(CsvReader.Table.get(row, "unit_price")), missing));
            }
            if (lines.isEmpty()) { skipped.add(new Skipped(id, "no line with a SKU or quantity", at)); continue; }
            String created = first(rows, "placed_at");
            Instant placed = date(created);
            if (placed == null && !created.isEmpty()) badDates++;
            orders.add(new ImportedOrder(id, placed, first(rows, "email"), tier(first(rows, "customer_tier")), lines, first(rows, "address1"), first(rows, "city"), first(rows, "state"),
                first(rows, "zip"), first(rows, "country"), first(rows, "ship_method"), first(rows, "note"), money(first(rows, "shipping_paid")), null, at));
        }
        if (missingSku > 0) warnings.add(missingSku + " line(s) had no SKU; they were kept under their item name and priced as unknown SKUs");
        if (badDates > 0) warnings.add(badDates + " order(s) had an unreadable 'placed_at'; the replay date is used for them");
        return new Parsed(Format.GENERIC, orders, skipped, t.rows().size(), warnings);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** First non-blank value of a column across the rows of one order (Shopify leaves header columns blank on extra line rows). */
    static String first(List<Map<String, String>> rows, String header) {
        for (Map<String, String> r : rows) { String v = CsvReader.Table.get(r, header); if (!v.isEmpty()) return v; }
        return "";
    }

    static String tier(String tags) {
        if (tags == null || tags.isBlank()) return "standard";
        String t = tags.toLowerCase(Locale.ROOT);
        for (String known : List.of("platinum", "gold", "silver", "vip", "standard")) if (t.contains(known)) return known.equals("vip") ? "gold" : known;
        return t.matches("[a-z_]+") && t.length() <= 16 ? t : "standard";
    }

    static int intOf(String s, int dflt) {
        if (s == null || s.isBlank()) return dflt;
        try { return (int) Math.round(Double.parseDouble(s.trim())); } catch (NumberFormatException e) { return dflt; }
    }

    static BigDecimal money(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        String cleaned = s.trim().replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) return BigDecimal.ZERO;
        try { return new BigDecimal(cleaned).setScale(2, java.math.RoundingMode.HALF_UP); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    /** Lenient timestamp: ISO instant, Shopify {@code yyyy-MM-dd HH:mm:ss -0400}, plain dates, US slashed dates. Null when unreadable. */
    public static Instant date(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try { return Instant.parse(v); } catch (RuntimeException ignored) { }
        for (DateTimeFormatter f : DATE_TIMES) {
            try { return java.time.OffsetDateTime.parse(v, f).toInstant(); } catch (RuntimeException ignored) { }
            try { return LocalDateTime.parse(v, f).toInstant(ZoneOffset.UTC); } catch (RuntimeException ignored) { }
        }
        for (DateTimeFormatter f : DATES) {
            try { return LocalDate.parse(v, f).atStartOfDay(ZoneOffset.UTC).toInstant(); } catch (RuntimeException ignored) { }
        }
        try { return LocalDate.parse(v.substring(0, Math.min(10, v.length()))).atStartOfDay(ZoneOffset.UTC).toInstant(); } catch (RuntimeException ignored) { }
        return null;
    }

    static String slug(String name) {
        String s = NON_ALNUM.matcher(name.trim()).replaceAll("-").replaceAll("(^-|-$)", "").toUpperCase(Locale.ROOT);
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
