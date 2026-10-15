package ai.intentoms.inventory.model;

/**
 * Available-to-promise at one node (FR-INV-01):
 * {@code available = onHand + inbound(if tenant config inventory.include_inbound) - reserved - allocated - safety}.
 *
 * @param inbound    po + asn + in_transit supply; reported even when the tenant excludes it from {@code available}
 * @param reserved   active reservations held by {@code ReservationService} plus externally fed {@code reserved} demand
 * @param confidence confidence of the on-hand figure, 1.0 unless scored (FR-INV-05)
 */
public record Atp(String sku, String nodeId, int onHand, int inbound, int reserved, int allocated, int safety, int available, double confidence) {}
