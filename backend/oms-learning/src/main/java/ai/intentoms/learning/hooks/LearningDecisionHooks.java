package ai.intentoms.learning.hooks;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.runtime.DecisionLearningHooks;
import ai.intentoms.learning.governance.LearnerGovernance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * oms-learning's side of {@link DecisionLearningHooks}: delegates to the {@link FeatureContributor},
 * {@link ConfidenceCalibration}, {@link ProviderRouter} and {@link ExperimentRouter} beans each workstream adds, so
 * no two features edit the same file. When the tenant's learning is off (global or tenant kill switch) every hook
 * returns the neutral answer, which is GV-03's "revert to last stable policy" for the decision fabric.
 */
@Component
public class LearningDecisionHooks implements DecisionLearningHooks {
    private final LearnerGovernance governance;
    private final ObjectProvider<FeatureContributor> contributors;
    private final ObjectProvider<ConfidenceCalibration> calibrations;
    private final ObjectProvider<ProviderRouter> providerRouters;
    private final ObjectProvider<ExperimentRouter> experimentRouters;

    public LearningDecisionHooks(LearnerGovernance governance, ObjectProvider<FeatureContributor> contributors, ObjectProvider<ConfidenceCalibration> calibrations,
                                 ObjectProvider<ProviderRouter> providerRouters, ObjectProvider<ExperimentRouter> experimentRouters) {
        this.governance = governance;
        this.contributors = contributors;
        this.calibrations = calibrations;
        this.providerRouters = providerRouters;
        this.experimentRouters = experimentRouters;
    }

    @Override
    public Contribution contribute(String decision, Map<String, Object> input, DecisionOptions opts) {
        if (!governance.learningOn()) return Contribution.none(input);
        Map<String, Object> copy = null;
        List<Map<String, Object>> used = new ArrayList<>();
        for (FeatureContributor c : contributors.orderedStream().toList()) {
            if (!c.appliesTo(decision)) continue;
            if (copy == null) copy = deepCopy(input);
            c.contribute(decision, copy, opts, used);
        }
        return copy == null ? Contribution.none(input) : new Contribution(copy, List.copyOf(used));
    }

    @Override
    public Calibrated calibrate(String decision, int version, double rawConfidence) {
        if (!governance.learningOn()) return new Calibrated(rawConfidence, null);
        for (ConfidenceCalibration c : calibrations.orderedStream().toList()) {
            Optional<Calibrated> r = c.calibrate(decision, version, rawConfidence);
            if (r.isPresent()) return r.get();
        }
        return new Calibrated(rawConfidence, null);
    }

    @Override
    public Optional<String> preferredProvider(String decision, int version) {
        if (!governance.learningOn()) return Optional.empty();
        for (ProviderRouter r : providerRouters.orderedStream().toList()) {
            Optional<String> p = r.preferredProvider(decision, version);
            if (p.isPresent()) return p;
        }
        return Optional.empty();
    }

    @Override
    public Optional<Route> route(String decision, String routingKey, int resolvedVersion) {
        // Experiments keep routing while learning is paused: stopping a holdout mid-flight would bias its result.
        for (ExperimentRouter r : experimentRouters.orderedStream().toList()) {
            Optional<Route> route = r.route(decision, routingKey, resolvedVersion);
            if (route.isPresent()) return route;
        }
        return Optional.empty();
    }

    /** Copies maps and lists so a contributor can mutate nested candidate maps without touching the caller's input. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopy(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, copyValue(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object v) {
        if (v instanceof Map<?, ?> m) return deepCopy((Map<String, Object>) m);
        if (v instanceof List<?> l) { List<Object> c = new ArrayList<>(l.size()); l.forEach(x -> c.add(copyValue(x))); return c; }
        return v;
    }
}
