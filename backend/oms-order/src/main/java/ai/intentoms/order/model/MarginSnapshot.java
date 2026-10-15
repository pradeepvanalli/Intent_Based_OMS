package ai.intentoms.order.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Contribution margin as last computed (FR-FIN-04); every decision sees it (design principle 7). */
public record MarginSnapshot(BigDecimal revenue, BigDecimal contributionMargin, double marginRatio, double floor, boolean belowFloor, boolean approvalPresent, String approvalTaskId, Instant computedAt) {}
