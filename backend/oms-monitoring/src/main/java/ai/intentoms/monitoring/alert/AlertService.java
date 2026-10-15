package ai.intentoms.monitoring.alert;

import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Alerts with de-duplication (FR-MON-06). An alert is delivered to every configured channel the alert allows; a repeat of
 * the same key inside {@code alerts.dedupe_minutes} (default 15) only bumps the stored episode. Delivery failures are
 * logged and counted ({@code oms.monitoring.alerts}), never thrown: alerting must not break the operation that raised it.
 */
@Service
public class AlertService {
    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private final AlertRepository repo;
    private final ObjectProvider<AlertChannel> channels;
    private final TenantConfigStore config;
    private final TimeSource time;
    private final Metrics metrics;

    public AlertService(AlertRepository repo, ObjectProvider<AlertChannel> channels, TenantConfigStore config, TimeSource time, Metrics metrics) {
        this.repo = repo;
        this.channels = channels;
        this.config = config;
        this.time = time;
        this.metrics = metrics;
    }

    public AlertOutcome alert(Alert alert) {
        Instant now = time.now();
        try {
            Duration window = Duration.ofMinutes(config.getInt(AlertDedupPolicy.WINDOW_KEY, AlertDedupPolicy.DEFAULT_WINDOW_MINUTES));
            Optional<AlertRecord> last = repo.latestByKey(alert.key());
            if (last.isPresent() && AlertDedupPolicy.suppress(last.get().lastSeen(), now, window)) {
                AlertRecord touched = repo.touch(last.get().id(), now);
                metrics.count("oms.monitoring.alerts", "outcome", "deduplicated");
                return new AlertOutcome(touched.id(), true, List.of(), List.of());
            }
            List<String> sent = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (AlertChannel ch : channels) {
                if (!alert.channels().isEmpty() && !alert.channels().contains(ch.name())) continue;
                boolean configured;
                try { configured = ch.configured(config, alert); } catch (RuntimeException e) { configured = false; }
                if (!configured) continue;
                try {
                    ch.send(config, alert);
                    sent.add(ch.name());
                    metrics.count("oms.monitoring.alerts", "outcome", "sent", "channel", ch.name());
                } catch (RuntimeException e) {
                    failed.add(ch.name());
                    metrics.count("oms.monitoring.alerts", "outcome", "failed", "channel", ch.name());
                    log.warn("Alert {} via {} failed: {}", alert.key(), ch.name(), e.toString());
                }
            }
            AlertRecord row = repo.insert(alert, now, sent);
            if (sent.isEmpty() && failed.isEmpty()) metrics.count("oms.monitoring.alerts", "outcome", "recorded");
            return new AlertOutcome(row.id(), false, sent, failed);
        } catch (RuntimeException e) {
            log.error("Alert {} could not be processed: {}", alert.key(), e.toString());
            metrics.count("oms.monitoring.alerts", "outcome", "error");
            return new AlertOutcome(null, false, List.of(), List.of("store"));
        }
    }

    public Page<AlertRecord> list(String severity, int offset, int limit) {
        return repo.list(severity, Math.max(0, offset), limit <= 0 || limit > 500 ? 50 : limit);
    }
}
