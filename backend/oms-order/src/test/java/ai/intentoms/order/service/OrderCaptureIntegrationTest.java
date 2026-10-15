package ai.intentoms.order.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.OrderTestBase;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.order.testflows.TestStandardShipWorkflow;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.workflow.model.WorkflowEngine;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-CAP-01/02/03/04/05/07, FR-FIN-02/04: capture end to end against PostgreSQL. */
class OrderCaptureIntegrationTest extends OrderTestBase {
    private static final Logger log = LoggerFactory.getLogger(OrderCaptureIntegrationTest.class);

    @Autowired OrderService orders;
    @Autowired StateMachine stateMachine;
    @Autowired HoldService holds;
    @Autowired WorkflowEngine workflows;

    @Test
    void happyPathPersistsPricesInitialisesStateAndStartsWorkflow() {
        CaptureRequest req = webOrder("W-" + Ids.shortId(), "Please ship everything together, it is a gift");
        OrderService.CaptureResult result = orders.captureDetailed(req, null);
        assertThat(result.replayed()).isFalse();
        Order o = result.order();
        assertThat(o.id()).startsWith("ord_");
        assertThat(o.status()).isEqualTo("created");
        assertThat(o.stateModelId()).isEqualTo("order.standard_ship");
        assertThat(o.version()).isEqualTo(1);

        // Catalogue enrichment (FR-CAP-05) and totals / tax (FR-FIN-02): 2×28 + 32 = 88 merchandise, CA 7.25% = 6.38, shipping 6.
        assertThat(o.lines()).hasSize(2);
        assertThat(o.lines().get(0).attributes()).containsEntry("hazmat", false).containsEntry("weight_kg", 0.25).containsKey("cogs");
        assertThat(o.lines().get(0).description()).isEqualTo("Organic cotton tee black M");
        assertThat(o.totals().merchandise()).isEqualTo(Money.usd("88.00"));
        assertThat(o.totals().shipping()).isEqualTo(Money.usd("6.00"));
        assertThat(o.totals().tax()).isEqualTo(Money.usd("6.38"));
        assertThat(o.totals().grandTotal()).isEqualTo(Money.usd("100.38"));
        assertThat(o.lines().get(0).tax()).isEqualTo(Money.usd("4.06"));

        // Margin snapshot (FR-FIN-04): revenue 94 (88 + 6 shipping), COGS 24, fees/reserve applied, floor from tenant config.
        assertThat(o.margin()).isNotNull();
        assertThat(o.margin().floor()).isEqualTo(0.12);
        assertThat(o.margin().marginRatio()).isGreaterThan(0.5);
        assertThat(o.margin().belowFloor()).isFalse();
        assertThat(o.margin().revenue()).isEqualByComparingTo(new BigDecimal("94.00"));

        // Intent unclear until the workflow decides (FR-CAP-06, GR-07).
        assertThat(o.intent().policy()).isEqualTo("unclear");
        assertThat(o.intent().source()).isEqualTo("pending");
        assertThat(o.notes()).singleElement().satisfies(n -> { assertThat(n.source()).isEqualTo("customer"); assertThat(n.injectionRisk()).isNull(); });
        assertThat(o.shipTo()).isPresent();
        assertThat(o.shipTo().get().verified()).isTrue();
        assertThat(o.shipTo().get().addressType()).isEqualTo("residential");

        // Rows
        Order reloaded = orders.get(o.id());
        assertThat(reloaded.lines()).hasSize(2);
        assertThat(reloaded.tenders()).singleElement().satisfies(t -> assertThat(t.status()).isEqualTo("pending"));
        assertThat(jdbc.sql("select count(*) from order_line where order_id = :id").param("id", o.id()).query(Long.class).single()).isEqualTo(2L);

        // State machines (FR-CAP-03): header + per-line quantity-level entities.
        EntityStatus header = stateMachine.status("order", o.id());
        assertThat(header.modelId()).isEqualTo("order.standard_ship");
        assertThat(header.state()).isEqualTo("created");
        EntityStatus l1 = stateMachine.status("order_line", o.id() + ":L1");
        assertThat(l1.modelId()).isEqualTo("order_line.standard_ship");
        assertThat(l1.qtyIn("created")).isEqualTo(2);
        assertThat(stateMachine.status("order_line", o.id() + ":L2").totalQty()).isEqualTo(1);

        // Ledger
        List<Map<String, Object>> captured = events(o.id(), "order.captured");
        assertThat(captured).hasSize(1);
        Map<String, Object> payload = Json.readMap((String) captured.get(0).get("payload"));
        assertThat(payload).containsEntry("channel", "web").containsEntry("orderType", "standard_ship").containsEntry("agentOrder", false);
        assertThat(String.valueOf(payload.get("customerEmailToken"))).startsWith("em_");
        assertThat((List<?>) payload.get("lines")).hasSize(2);
        assertThat(payload).containsKey("totals").containsKey("intent");

        // Workflow started (FR-WF-01)
        assertThat(workflows.find(TestStandardShipWorkflow.TYPE, o.id())).isPresent();
        assertThat(workflows.find(TestStandardShipWorkflow.TYPE, o.id()).get().businessKey()).isEqualTo(o.id());
    }

    @Test
    void replaysOnChannelReferenceAndOnIdempotencyKey() {
        String ref = "W-" + Ids.shortId();
        Order first = orders.capture(webOrder(ref), null);
        OrderService.CaptureResult again = orders.captureDetailed(webOrder(ref), null);
        assertThat(again.replayed()).isTrue();
        assertThat(again.order().id()).isEqualTo(first.id());
        assertThat(events(first.id(), "order.captured")).hasSize(1);

        String key = "idem-" + Ids.shortId();
        CaptureRequest keyed = webOrder("W-" + Ids.shortId());
        OrderService.CaptureResult a = orders.captureDetailed(keyed, key);
        OrderService.CaptureResult b = orders.captureDetailed(keyed, key);
        assertThat(a.replayed()).isFalse();
        assertThat(b.replayed()).isTrue();
        assertThat(b.order().id()).isEqualTo(a.order().id());
        // Same key with a different body is a conflict.
        assertThatThrownBy(() -> orders.captureDetailed(webOrder("W-" + Ids.shortId()), key)).isInstanceOf(ai.intentoms.platform.error.ConflictException.class);
        // A failed attempt must release the key so the client can retry.
        String failKey = "idem-" + Ids.shortId();
        CaptureRequest bad = withLines(webOrder("W-" + Ids.shortId()), List.of(line("L1", "NOPE", 1, "1.00")));
        assertThatThrownBy(() -> orders.captureDetailed(bad, failKey)).isInstanceOf(ValidationException.class);
        assertThat(jdbc.sql("select count(*) from idempotency_key where key = :k").param("k", failKey).query(Long.class).single()).isZero();
    }

    @Test
    void blockingValidationRefusesCaptureWithAllIssues() {
        CaptureRequest unknown = withLines(webOrder("W-" + Ids.shortId()), List.of(line("L1", "NOPE-1", 1, "10.00"), line("L2", "TEE-BLK-M", 1, "28.00")));
        assertThatThrownBy(() -> orders.capture(unknown, null)).isInstanceOf(ValidationException.class).satisfies(e -> {
            ValidationException ve = (ValidationException) e;
            @SuppressWarnings("unchecked") List<Map<String, Object>> issues = (List<Map<String, Object>>) ve.details().get("issues");
            assertThat(issues).anyMatch(i -> "unknown_sku".equals(i.get("code")) && "item_price_sanity".equals(i.get("step")));
        });

        CaptureRequest hazmatAir = withShipMethod(withLines(webOrder("W-" + Ids.shortId()), List.of(line("L1", "PERF-50ML", 1, "120.00"))), "air_2day");
        assertThatThrownBy(() -> orders.capture(hazmatAir, null)).isInstanceOf(ValidationException.class).satisfies(e -> {
            @SuppressWarnings("unchecked") List<Map<String, Object>> issues = (List<Map<String, Object>>) ((ValidationException) e).details().get("issues");
            assertThat(issues).anyMatch(i -> "HAZMAT_AIR".equals(i.get("code")) && "compliance".equals(i.get("step")) && "block".equals(i.get("severity")));
        });

        CaptureRequest badZip = withAddresses(webOrder("W-" + Ids.shortId()), List.of(shipTo("1 Market St", "San Francisco", "CA", "10001")));
        assertThatThrownBy(() -> orders.capture(badZip, null)).isInstanceOf(ValidationException.class).satisfies(e -> {
            @SuppressWarnings("unchecked") List<Map<String, Object>> issues = (List<Map<String, Object>>) ((ValidationException) e).details().get("issues");
            assertThat(issues).anyMatch(i -> "address_invalid".equals(i.get("code")));
        });
        assertThat(jdbc.sql("select count(*) from orders where channel_order_id = :r").param("r", badZip.channelOrderId()).query(Long.class).single()).isZero();
    }

    @Test
    void restrictedZipAndAgeRestrictedItemsCaptureWithComplianceHold() {
        config.set("compliance.restricted_zips", List.of("941"));
        try {
            Order o = orders.capture(webOrder("W-" + Ids.shortId()), null);
            List<Hold> active = holds.active("order", o.id());
            assertThat(active).anyMatch(h -> "compliance".equals(h.type()) && "ZIP_RESTRICTED".equals(h.reasonCode()) && h.blocks("release"));
            assertThat(holds.blocks("order_line", o.id() + ":L1", "decision.sourcing_plan")).isTrue();
        } finally {
            config.delete("compliance.restricted_zips");
        }
        CaptureRequest wine = withLines(webOrder("W-" + Ids.shortId()), List.of(line("L1", "WINE-RED", 2, "42.00")));
        Order w = orders.capture(wine, null);
        assertThat(holds.active("order", w.id())).anyMatch(h -> "AGE_VERIFICATION_REQUIRED".equals(h.reasonCode()));
        Map<String, Object> payload = Json.readMap((String) events(w.id(), "order.captured").get(0).get("payload"));
        assertThat((List<?>) payload.get("validationWarnings")).isNotEmpty();
    }

    @Test
    void agentOrderBeyondMandateGetsAgentVerificationHold() {
        CaptureRequest.ProvenanceInput p = new CaptureRequest.ProvenanceInput("shopbot-7", "ucp", "mnd-1", Money.usd("50.00"), "Buy two black tees and a navy cap for Jane", "sig-abc", "buyer-jane", Map.of("ucpVersion", "2026-08-25"));
        Order o = orders.capture(withProvenance(webOrder("A-" + Ids.shortId()), p), null);
        assertThat(o.isAgentOrder()).isTrue();
        assertThat(o.provenance().agentId()).isEqualTo("shopbot-7");
        assertThat(o.provenance().withinMandate()).isFalse();
        OrderView view = orders.view(o.id());
        assertThat(view.holds()).anyMatch(h -> "agent_verification".equals(h.type()) && "BEYOND_MANDATE".equals(h.reasonCode()));
        Map<String, Object> payload = Json.readMap((String) events(o.id(), "order.captured").get(0).get("payload"));
        assertThat(payload).containsEntry("agentOrder", true).containsEntry("agentId", "shopbot-7");

        CaptureRequest.ProvenanceInput within = new CaptureRequest.ProvenanceInput("shopbot-7", "mcp", "mnd-2", Money.usd("500.00"), "Buy two black tees and a navy cap for Jane", "sig", "buyer-jane", Map.of());
        Order ok = orders.capture(withProvenance(webOrder("A-" + Ids.shortId()), within), null);
        assertThat(ok.provenance().withinMandate()).isTrue();
        assertThat(holds.active("order", ok.id())).isEmpty();
        // Agent channel without provenance is refused (FR-CAP-07).
        CaptureRequest noProv = new CaptureRequest("agent", "A-" + Ids.shortId(), null, "cust-jane", jane(), List.of(line("L1", "TEE-BLK-M", 1, "28.00")),
            List.of(shipTo("1 Market St", "San Francisco", "CA", "94105")), List.of(), List.of(card("50")), List.of(), List.of(), "ground", null, "USD", Map.of(), null);
        assertThatThrownBy(() -> orders.capture(noProv, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void explicitIntentChoiceIsRecordedAtCapture() {
        Order o = orders.capture(withIntentChoices(webOrder("W-" + Ids.shortId()), List.of("ship_complete", "gift")), null);
        assertThat(o.intent().policy()).isEqualTo("ship_complete");
        assertThat(o.intent().source()).isEqualTo("explicit");
        assertThat(o.intent().confidence()).isEqualTo(1.0);
        assertThat(o.intent().isGift()).isTrue();
    }

    @Test
    void captureLatencyP95() {
        orders.capture(webOrder("W-warm-" + Ids.shortId()), null);
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            long t0 = System.nanoTime();
            orders.capture(webOrder("W-perf-" + Ids.shortId()), null);
            samples.add((System.nanoTime() - t0) / 1_000_000);
        }
        samples.sort(Long::compare);
        long p50 = samples.get(samples.size() / 2), p95 = samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
        log.info("capture latency over 50 orders: p50={} ms p95={} ms (target p95 < 200 ms)", p50, p95);
        System.out.println("CAPTURE_P95_MS=" + p95 + " CAPTURE_P50_MS=" + p50);
        assertThat(p95).as("p95 capture latency (ms); target 200").isLessThan(2000);
    }
}
