package ai.intentoms.learning.learner;

import java.util.Map;

/**
 * Raises an operator alert from a learning monitor (FD-07 drift, OC-07 coverage). oms-monitoring provides the
 * implementation (its alert store and channels, with dedupe); without it the monitors still pause and log.
 */
public interface OperatorAlerts {
    /**
     * @param key      dedupe key, e.g. {@code learning:drift:payment_risk}
     * @param severity low, medium, high or critical
     */
    void raise(String key, String severity, String title, String body, Map<String, String> links);
}
