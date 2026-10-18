package ai.intentoms.learning.service;

import ai.intentoms.platform.config.TenantConfigStore;

import java.time.Duration;

/**
 * OC-04: how long each reward term can still change. Delivery-driven terms (on time, fill, cost, split, aged stock)
 * close with the delivery window, contacts with the contact window, fraud loss with the chargeback window (a fraud-
 * confirmed return lands inside it too). Tenant-configurable through {@code learning.maturity.delivery_days},
 * {@code return_days}, {@code contact_days} and {@code chargeback_days}; defaults 14 / 45 / 30 / 120.
 */
public record MaturityWindows(int deliveryDays, int returnDays, int contactDays, int chargebackDays) {
    public static final MaturityWindows DEFAULT = new MaturityWindows(14, 45, 30, 120);

    public static MaturityWindows forTenant(TenantConfigStore config) {
        return new MaturityWindows(config.getInt("learning.maturity.delivery_days", DEFAULT.deliveryDays),
            config.getInt("learning.maturity.return_days", DEFAULT.returnDays),
            config.getInt("learning.maturity.contact_days", DEFAULT.contactDays),
            config.getInt("learning.maturity.chargeback_days", DEFAULT.chargebackDays));
    }

    public Duration forTerm(String term) {
        return Duration.ofDays(switch (term) {
            case "fraud" -> Math.max(chargebackDays, returnDays);
            case "cx" -> contactDays;
            default -> deliveryDays;
        });
    }

    public Duration longest() {
        return Duration.ofDays(Math.max(Math.max(deliveryDays, returnDays), Math.max(contactDays, chargebackDays)));
    }
}
