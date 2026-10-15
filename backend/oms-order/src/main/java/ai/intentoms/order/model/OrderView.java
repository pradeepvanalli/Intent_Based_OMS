package ai.intentoms.order.model;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.finance.payment.Payment;
import ai.intentoms.finance.payment.Refund;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.workflow.model.WorkflowView;

import java.util.List;

/**
 * One order view across channels (FR-CS-01, UI contract item 4): the canonical order, header and line quantity statuses,
 * fulfilment groups, active holds, shipments, returns, payments and refunds, decisions with their record ids (traces via
 * {@code /decision-records/{id}/why}), the driving workflow, the merged timeline, the raw ledger, communications, SLA
 * risk and the next expected event.
 */
public record OrderView(
    Order order,
    EntityStatus header,
    List<EntityStatus> lineStatuses,
    List<FulfilmentGroup> fulfilmentGroups,
    List<Hold> holds,
    List<ShipmentSummary> shipments,
    List<ReturnSummary> returns,
    List<Payment> payments,
    List<Refund> refunds,
    List<String> decisionRecordIds,
    List<DecisionRecord> decisions,
    WorkflowView workflow,
    List<OrderTimelineEntry> timeline,
    List<LedgerEntry> ledger,
    List<Communication> communications,
    OrderRisk risk,
    NextExpectedEvent nextExpectedEvent
) {}
