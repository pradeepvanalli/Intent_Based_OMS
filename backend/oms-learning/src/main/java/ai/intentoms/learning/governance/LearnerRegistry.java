package ai.intentoms.learning.governance;

import ai.intentoms.learning.store.LearnerRepository;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every {@link GovernedLearner} bean, and the per-tenant {@code learner} rows that govern them. At startup each
 * tenant gets a row per learner at the learner's declared default autonomy (existing rows, i.e. a human's settings,
 * are never touched); a tenant created later is registered the first time its learners are listed or gated.
 */
@Component
public class LearnerRegistry {
    private static final Logger log = LoggerFactory.getLogger(LearnerRegistry.class);

    private final ObjectProvider<GovernedLearner> learners;
    private final LearnerRepository repo;
    private final JdbcClient jdbc;
    private final Set<String> registeredTenants = ConcurrentHashMap.newKeySet();
    private volatile List<LearnerDescriptor> descriptors;

    public LearnerRegistry(ObjectProvider<GovernedLearner> learners, LearnerRepository repo, JdbcClient jdbc) {
        this.learners = learners;
        this.repo = repo;
        this.jdbc = jdbc;
    }

    public List<LearnerDescriptor> descriptors() {
        List<LearnerDescriptor> d = descriptors;
        if (d == null) descriptors = d = learners.stream().map(GovernedLearner::descriptor).sorted(Comparator.comparing(LearnerDescriptor::key)).toList();
        return d;
    }

    public Optional<LearnerDescriptor> descriptor(String key) {
        return descriptors().stream().filter(x -> x.key().equals(key)).findFirst();
    }

    /** OC-07 scope: the learners that learn from this decision's outcomes. */
    public List<String> learnersReadingOutcomesOf(String decision) {
        return descriptors().stream().filter(x -> x.outcomeDecisions().contains(decision)).map(LearnerDescriptor::key).toList();
    }

    /** Decisions whose outcome coverage matters, because at least one learner reads them. */
    public Set<String> outcomeDecisions() {
        Set<String> s = new java.util.TreeSet<>();
        descriptors().forEach(x -> s.addAll(x.outcomeDecisions()));
        return s;
    }

    /** Ensures the current tenant has a row for every learner. Cheap after the first call per tenant. */
    public void ensureRegistered() {
        String tenant = TenantContext.require();
        if (registeredTenants.contains(tenant)) return;
        for (LearnerDescriptor d : descriptors()) repo.insertIfAbsent(d.key(), d.defaultAutonomy());
        registeredTenants.add(tenant);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    public void registerAll() {
        List<String> tenants = jdbc.sql("select tenant_id from tenant order by tenant_id").query(String.class).list();
        for (String t : tenants) {
            try { TenantContext.runAs(t, this::ensureRegistered); }
            catch (RuntimeException e) { log.warn("Could not register learners for tenant {}: {}", t, e.toString()); }
        }
        log.info("Registered {} learners for {} tenants", descriptors().size(), tenants.size());
    }
}
