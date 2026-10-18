package ai.intentoms.learning.api;

import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.CustomerFactContext;
import ai.intentoms.learning.learner.CustomerFeatureFamilies;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.Segment;
import ai.intentoms.learning.store.CustomerEvidenceRepository;
import ai.intentoms.learning.store.SegmentRepository;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Customer intelligence (CI-01..CI-05): discovered segments and what has been learned about one customer. */
@RestController
@RequestMapping("/api/v1/learning")
public class CustomerIntelligenceController {
    private final SegmentRepository segments;
    private final CustomerFactContext facts;
    private final CustomerEvidenceRepository evidence;
    private final ParameterResolver resolver;

    public CustomerIntelligenceController(SegmentRepository segments, CustomerFactContext facts, CustomerEvidenceRepository evidence, ParameterResolver resolver) {
        this.segments = segments;
        this.facts = facts;
        this.evidence = evidence;
        this.resolver = resolver;
    }

    /** Segments from the most recent discovery run, largest first. */
    @GetMapping("/segments")
    @RequiresPermission(Permissions.LEARNING_READ)
    public List<Segment> segments() { return segments.latest(); }

    /** One customer's learned preferences, their cohort and segment, and whether decisions use them yet. */
    @GetMapping("/customers/{customerRef}/preferences")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Map<String, Object> preferences(@PathVariable String customerRef) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerRef", customerRef);
        out.put("orders", evidence.orderCount(customerRef));
        out.put("cohort", evidence.latestCohort(customerRef).orElse(null));
        out.put("usedByDecisions", resolver.isLive(CustomerFeatureFamilies.PREFERENCES));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<FactKey, LearnedFact> e : facts.facts(customerRef).entrySet()) {
            LearnedFact f = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", CustomerFactContext.field(e.getKey()));
            m.put("factKey", e.getKey().name());
            m.put("value", CustomerFactContext.scalar(f.value()));
            m.put("confidence", f.confidence());
            m.put("evidence", f.sampleSize());
            m.put("version", f.version());
            m.put("learnedAt", f.validFrom());
            list.add(m);
        }
        out.put("facts", list);
        out.put("asOf", Instant.now());
        return out;
    }
}
