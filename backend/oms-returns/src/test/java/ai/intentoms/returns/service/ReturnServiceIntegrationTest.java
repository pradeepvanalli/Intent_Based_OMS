package ai.intentoms.returns.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.Refund;
import ai.intentoms.finance.payment.RefundStatus;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.pii.Tokenizer;
import ai.intentoms.returns.ReturnsTestBase;
import ai.intentoms.returns.config.ReturnsConfigKeys;
import ai.intentoms.returns.model.*;
import ai.intentoms.returns.store.ReturnRepository;
import ai.intentoms.returns.store.StoreCreditRepository;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-RET-01..07 end to end against the real state machine, decision runtime (heuristic provider), finance and tasks. */
class ReturnServiceIntegrationTest extends ReturnsTestBase {
    @Autowired ReturnService service;
    @Autowired ReturnRepository repo;
    @Autowired StoreCreditRepository storeCredits;
    @Autowired ReturnSummaryLookup lookup;
    @Autowired ReturnMessages messages;
    @Autowired Tokenizer tokenizer;

    private static ReturnRequest web(Order o, String lineId, int qty, String reasonCode, String text) {
        return new ReturnRequest(o.id(), List.of(new ReturnLineRequest(lineId, qty, reasonCode, text)), ReturnChannel.WEB, null, null, true, null, null);
    }

    // ---------------------------------------------------------------------------------------------- FR-RET-01 / 02 / 04

    @Test
    void lowRiskReturnIsRefundedInstantlyThroughFinance() {
        useRelaxedRiskBands();
        Order order = newDeliveredOrder("cust_" + Ids.shortId(), "jane@example.com", List.of(line("L1", "BAG-TOTE", "45.00", 1, "3.00", Map.of("category", "accessories", "cogs", 12.0))), null);

        ReturnRma created = service.create(web(order, "L1", 1, null, "Changed my mind, no longer needed"), SYSTEM);
        assertThat(created.rmaId()).startsWith("rma_");
        assertThat(created.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(created.reasonCode()).isEqualTo("changed_mind");
        assertThat(created.reasonDecisionRecordId()).isNotNull();
        assertThat(created.lines()).singleElement().satisfies(l -> {
            assertThat(l.sku()).isEqualTo("BAG-TOTE");
            assertThat(l.tax()).isEqualTo(Money.usd("3.00"));
            assertThat(l.reasonCode()).isEqualTo("changed_mind");
        });
        assertThat(machine.status("return", created.rmaId()).state()).isEqualTo("requested");
        assertThat(eventTypes(created.rmaId())).contains("return.state_initialized", ReturnEvents.REQUESTED);

        ReturnRma decided = service.decideEligibility(created.rmaId(), SYSTEM);
        assertThat(decided.eligibility().eligible()).isTrue();
        assertThat(decided.riskScore()).isEqualTo(0.0);
        assertThat(decided.riskLevel()).isEqualTo("low");
        // the keyword heuristic may flag receipt_missing from the word "receipt" in the state; history-based codes must be absent
        assertThat(decided.reasonCodes()).extracting(ReasonCode::code).doesNotContain("many_not_received_claims", "serial_returner");
        assertThat(decided.riskDecisionRecordId()).isNotNull();
        assertThat(decided.refundTimingDecisionRecordId()).isEqualTo(decided.riskDecisionRecordId());
        assertThat(decided.refundTiming()).isEqualTo(RefundTiming.INSTANT);
        assertThat(decided.keepIt().offered()).as("recovery 18 beats return cost 10.5").isFalse();
        assertThat(decided.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(machine.status("return", created.rmaId()).state()).isEqualTo("refunded");
        assertThat(decided.refund().status()).isEqualTo(RefundInfo.SUCCEEDED);
        assertThat(decided.refund().amount()).isEqualByComparingTo("48.00");
        assertThat(decided.refund().legs()).singleElement().satisfies(leg -> {
            assertThat(leg.refundId()).startsWith("ref_");
            assertThat(leg.status()).isEqualTo("succeeded");
        });
        List<Refund> finance = payments.refundsForOrder(order.id());
        assertThat(finance).hasSize(1);
        assertThat(finance.get(0).returnId()).isEqualTo(created.rmaId());
        assertThat(finance.get(0).amount()).isEqualTo(Money.usd("48.00"));
        assertThat(finance.get(0).status()).isEqualTo(RefundStatus.SUCCEEDED);

        List<String> types = eventTypes(created.rmaId());
        assertThat(types).containsSubsequence(ReturnEvents.REQUESTED, ReturnEvents.ELIGIBILITY_DECIDED, ReturnEvents.REFUNDED);
        Event elig = lastEvent(created.rmaId(), ReturnEvents.ELIGIBILITY_DECIDED);
        assertThat(elig.payload()).containsEntry("refundTiming", "instant").containsEntry("eligible", true).containsKeys("riskScore", "riskLevel", "reasonCodes", "decisionRecordId", "band", "layer");
        assertThat(elig.correlationId()).isEqualTo(order.id());
        Event refunded = lastEvent(created.rmaId(), ReturnEvents.REFUNDED);
        assertThat(refunded.payload()).containsEntry("timing", "instant").containsKeys("amount", "legs");

        ReturnRecord view = service.get(created.rmaId());
        assertThat(view.rma()).isEqualTo(created.rmaId());
        assertThat(view.refund().amount()).isEqualTo(Money.usd("48.00"));
        assertThat(view.refundAmount()).isEqualTo(Money.usd("48.00"));
        assertThat(view.timeline()).extracting(ReturnRecord.TimelineEntry::name).contains(ReturnEvents.REQUESTED, ReturnEvents.REFUNDED);
        assertThat(view.channelOrderId()).isEqualTo(order.channelOrderId());
        String text = messages.customerText(repo.get(created.rmaId()));
        assertThat(text).contains("Your refund has been issued", "48.00 USD").doesNotContain("risk", "low");
    }

    @Test
    void cheapLowRiskReturnBecomesKeepIt() {
        useRelaxedRiskBands();
        Order order = newDeliveredOrder("cust_" + Ids.shortId(), null, List.of(line("L1", "MUG-CER", "18.00", 1, "0.00", Map.of("category", "home"))), null);
        ReturnRma created = service.create(web(order, "L1", 1, "changed_mind", null), SYSTEM);
        assertThat(created.reasonDecisionRecordId()).as("fully coded request needs no normalisation call").isNull();

        ReturnRma decided = service.decideEligibility(created.rmaId(), SYSTEM);
        assertThat(decided.keepIt().offered()).isTrue();
        assertThat(decided.keepIt().returnCostUsd()).isEqualByComparingTo("10.50");
        assertThat(decided.keepIt().recoveryValueUsd()).isEqualByComparingTo("7.20");
        assertThat(decided.status()).isEqualTo(ReturnStatus.KEEP_IT_REFUNDED);
        assertThat(decided.refund().status()).isEqualTo(RefundInfo.SUCCEEDED);
        assertThat(eventTypes(created.rmaId())).containsSubsequence(ReturnEvents.ELIGIBILITY_DECIDED, ReturnEvents.KEEP_IT_OFFERED, ReturnEvents.REFUNDED);
        assertThat(lastEvent(created.rmaId(), ReturnEvents.REFUNDED).payload()).containsEntry("keepIt", true).containsEntry("toState", "keep_it_refunded");
        assertThat(messages.customerText(repo.get(created.rmaId()))).contains("You may keep the item");
    }

    // ------------------------------------------------------------------------------------------------- T3 denial path

    @Test
    void outOfWindowReturnIsOnlyRecommendedForDenialUntilAHumanConfirms() {
        useRelaxedRiskBands();
        Order order = newDeliveredOrder("cust_" + Ids.shortId(), null, List.of(line("L1", "HOOD-GRY-M", "68.00", 1, "0.00", Map.of())), null);
        ReturnRma created = service.create(web(order, "L1", 1, "wrong_size", null), SYSTEM);
        time.override(Clock.fixed(Instant.now().plus(Duration.ofDays(40)), ZoneOffset.UTC));

        ReturnRma decided = service.decideEligibility(created.rmaId(), SYSTEM);
        assertThat(decided.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(decided.eligibility().eligible()).isFalse();
        assertThat(decided.eligibility().reasons()).containsExactly("out_of_window");
        assertThat(decided.eligibility().daysSinceDelivery()).isGreaterThanOrEqualTo(40);
        assertThat(decided.eligibility().windowDays()).isEqualTo(30);
        assertThat(decided.refundTiming()).isNull();
        assertThat(decided.reviewTaskType()).isEqualTo(ReturnTasks.TYPE_RETURN_DENIAL);
        List<HumanTask> tasks = tasksFor(ReturnTasks.QUEUE_RISK_REVIEW, created.rmaId());
        assertThat(tasks).singleElement().satisfies(t -> {
            assertThat(t.type()).isEqualTo(ReturnTasks.TYPE_RETURN_DENIAL);
            assertThat(t.status()).isEqualTo(TaskStatus.OPEN);
            assertThat(t.payload()).containsEntry("recommendation", "reject");
        });
        assertThat(lastEvent(created.rmaId(), ReturnEvents.ELIGIBILITY_DECIDED).payload()).containsEntry("eligible", false).containsEntry("recommendation", "reject").containsEntry("layer", "L0_RULE");
        assertThat(machine.status("return", created.rmaId()).state()).as("T3: the system never rejects on its own").isEqualTo("requested");
        assertThatThrownBy(() -> service.refund(created.rmaId(), SYSTEM)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.applyDenial(created.rmaId(), true, " ", SYSTEM)).as("GR-13 reason mandatory").isInstanceOf(ValidationException.class);

        ReturnRma rejected = service.applyDenial(created.rmaId(), true, "Outside the 30 day window, no exception applies", SYSTEM);
        assertThat(rejected.status()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(machine.status("return", created.rmaId()).state()).isEqualTo("rejected");
        assertThat(rejected.reviewTaskId()).isNull();
        assertThat(tasks.get(0).id()).satisfies(id -> assertThat(this.tasks.get(id).status()).isEqualTo(TaskStatus.COMPLETED));
        assertThat(lastEvent(created.rmaId(), ReturnEvents.REJECTED).payload()).containsEntry("recommendationConfirmed", true).containsEntry("policyReasons", List.of("out_of_window"));
        assertThat(messages.customerText(rejected)).contains("unable to accept", "return window for this order has closed");
    }

    @Test
    void finalSaleDenialCanBeOverriddenByAHuman() {
        useRelaxedRiskBands();
        Order order = newDeliveredOrder("cust_" + Ids.shortId(), null, List.of(line("L1", "FINAL-SALE", "12.00", 1, "0.00", Map.of("final_sale", true))), null);
        ReturnRma created = service.create(web(order, "L1", 1, "defective", null), SYSTEM);
        ReturnRma decided = service.decideEligibility(created.rmaId(), SYSTEM);
        assertThat(decided.eligibility().reasons()).containsExactly("final_sale");
        assertThat(decided.riskDecisionRecordId()).isNotNull();

        ReturnRma overridden = service.applyDenial(created.rmaId(), false, "Defective final-sale item: goodwill exception", SYSTEM);
        assertThat(overridden.status()).isEqualTo(ReturnStatus.AWAITING_RECEIPT);
        assertThat(overridden.eligibility().eligible()).isTrue();
        assertThat(overridden.eligibility().reasons()).contains("final_sale", "overridden_by_human");
        assertThat(overridden.refundTiming()).isEqualTo(RefundTiming.ON_INSPECTION);
        assertThat(lastEvent(created.rmaId(), ReturnEvents.ELIGIBILITY_DECIDED).payload()).containsEntry("layer", "L3_HUMAN");
        assertThat(jdbc.sql("select count(*) from event where tenant_id = :t and type = 'decision.overridden' and aggregate_id = :id").param("t", tenant).param("id", decided.riskDecisionRecordId()).query(Long.class).single())
            .as("GR-13 override recorded on the decision record").isEqualTo(1L);
    }

    // ------------------------------------------------------------------------- T2 non-act band → review → inspection path

    @Test
    void strictBandsEscalateThenInspectionPathRefundsAndRestocks() {
        // Demo definition: T2 act >= 0.9; the heuristic provider (0.6) lands in CONFIRM → L3 → human review.
        // Two earlier returns keep the customer out of the trusted fast lane (L0 rule) so the model is consulted.
        String customer = "cust_" + Ids.shortId();
        Order order = newDeliveredOrder(customer, "jane@example.com", List.of(line("L1", "JEAN-IND-32", "128.00", 1, "8.00", Map.of("category", "apparel", "cogs", 38.0))), Money.usd("9.00"));
        seedHistory(customer, "jane@example.com", 2, "changed_mind");
        ReturnRma created = service.create(web(order, "L1", 1, null, "Too small, wrong size"), SYSTEM);
        assertThat(created.reasonCode()).isEqualTo("wrong_size");

        ReturnRma decided = service.decideEligibility(created.rmaId(), SYSTEM);
        assertThat(decided.status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(decided.refundTiming()).isEqualTo(RefundTiming.ESCALATE);
        assertThat(decided.reviewTaskType()).isEqualTo(ReturnTasks.TYPE_RETURN_REVIEW);
        assertThat(tasksFor(ReturnTasks.QUEUE_RISK_REVIEW, created.rmaId())).singleElement().satisfies(t -> assertThat(t.payload()).containsKeys("recommendation", "options", "decisionRecordId"));
        Event elig = lastEvent(created.rmaId(), ReturnEvents.ELIGIBILITY_DECIDED);
        assertThat(elig.payload()).containsEntry("refundTiming", "escalate").containsEntry("acts", false);
        assertThat(elig.payload().get("band")).isIn("CONFIRM", "ESCALATE");
        assertThatThrownBy(() -> service.decideEligibility(created.rmaId(), SYSTEM)).isInstanceOf(ConflictException.class);

        ReturnRma reviewed = service.applyReview(created.rmaId(), RefundTiming.ON_INSPECTION, "Standard customer, refund after inspection", SYSTEM);
        assertThat(reviewed.status()).isEqualTo(ReturnStatus.AWAITING_RECEIPT);
        assertThat(reviewed.refundTiming()).isEqualTo(RefundTiming.ON_INSPECTION);
        assertThat(reviewed.reviewTaskId()).isNull();

        ReturnRma scanned = service.recordScan(created.rmaId(), SYSTEM);
        assertThat(scanned.scannedAt()).isNotNull();
        assertThat(scanned.refund()).as("on_inspection: a carrier scan does not refund").isNull();
        assertThat(scanned.status()).isEqualTo(ReturnStatus.AWAITING_RECEIPT);
        assertThat(eventTypes(created.rmaId())).contains(ReturnEvents.CARRIER_SCANNED);

        ReturnRma received = service.receive(created.rmaId(), "DC-TX", SYSTEM);
        assertThat(received.status()).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(received.receivedNodeId()).isEqualTo("DC-TX");
        assertThatThrownBy(() -> service.disposition(created.rmaId(), SYSTEM)).as("disposition needs an inspected condition").isInstanceOf(ConflictException.class);

        ReturnRma inspected = service.inspect(created.rmaId(), "new", "tags attached", SYSTEM);
        assertThat(inspected.condition()).isEqualTo("new");
        assertThat(inspected.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(inspected.refund().status()).isEqualTo(RefundInfo.SUCCEEDED);
        assertThat(inspected.refund().amount()).as("merchandise + tax, shipping not refunded by default").isEqualByComparingTo("136.00");
        assertThat(inspected.disposition()).isEqualTo(Disposition.RESTOCK);
        assertThat(inspected.dispositionDecisionRecordId()).isNotNull();
        assertThat(inspected.lines()).allSatisfy(l -> { assertThat(l.condition()).isEqualTo("new"); assertThat(l.disposition()).isEqualTo("restock"); });
        assertThat(eventTypes(created.rmaId())).containsSubsequence(ReturnEvents.RECEIVED, ReturnEvents.INSPECTED, ReturnEvents.REFUNDED, ReturnEvents.DISPOSITIONED);
        assertThat(lastEvent(created.rmaId(), ReturnEvents.REFUNDED).payload()).containsEntry("timing", "on_inspection").containsEntry("toState", "refunded");
        assertThat(payments.refundsForOrder(order.id())).hasSize(1);
    }

    // ------------------------------------------------------------------------------------- FR-RET-07 store credit only

    @Test
    void flaggedSerialReturnerGetsStoreCreditOnlyAfterInspection() {
        useRelaxedRiskBands();
        String customer = "cust_" + Ids.shortId();
        config.set(ReturnsConfigKeys.FLAGGED_CUSTOMERS, List.of(customer));
        Order order = newDeliveredOrder(customer, "serial@example.com", List.of(line("L1", "BAG-TOTE", "45.00", 1, "0.00", Map.of("category", "accessories"))), null);
        seedHistory(customer, "serial@example.com", 7, "not_received");

        ReturnRma created = service.create(new ReturnRequest(order.id(), List.of(new ReturnLineRequest("L1", 1, "not_received", null)), ReturnChannel.CS, null, null, false, null, null), SYSTEM);
        ReturnRma decided = service.decideEligibility(created.rmaId(), SYSTEM);
        assertThat(decided.riskScore()).isGreaterThanOrEqualTo(3.0);
        assertThat(decided.riskLevel()).isEqualTo("severe");
        assertThat(decided.refundTiming()).isEqualTo(RefundTiming.STORE_CREDIT_ONLY);
        assertThat(decided.reasonCodes()).extracting(ReasonCode::code).contains("flagged_or_repeated_not_received_claims");   // L0 fast lane decided; its reason is the code CS reads out
        assertThat(decided.reasonCodes()).allSatisfy(c -> assertThat(c.probability()).isGreaterThan(0.5));
        assertThat(decided.keepIt().offered()).isFalse();
        assertThat(decided.status()).isEqualTo(ReturnStatus.AWAITING_RECEIPT);
        Map<String, Object> elig = lastEvent(created.rmaId(), ReturnEvents.ELIGIBILITY_DECIDED).payload();
        assertThat(elig).containsEntry("refundTiming", "store_credit_only");
        assertThat(Json.write(elig.get("reasonCodes"))).contains("flagged_or_repeated_not_received_claims");

        service.recordScan(created.rmaId(), SYSTEM);
        assertThat(repo.get(created.rmaId()).refund()).isNull();
        service.receive(created.rmaId(), "DC-TX", SYSTEM);
        ReturnRma inspected = service.inspect(created.rmaId(), "like_new", null, SYSTEM);
        assertThat(inspected.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(inspected.refund().method()).isEqualTo(RefundInfo.STORE_CREDIT);
        assertThat(inspected.refund().legs()).singleElement().satisfies(leg -> { assertThat(leg.paymentId()).isEqualTo("store_credit"); assertThat(leg.refundId()).startsWith("sc_"); });
        assertThat(storeCredits.forRma(created.rmaId())).singleElement().satisfies(sc -> {
            assertThat(sc.amount()).isEqualTo(Money.usd("45.00"));
            assertThat(sc.customerRef()).isEqualTo(customer);
        });
        assertThat(payments.refundsForOrder(order.id())).as("no money moved back to the card").isEmpty();
        assertThat(eventTypes(created.rmaId())).contains(ReturnEvents.STORE_CREDIT_ISSUED, ReturnEvents.REFUNDED);
        assertThat(inspected.disposition()).isEqualTo(Disposition.RESTOCK);
        assertThat(messages.customerText(inspected)).contains("store credit").doesNotContain("severe", "serial");
    }

    // ---------------------------------------------------------------------------------------- T3 money-moving threshold

    @Test
    void refundAboveThresholdWaitsForFinanceApproval() {
        useRelaxedRiskBands();
        Order order = newDeliveredOrder("cust_" + Ids.shortId(), null, List.of(line("L1", "BOOT-LTH-9", "220.00", 3, "0.00", Map.of("category", "footwear"))), null);
        ReturnRma created = service.create(web(order, "L1", 3, "wrong_size", "ordered the wrong size"), SYSTEM);
        ReturnRma decided = service.decideEligibility(created.rmaId(), SYSTEM);
        assertThat(decided.refundTiming()).isEqualTo(RefundTiming.INSTANT);
        assertThat(decided.status()).isEqualTo(ReturnStatus.AWAITING_RECEIPT);
        assertThat(decided.refund().status()).isEqualTo(RefundInfo.AWAITING_APPROVAL);
        assertThat(decided.refund().holdReason()).isEqualTo("above_threshold");
        assertThat(decided.refund().amount()).isEqualByComparingTo("660.00");
        List<HumanTask> approvals = tasksFor(ReturnTasks.QUEUE_REFUND_APPROVALS, created.rmaId());
        assertThat(approvals).singleElement().satisfies(t -> assertThat(t.type()).isEqualTo(ReturnTasks.TYPE_REFUND_APPROVAL));
        assertThat(decided.refund().approvalTaskId()).isEqualTo(approvals.get(0).id());
        assertThat(payments.refundsForOrder(order.id())).isEmpty();
        assertThat(eventTypes(created.rmaId())).contains(ReturnEvents.REFUND_HELD).doesNotContain(ReturnEvents.REFUNDED);
        assertThatThrownBy(() -> service.refund(created.rmaId(), SYSTEM)).isInstanceOf(ConflictException.class);

        ReturnRma approved = service.approveRefund(created.rmaId(), SYSTEM);
        assertThat(approved.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(approved.refund().status()).isEqualTo(RefundInfo.SUCCEEDED);
        assertThat(approved.refund().approvedBy()).isEqualTo(SYSTEM.ref());
        assertThat(tasks.get(approvals.get(0).id()).status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(payments.refundsForOrder(order.id())).singleElement().satisfies(r -> assertThat(r.amount()).isEqualTo(Money.usd("660.00")));
        assertThat(eventTypes(created.rmaId())).containsSubsequence(ReturnEvents.REFUND_HELD, ReturnEvents.REFUND_APPROVED, ReturnEvents.REFUNDED);
        assertThatThrownBy(() -> service.approveRefund(created.rmaId(), SYSTEM)).isInstanceOf(ConflictException.class);
    }

    // --------------------------------------------------------------------------------------- store / validation / lists

    @Test
    void inStoreReturnIsCreatedDecidedAndReceivedAtTheStore() {
        useRelaxedRiskBands();
        Order order = newDeliveredOrder("cust_" + Ids.shortId(), null, List.of(line("L1", "CAP-NVY", "32.00", 2, "0.00", Map.of("category", "accessories"))), null);
        ReturnRma rma = service.createInStore(new InStoreReturnRequest(null, "web", order.channelOrderId(), List.of(new ReturnLineRequest("L1", 1, "changed_mind", null)), "STORE-AUS-01", null), SYSTEM);
        assertThat(rma.channel()).isEqualTo(ReturnChannel.STORE);
        assertThat(rma.storeNodeId()).isEqualTo("STORE-AUS-01");
        assertThat(rma.receivedNodeId()).isEqualTo("STORE-AUS-01");
        assertThat(rma.receivedAt()).isNotNull();
        assertThat(rma.keepIt().reason()).isEqualTo("in_store");
        assertThat(rma.status()).isIn(ReturnStatus.REFUNDED, ReturnStatus.RECEIVED);
        assertThat(rma.refund()).isNotNull();
        assertThat(lookup.forOrder(order.id())).singleElement().satisfies(s -> { assertThat(s.channel()).isEqualTo("store"); assertThat(s.lineCount()).isEqualTo(1); });
    }

    @Test
    void createValidatesReturnableQuantityAcrossRmas() {
        Order order = newDeliveredOrder("cust_" + Ids.shortId(), null, List.of(line("L1", "SOCK-3PK", "36.00", 2, "0.00", Map.of())), null);
        assertThatThrownBy(() -> service.create(web(order, "L1", 3, "changed_mind", null), SYSTEM)).isInstanceOf(ValidationException.class).hasMessageContaining("returnable");
        assertThatThrownBy(() -> service.create(web(order, "L9", 1, "changed_mind", null), SYSTEM)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(new ReturnRequest(order.id(), List.of(new ReturnLineRequest("L1", 1, null, null)), "phone", null, null, true, null, null), SYSTEM)).isInstanceOf(ValidationException.class);
        ReturnRma first = service.create(web(order, "L1", 2, "changed_mind", null), SYSTEM);
        assertThatThrownBy(() -> service.create(web(order, "L1", 1, "changed_mind", null), SYSTEM)).as("all units already on an RMA").isInstanceOf(ValidationException.class);
        ReturnRma cancelled = service.cancel(first.rmaId(), "customer kept it", SYSTEM);
        assertThat(cancelled.status()).isEqualTo(ReturnStatus.CANCELLED);
        assertThat(service.create(web(order, "L1", 1, "changed_mind", null), SYSTEM).status()).as("cancelled RMAs free the quantity").isEqualTo(ReturnStatus.REQUESTED);
    }

    @Test
    void listAndGetShapes() {
        useRelaxedRiskBands();
        String customer = "cust_" + Ids.shortId();
        Order a = newDeliveredOrder(customer, null, List.of(line("L1", "TEE-BLK-M", "28.00", 1, "0.00", Map.of())), null);
        Order b = newDeliveredOrder(customer, null, List.of(line("L1", "TEE-WHT-L", "28.00", 1, "0.00", Map.of())), null);
        ReturnRma ra = service.create(web(a, "L1", 1, "wrong_size", null), SYSTEM);
        ReturnRma rb = service.create(web(b, "L1", 1, "defective", null), SYSTEM);
        service.decideEligibility(rb.rmaId(), SYSTEM);

        Page<ReturnSummary> all = service.list(new ReturnQuery(null, null, customer, null, null, 0, 10));
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.items()).extracting(ReturnSummary::rma).containsExactlyInAnyOrder(ra.rmaId(), rb.rmaId());
        assertThat(service.list(new ReturnQuery(ReturnStatus.REQUESTED, null, customer, null, null, 0, 10)).items()).extracting(ReturnSummary::rma).containsExactly(ra.rmaId());
        assertThat(service.list(new ReturnQuery(null, a.id(), null, null, null, 0, 10)).items()).extracting(ReturnSummary::orderId).containsExactly(a.id());
        assertThat(service.list(new ReturnQuery(null, null, customer, "low", null, 0, 10)).items()).extracting(ReturnSummary::rma).containsExactly(rb.rmaId());
        ReturnSummary sb = all.items().stream().filter(s -> s.rma().equals(rb.rmaId())).findFirst().orElseThrow();
        assertThat(sb.refundAmount()).isEqualTo(Money.usd("28.00"));
        assertThat(sb.riskScore()).isEqualTo(0.0);
        assertThat(sb.lineCount()).isEqualTo(1);

        ReturnRecord rec = service.get(rb.rmaId());
        assertThat(rec.rma()).isEqualTo(rb.rmaId());
        assertThat(rec.orderId()).isEqualTo(b.id());
        assertThat(rec.lines()).singleElement().satisfies(l -> { assertThat(l.sku()).isEqualTo("TEE-WHT-L"); assertThat(l.reason()).isEqualTo("defective"); });
        assertThat(rec.eligibility().eligible()).isTrue();
        assertThat(rec.riskDecisionRecordId()).isNotNull();
        assertThat(rec.refundTimingDecisionRecordId()).isNotNull();
        assertThat(rec.reasonCodes()).isNotNull();
        assertThat(rec.keepIt()).isNotNull();
        assertThat(rec.timeline()).isNotEmpty().allSatisfy(t -> { assertThat(t.at()).isNotNull(); assertThat(t.name()).isNotBlank(); });
        assertThat(service.forOrder(b.id())).singleElement().satisfies(s -> assertThat(s.rma()).isEqualTo(rb.rmaId()));
        assertThatThrownBy(() -> service.get("rma_missing")).isInstanceOf(ai.intentoms.platform.error.NotFoundException.class);
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** Seeds prior RMAs (other orders, other channels) so cross-channel history counts them (FR-RET-07). */
    private void seedHistory(String customerRef, String email, int count, String reasonCode) {
        String token = email == null ? null : tokenizer.email(email);
        for (int i = 0; i < count; i++) {
            Instant at = Instant.now().minus(Duration.ofDays(10L * (i + 1)));
            ReturnLine l = new ReturnLine("L1", "SKU-H" + i, "history", 1, Money.usd("20.00"), Money.zero("USD"), reasonCode, null, null, null, Map.of());
            // alternate identity: half the rows only share the e-mail token (store returns without a customer ref)
            String ref = i % 2 == 0 ? customerRef : null;
            repo.insert(new ReturnRma("rma_h" + Ids.shortId(), "ord_h" + i, i % 2 == 0 ? "web" : "store", ref, token, ReturnStatus.REFUNDED, reasonCode, null, null,
                RefundTiming.INSTANT, 0.0, "low", List.of(), null, null, null, null, null, null, KeepIt.notEvaluated(), null, true, null, null, null, null, null, null, null, null,
                null, null, at, "system:test", at, at, List.of(l)));
        }
    }
}
