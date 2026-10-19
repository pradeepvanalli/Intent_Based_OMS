package ai.intentoms.monitoring.alert;

import ai.intentoms.learning.learner.OperatorAlerts;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Learning monitors' alerts (FD-07 drift, OC-07 coverage) go to the same alert store and channels as every other alert. */
@Component
public class LearningOperatorAlerts implements OperatorAlerts {
    private final AlertService alerts;

    public LearningOperatorAlerts(AlertService alerts) { this.alerts = alerts; }

    @Override
    public void raise(String key, String severity, String title, String body, Map<String, String> links) {
        alerts.alert(new Alert(key, severity, title, body, links, Set.of()));
    }
}
