package ai.intentoms.inventory.service;

import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Expires cart/checkout reservations past their TTL (FR-INV-02) for every active tenant, every 5 s by default
 * ({@code oms.inventory.expiry-sweep-ms}). Disable on instances that must not sweep with {@code oms.inventory.expiry-sweep-enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "oms.inventory", name = "expiry-sweep-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpirySweeper {
    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final JdbcClient jdbc;
    private final ReservationService reservations;

    public ReservationExpirySweeper(JdbcClient jdbc, ReservationService reservations) {
        this.jdbc = jdbc;
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${oms.inventory.expiry-sweep-ms:5000}", initialDelayString = "${oms.inventory.expiry-sweep-ms:5000}")
    public void sweep() {
        List<String> tenants = jdbc.sql("select tenant_id from tenant where status = 'active'").query(String.class).list();
        for (String tenant : tenants) {
            try {
                int n = TenantContext.supplyAs(tenant, reservations::expireDue);
                if (n > 0) log.info("Expired {} reservations for tenant {}", n, tenant);
            } catch (RuntimeException e) {
                log.warn("Reservation expiry sweep failed for tenant {}: {}", tenant, e.getMessage());
            }
        }
    }
}
