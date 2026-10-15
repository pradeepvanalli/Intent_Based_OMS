package ai.intentoms.simulation.margin;

import ai.intentoms.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Margin Replay parsers: RFC 4180 edge cases, both layouts, auto-detection and skip reasons. */
class OrderCsvParserTest {
    static final String SHOPIFY_HEADER = "Name,Email,Financial Status,Currency,Subtotal,Shipping,Total,Shipping Method,Created at,Lineitem quantity,Lineitem name,Lineitem price,Lineitem sku,Lineitem requires shipping,"
        + "Shipping Address1,Shipping City,Shipping Zip,Shipping Province,Shipping Country,Notes,Cancelled at,Tags\n";

    @Test
    void csvReaderHandlesQuotedCommasEscapedQuotesAndMultiLineFields() {
        String csv = "﻿a,b,c\r\n1,\"x, y\",\"line one\nline two\"\r\n2,\"say \"\"hi\"\"\",\n\n3,,z\n";
        CsvReader.Table t = CsvReader.parse(csv, 100);
        assertThat(t.headers()).containsExactly("a", "b", "c");
        assertThat(t.rows()).hasSize(3);
        assertThat(CsvReader.Table.get(t.rows().get(0), "B")).isEqualTo("x, y");
        assertThat(CsvReader.Table.get(t.rows().get(0), "c")).isEqualTo("line one\nline two");
        assertThat(CsvReader.Table.get(t.rows().get(1), "b")).isEqualTo("say \"hi\"");
        assertThat(CsvReader.Table.get(t.rows().get(1), "c")).isEmpty();
        assertThat(CsvReader.Table.get(t.rows().get(2), "c")).isEqualTo("z");
        assertThatThrownBy(() -> CsvReader.parse("a,b\n\"unterminated", 10)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> CsvReader.parse("a,b\n1,2\n3,4\n", 1)).isInstanceOf(ValidationException.class).hasMessageContaining("rows");
        assertThatThrownBy(() -> CsvReader.parse("   ", 10)).isInstanceOf(ValidationException.class);
    }

    @Test
    void shopifyRowsOfTheSameNameFormOneOrderWithHeaderFieldsFromTheFirstRow() {
        String csv = SHOPIFY_HEADER
            + "#1001,jane@example.com,paid,USD,56.00,6.00,62.00,Standard Shipping,2025-09-03 14:22:31 -0400,2,Organic cotton tee black M,28.00,TEE-BLK-M,true,\"5th Ave, Apt 3\",New York,10001,NY,US,\"Gift for mom, no receipt please.\nThanks!\",,vip\n"
            + "#1001,,,,,,,,,1,Wool cap navy,32.00,CAP-NVY,true,,,,,,,,\n"
            + "#1001,,,,,,,,,1,Gift card (digital),50.00,GIFT-CARD,false,,,,,,,,\n"
            + "#1002,omar@example.com,paid,USD,145.00,0.00,145.00,Express Shipping,2025-09-04 09:00:00 -0700,1,Trail runner sneaker 10,145.00,,true,PO Box 12,Los Angeles,90012,CA,US,,,\n"
            + "#1003,x@example.com,voided,USD,10,0,10,Standard Shipping,2025-09-05 09:00:00 -0700,1,Mug,18.00,MUG-CER,true,1 Main St,Toronto,M5V 2T6,ON,CA,,,\n"
            + "#1004,y@example.com,paid,USD,10,0,10,Standard Shipping,2025-09-06 09:00:00 -0700,1,Mug,18.00,MUG-CER,true,1 Main St,Austin,78701,TX,US,,2025-09-06 10:00:00 -0700,\n"
            + "#1005,z@example.com,paid,USD,0,0,0,Standard Shipping,2025-09-07,1,Gift card,50.00,GIFT-CARD,false,1 Main St,Austin,78701,TX,US,,,\n";
        OrderCsvParser.Parsed p = OrderCsvParser.parse(csv, "auto", 5000, 100_000);
        assertThat(p.format()).isEqualTo(OrderCsvParser.Format.SHOPIFY);
        assertThat(p.rows()).isEqualTo(7);
        assertThat(p.orders()).extracting(ImportedOrder::orderId).containsExactly("#1001", "#1002", "#1003");
        ImportedOrder o = p.orders().get(0);
        assertThat(o.lines()).hasSize(2);      // the digital line (requires shipping = false) is dropped
        assertThat(o.lines().get(0).sku()).isEqualTo("TEE-BLK-M");
        assertThat(o.lines().get(0).qty()).isEqualTo(2);
        assertThat(o.lines().get(0).unitPrice()).isEqualByComparingTo("28.00");
        assertThat(o.lines().get(1).sku()).isEqualTo("CAP-NVY");
        assertThat(o.email()).isEqualTo("jane@example.com");
        assertThat(o.city()).isEqualTo("New York");
        assertThat(o.state()).isEqualTo("NY");
        assertThat(o.zip()).isEqualTo("10001");
        assertThat(o.address1()).isEqualTo("5th Ave, Apt 3");
        assertThat(o.note()).isEqualTo("Gift for mom, no receipt please.\nThanks!");
        assertThat(o.shippingCharged()).isEqualByComparingTo("6.00");
        assertThat(o.total()).isEqualByComparingTo("62.00");
        assertThat(o.customerTier()).isEqualTo("gold");
        assertThat(o.placedAt()).isEqualTo(Instant.parse("2025-09-03T18:22:31Z"));
        assertThat(o.poBox()).isFalse();

        ImportedOrder po = p.orders().get(1);
        assertThat(po.poBox()).isTrue();
        assertThat(po.lines().get(0).skuMissing()).isTrue();
        assertThat(po.lines().get(0).sku()).isEqualTo("NAME:TRAIL-RUNNER-SNEAKER-10");
        assertThat(po.shipMethod()).isEqualTo("Express Shipping");
        assertThat(p.warnings()).anySatisfy(w -> assertThat(w).contains("no SKU"));

        // Non-US orders are parsed (the replay skips them with a reason); cancelled and all-digital orders are skipped here.
        assertThat(p.orders().get(2).country()).isEqualTo("CA");
        assertThat(p.skipped()).extracting(OrderCsvParser.Skipped::orderId).containsExactly("#1004", "#1005");
        assertThat(p.skipped()).extracting(OrderCsvParser.Skipped::reason).containsExactly("cancelled", "no shippable line items");
    }

    @Test
    void genericLayoutGroupsByOrderIdAndReadsOptionalColumns() {
        String csv = "order_id,placed_at,sku,name,qty,unit_price,city,state,zip,country,ship_method,note,customer_tier,shipping_paid\n"
            + "G-1,2025-09-10T12:00:00Z,TEE-BLK-M,,2,28.00,Seattle,Washington,98101,US,ground,\"ship together, one box\",gold,6.00\n"
            + "G-1,2025-09-10T12:00:00Z,SOCK-3PK,,1,36.00,Seattle,Washington,98101,US,ground,,gold,\n"
            + "G-2,9/12/2025 8:30,,Yoga mat,1,40.00,Dallas,TX,75201,,two_day,,standard,\n"
            + "G-3,2025-09-13,MUG-CER,,0,18.00,Dallas,TX,75201,US,ground,,,\n";
        OrderCsvParser.Parsed p = OrderCsvParser.parse(csv, null, 5000, 100_000);
        assertThat(p.format()).isEqualTo(OrderCsvParser.Format.GENERIC);
        assertThat(p.orders()).hasSize(2);
        ImportedOrder g1 = p.orders().get(0);
        assertThat(g1.lines()).hasSize(2);
        assertThat(g1.state()).isEqualTo("Washington");
        assertThat(g1.note()).isEqualTo("ship together, one box");
        assertThat(g1.customerTier()).isEqualTo("gold");
        assertThat(g1.shippingCharged()).isEqualByComparingTo("6.00");
        ImportedOrder g2 = p.orders().get(1);
        assertThat(g2.lines().get(0).skuMissing()).isTrue();
        assertThat(g2.lines().get(0).sku()).isEqualTo("NAME:YOGA-MAT");
        assertThat(g2.placedAt()).isEqualTo(Instant.parse("2025-09-12T08:30:00Z"));
        assertThat(g2.country()).isEmpty();
        assertThat(p.skipped()).singleElement().satisfies(s -> { assertThat(s.orderId()).isEqualTo("G-3"); assertThat(s.reason()).contains("quantity"); });
        assertThat(OrderCsvParser.parse(csv, "generic", 5000, 100_000).orders()).hasSize(2);
    }

    @Test
    void rejectsUnknownLayoutsBadFormatsAndTooManyOrders() {
        assertThatThrownBy(() -> OrderCsvParser.parse("foo,bar\n1,2\n", "auto", 10, 100)).isInstanceOf(ValidationException.class).hasMessageContaining("recognise");
        assertThatThrownBy(() -> OrderCsvParser.parse("order_id,sku,qty\nA,X,1\n", "xml", 10, 100)).isInstanceOf(ValidationException.class).hasMessageContaining("format");
        assertThatThrownBy(() -> OrderCsvParser.parse("order_id,qty\nA,1\n", "generic", 10, 100)).isInstanceOf(ValidationException.class).hasMessageContaining("sku");
        StringBuilder sb = new StringBuilder("order_id,sku,qty,unit_price,city,state\n");
        for (int i = 0; i < 12; i++) sb.append("O").append(i).append(",TEE-BLK-M,1,28.00,Austin,TX\n");
        assertThatThrownBy(() -> OrderCsvParser.parse(sb.toString(), "auto", 10, 100)).isInstanceOf(ValidationException.class).hasMessageContaining("at most 10");
    }

    @Test
    void lenientDatesAndMoney() {
        assertThat(OrderCsvParser.date("2025-09-03 14:22:31 -0400")).isEqualTo(Instant.parse("2025-09-03T18:22:31Z"));
        assertThat(OrderCsvParser.date("2025-09-03 14:22:31 +00:00")).isEqualTo(Instant.parse("2025-09-03T14:22:31Z"));
        assertThat(OrderCsvParser.date("2025-09-03 14:22:31")).isEqualTo(Instant.parse("2025-09-03T14:22:31Z"));
        assertThat(OrderCsvParser.date("2025-09-03")).isEqualTo(Instant.parse("2025-09-03T00:00:00Z"));
        assertThat(OrderCsvParser.date("9/3/2025")).isEqualTo(Instant.parse("2025-09-03T00:00:00Z"));
        assertThat(OrderCsvParser.date("not a date")).isNull();
        assertThat(OrderCsvParser.date("")).isNull();
        assertThat(OrderCsvParser.money("$1,234.50")).isEqualByComparingTo("1234.50");
        assertThat(OrderCsvParser.money("")).isEqualByComparingTo("0");
        assertThat(OrderCsvParser.money("abc")).isEqualByComparingTo("0");
        assertThat(OrderCsvParser.tier("VIP, newsletter")).isEqualTo("gold");
        assertThat(OrderCsvParser.tier("")).isEqualTo("standard");
    }

    @Test
    void bundledSamplesParse() {
        String shopify = new String(MarginReplayService.sampleBytes(), java.nio.charset.StandardCharsets.UTF_8);
        OrderCsvParser.Parsed p = OrderCsvParser.parse(shopify, "auto", MarginReplayService.MAX_ORDERS, MarginReplayService.MAX_ROWS);
        assertThat(p.format()).isEqualTo(OrderCsvParser.Format.SHOPIFY);
        assertThat(p.orders().size() + p.skipped().size()).isEqualTo(300);
        assertThat(p.skipped()).extracting(OrderCsvParser.Skipped::reason).containsOnly("cancelled");
        assertThat(p.orders().stream().filter(ImportedOrder::hasNote).count()).isBetween(20L, 60L);
        assertThat(p.orders().stream().filter(ImportedOrder::poBox).count()).isGreaterThanOrEqualTo(4);
        assertThat(p.orders().stream().filter(o -> !UsGeocoder.isUs(o.country())).count()).isEqualTo(2);
        assertThat(p.orders().stream().flatMap(o -> o.lines().stream()).filter(ImportedOrder.Line::skuMissing).count()).isEqualTo(2);
        assertThat(p.orders()).allSatisfy(o -> assertThat(o.placedAt()).isNotNull());
        Map<String, Long> byState = p.orders().stream().collect(java.util.stream.Collectors.groupingBy(ImportedOrder::state, java.util.stream.Collectors.counting()));
        assertThat(byState.get("CA") + byState.get("NY")).isGreaterThan(80L);

        String generic = new String(resource("replay/generic-orders-sample.csv"), java.nio.charset.StandardCharsets.UTF_8);
        OrderCsvParser.Parsed g = OrderCsvParser.parse(generic, "auto", 5000, 100_000);
        assertThat(g.format()).isEqualTo(OrderCsvParser.Format.GENERIC);
        assertThat(g.rows()).isEqualTo(50);
        assertThat(g.orders()).isNotEmpty().allSatisfy(o -> assertThat(o.lines()).isNotEmpty());
        assertThat(List.of(OrderCsvParser.Format.values())).hasSize(2);
    }

    static byte[] resource(String name) {
        try (var in = OrderCsvParserTest.class.getClassLoader().getResourceAsStream(name)) { return in.readAllBytes(); } catch (Exception e) { throw new IllegalStateException(name, e); }
    }
}
