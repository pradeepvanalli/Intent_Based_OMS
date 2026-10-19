package ai.intentoms.app.glue;

import ai.intentoms.learning.promotion.ConfigDefaults;
import ai.intentoms.sourcing.config.SourcingConfigKeys;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The sourcing module's built-in plan-score weights, for promoting a weight change on a tenant that never stored
 * {@code sourcing.weights}: the weights the proposal leaves alone are rescaled from these.
 */
@Component
public class SourcingConfigDefaults implements ConfigDefaults {
    @Override
    public Optional<Object> defaultFor(String key) {
        if (!SourcingConfigKeys.WEIGHTS.equals(key)) return Optional.empty();
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("cost", SourcingConfigKeys.DEFAULT_W_COST);
        w.put("time", SourcingConfigKeys.DEFAULT_W_TIME);
        w.put("soft", SourcingConfigKeys.DEFAULT_W_SOFT);
        w.put("split", SourcingConfigKeys.DEFAULT_W_SPLIT);
        return Optional.of(w);
    }
}
