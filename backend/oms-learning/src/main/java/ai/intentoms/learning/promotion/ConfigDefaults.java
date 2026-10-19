package ai.intentoms.learning.promotion;

import java.util.Optional;

/**
 * The value a tenant config key has when nothing is stored for it (the owning module's built-in default). Promotion
 * needs it to apply a change to a weight map the tenant never set: a proposal names only the weights it moves, so the
 * ones it leaves alone must come from somewhere. Implemented outside oms-learning (the app glue knows each module's
 * defaults); with no implementation, a change to an unset map must list every weight.
 */
public interface ConfigDefaults {
    Optional<Object> defaultFor(String key);
}
