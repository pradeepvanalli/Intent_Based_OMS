package ai.intentoms.orchestration.seed;

import ai.intentoms.order.capture.CaptureRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parsing and validation of {@code sample-orders.yaml} (docs/SAMPLE_ORDERS_FORMAT.md): every field, every error names the key and field. */
class SampleOrdersFileTest {
    static final String FULL = """
        orders:
          - key: D01
            channel: web
            channelOrderId: RET-WEB-1001
            orderType: standard_ship
            shipMethod: two_day
            placedAt: 2026-09-28T14:05:00Z
            customer:
              ref: cust-1001
              name: Dana Whitfield
              email: dana@example.com
              phone: "+1 312 555 0101"
              tier: gold
              loyaltyVerified: true
              age: 34
              attributes: { split_acceptance_rate: 0.2, returns_12m: 1, orders_12m: 4 }
            lines:
              - { id: "1", sku: HOOD-GRY-M, qty: 2, unitPrice: 68.00 }
              - { id: "2", sku: SNK-RUN-10, qty: 1, unitPrice: 145.00, discount: 10.00, attributes: { urgent: true } }
            shipTo: { name: Dana W, line1: 233 S Wacker Dr, line2: Suite 800, city: Chicago, state: IL, zip: "60606", kind: commercial }
            notes: [ "This is a birthday gift, send everything together." ]
            intentChoices: [ship_as_available]
            tender: { type: card, token: tok_visa_decline, amount: 309.10 }
            extensions: { giftWrap: true }
            provenance: { agentId: agent-shopper-1, mandateId: mandate-77, instructionText: "Buy the grey hoodie in M, ship to the Chicago office, keep it under $200." }
            lifecycle: { ship: true, partialShip: true, deliver: true, deliveredDaysAgo: 45 }
          - key: B01
            channel: web
            channelOrderId: RET-WEB-1002
            orderType: bopis
            customer: { ref: cust-1002, name: Marcus Lee }
            lines:
              - { id: "1", sku: HOOD-GRY-M, qty: 1, unitPrice: 68 }
            pickupNodeId: ST-CHI-01
            lifecycle: { bopisPickup: true, storeReject: true }
        returns:
          - { order: D01, lineId: "1", qty: 1, reason: changed_mind, text: Changed my mind, receiptPresent: false, backdateDeliveryDays: 40 }
        exceptions:
          - { order: B01, source: wms, type: store_reject, code: STORE_NO_STOCK, text: Store could not find the hoodie, playbook: manual_review }
        """;

    @Test
    void parsesEveryFieldOfTheFormat() {
        SampleOrdersFile f = SampleOrdersFile.parse(FULL);
        assertThat(f.orders()).hasSize(2);
        SampleOrdersFile.SampleSet set = f.toSampleSet(id -> Optional.of(new SampleOrdersFile.AddressSpec("Chicago store", id, null, "Chicago", "IL", "60611", "US", "commercial")));
        assertThat(set.orders()).hasSize(2);

        DemoOrders.Sample d01 = set.orders().get(0);
        CaptureRequest r = d01.request();
        assertThat(d01.key()).isEqualTo("D01");
        assertThat(r.channel()).isEqualTo("web");
        assertThat(r.channelOrderId()).isEqualTo("RET-WEB-1001");
        assertThat(r.effectiveOrderType()).isEqualTo("standard_ship");
        assertThat(r.shipMethod()).isEqualTo("two_day");
        assertThat(r.placedAt()).isEqualTo(Instant.parse("2026-09-28T14:05:00Z"));
        assertThat(r.customer().ref()).isEqualTo("cust-1001");
        assertThat(r.customer().phone()).isEqualTo("+1 312 555 0101");
        assertThat(r.customer().tier()).isEqualTo("gold");
        assertThat(r.customer().ageVerified()).isTrue();
        assertThat(r.customer().age()).isEqualTo(34);
        assertThat(r.customer().history()).containsEntry("returns_12m", 1).containsEntry("loyalty_verified", true);
        assertThat(r.lines()).hasSize(2);
        assertThat(r.lines().get(1).discount().amount()).isEqualByComparingTo("10.00");
        assertThat(r.lines().get(1).attributes()).containsEntry("urgent", true);
        assertThat(r.shipTo().name()).isEqualTo("Dana W");
        assertThat(r.shipTo().line2()).isEqualTo("Suite 800");
        assertThat(r.shipTo().country()).isEqualTo("US");
        assertThat(r.shipTo().addressType()).isEqualTo("commercial");
        assertThat(r.notes()).hasSize(1);
        assertThat(r.notes().get(0).source()).isEqualTo("customer");
        assertThat(r.intentChoices()).containsExactly("ship_as_available");
        assertThat(r.tenders().get(0).token()).isEqualTo("tok_visa_decline");
        assertThat(r.tenders().get(0).amount().amount()).isEqualByComparingTo("309.10");
        assertThat(r.extensions()).containsEntry("giftWrap", true);
        assertThat(r.provenance().agentId()).isEqualTo("agent-shopper-1");
        assertThat(r.provenance().mandateId()).isEqualTo("mandate-77");
        assertThat(d01.ship()).isTrue();
        assertThat(d01.partialShip()).isTrue();
        assertThat(d01.deliver()).isTrue();
        assertThat(d01.deliveredDaysAgo()).isEqualTo(45);
        assertThat(d01.storeReject()).isFalse();

        DemoOrders.Sample b01 = set.orders().get(1);
        CaptureRequest b = b01.request();
        assertThat(b.shipMethod()).as("pickup orders default to the pickup ship method").isEqualTo("pickup");
        assertThat(b.address("pickup").line1()).isEqualTo("ST-CHI-01");
        assertThat(b.address("pickup").zip()).isEqualTo("60611");
        assertThat(b.shipTo()).isNotNull();
        assertThat(b.extensions()).containsEntry("pickupNodeId", "ST-CHI-01");
        assertThat(b.intentChoices()).containsExactly("pickup_consolidated");
        assertThat(b.customer().ageVerified()).as("no age given leaves the customer unverified").isFalse();
        assertThat(b.customer().tier()).isEqualTo("standard");
        assertThat(b.tenders().get(0).token()).isEqualTo("tok_visa_ok");
        assertThat(b.tenders().get(0).amount().amount()).as("default tender is 110 % of merchandise").isEqualByComparingTo("74.80");
        assertThat(b01.bopisPickup()).isTrue();
        assertThat(b01.storeReject()).isTrue();
        assertThat(b01.ship()).isFalse();

        assertThat(set.returns()).hasSize(1);
        SampleOrdersFile.ReturnSpec ret = set.returns().get(0);
        assertThat(ret.order()).isEqualTo("D01");
        assertThat(ret.receipt()).isFalse();
        assertThat(ret.backdateDays()).isEqualTo(40);
        assertThat(set.exceptions()).hasSize(1);
        SampleOrdersFile.ExceptionSpec ex = set.exceptions().get(0);
        assertThat(ex.type()).isEqualTo("store_reject");
        assertThat(ex.effectiveCode()).isEqualTo("STORE_NO_STOCK");
        assertThat(ex.playbook()).isEqualTo("manual_review");
    }

    @Test
    void defaultsApplyWhenOptionalFieldsAreAbsent() {
        SampleOrdersFile f = SampleOrdersFile.parse("""
            orders:
              - key: M01
                channel: marketplace
                channelOrderId: MKT-1
                customer: { ref: c1 }
                lines: [ { id: "1", sku: PAN, qty: 2, unitPrice: 48 } ]
                shipTo: { line1: 1 Main St, city: Boston, state: MA, zip: "02116" }
            """);
        DemoOrders.Sample s = f.toSampleSet(id -> Optional.empty()).orders().get(0);
        assertThat(s.request().effectiveOrderType()).isEqualTo("standard_ship");
        assertThat(s.request().shipMethod()).isEqualTo("ground");
        assertThat(s.request().placedAt()).isNull();
        assertThat(s.request().shipTo().addressType()).isEqualTo("residential");
        assertThat(s.request().shipTo().country()).isEqualTo("US");
        assertThat(s.request().tenders().get(0).amount().amount()).isEqualByComparingTo(new BigDecimal("105.60"));
        assertThat(s.request().intentChoices()).isEmpty();
        assertThat(s.request().provenance()).isNull();
        assertThat(s.ship()).isFalse();
        assertThat(s.deliver()).isFalse();
        assertThat(f.returns()).isEmpty();
        assertThat(f.exceptions()).isEmpty();
    }

    @Test
    void validationNamesOrderKeyAndField() {
        String yaml = """
            orders:
              - key: D01
                channel: web
                channelOrderId: X-1
                customer: { ref: c1 }
                lines: [ { id: "1", sku: A, qty: 0, unitPrice: 10 }, { id: "1", qty: 1, unitPrice: 5 } ]
              - key: D01
                channelOrderId: X-1
                lines: []
                shipTo: { line1: 1 Main St, city: Boston, state: MA, zip: "02116", kind: igloo }
                lifecycle: { bopisPickup: true }
            returns:
              - { order: D09, lineId: "1", reason: changed_mind }
              - { order: D01, lineId: "7", qty: 0 }
            exceptions:
              - { order: D01, source: mars, text: hello }
            """;
        assertThatThrownBy(() -> SampleOrdersFile.parse(yaml))
            .isInstanceOf(SampleOrdersFile.SampleOrdersException.class)
            .satisfies(e -> {
                List<String> p = ((SampleOrdersFile.SampleOrdersException) e).problems();
                assertThat(p).contains(
                    "order D01: shipTo is required unless pickupNodeId is set",
                    "order D01: lines[0].qty must be a positive integer",
                    "order D01: lines[1].id '1' is duplicated",
                    "order D01: lines[1].sku is required",
                    "order D01: key is duplicated",
                    "order D01: channel is required",
                    "order D01: channelOrderId 'X-1' is duplicated",
                    "order D01: customer is required",
                    "order D01: lines must contain at least one line",
                    "order D01: shipTo.kind 'igloo' must be one of " + SampleOrdersFile.ADDRESS_KINDS,
                    "order D01: lifecycle.bopisPickup needs pickupNodeId",
                    "return[0] (order D09): order 'D09' is not an order key",
                    "return[1] (order D01): lineId '7' is not a line of order D01",
                    "return[1] (order D01): qty must be a positive integer",
                    "return[1] (order D01): reason is required",
                    "exception[0] (order D01): source 'mars' must be one of " + SampleOrdersFile.EXCEPTION_SOURCES,
                    "exception[0] (order D01): type is required");
            });
    }

    @Test
    void crossCheckReportsUnknownSkusNodesTypes() {
        SampleOrdersFile f = SampleOrdersFile.parse("""
            orders:
              - key: B01
                channel: web
                channelOrderId: W-1
                orderType: curbside
                customer: { ref: c1 }
                lines: [ { id: "1", sku: NOPE, qty: 1, unitPrice: 10 } ]
                pickupNodeId: ST-ZZZ
            exceptions:
              - { order: B01, source: cs, type: alien, text: hi }
            """);
        assertThat(f.crossCheck(Set.of("HOOD"), Set.of("ST-CHI-01"), Set.of("standard_ship"), Set.of("address"))).containsExactly(
            "order B01: orderType 'curbside' is not in order-types.yaml",
            "order B01: pickupNodeId 'ST-ZZZ' is not in nodes.yaml",
            "order B01: lines[0].sku 'NOPE' is not in catalog.yaml",
            "exception[0] (order B01): type 'alien' is not in exception-taxonomy.yaml");
        assertThat(f.crossCheck(null, null, null, null)).isEmpty();
        assertThatThrownBy(() -> f.toSampleSet(id -> Optional.empty())).hasMessageContaining("order B01: pickupNodeId 'ST-ZZZ' is unknown");
    }

    @Test
    void malformedYamlIsReported() {
        assertThatThrownBy(() -> SampleOrdersFile.parse("orders: [ { key: D01, lines: oops: }")).isInstanceOf(SampleOrdersFile.SampleOrdersException.class).hasMessageContaining("cannot parse");
        assertThat(SampleOrdersFile.parse("").orders()).isEmpty();
    }

    @Test
    void demoFallbackSetIsConsistent() {
        SampleOrdersFile.SampleSet demo = DemoOrders.sampleSet();
        Set<String> keys = demo.orders().stream().map(DemoOrders.Sample::key).collect(java.util.stream.Collectors.toSet());
        assertThat(keys).hasSize(demo.orders().size());
        assertThat(demo.returns()).extracting(SampleOrdersFile.ReturnSpec::order).allMatch(keys::contains);
        assertThat(demo.exceptions()).extracting(SampleOrdersFile.ExceptionSpec::order).allMatch(keys::contains);
    }
}
