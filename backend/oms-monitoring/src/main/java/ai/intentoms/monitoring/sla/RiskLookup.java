package ai.intentoms.monitoring.sla;

import java.util.Optional;

/**
 * Read side of the breach-risk store so the order view (UI contract item 4 {@code risk{score, level, reasons,
 * decisionRecordId}}) and order summaries can show risk without depending on the scorer (FR-MON-02).
 */
public interface RiskLookup {
    Optional<OrderRisk> riskFor(String orderId);
}
