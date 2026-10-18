package ai.intentoms.inventory.learning;

import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Shared plumbing for the inventory learners: which tenants to run for, and versioned fact writes. */
@Component
public class InventoryLearnerSupport {
    private final JdbcClient jdbc;
    private final LearnedFactRepository facts;

    public InventoryLearnerSupport(JdbcClient jdbc, LearnedFactRepository facts) {
        this.jdbc = jdbc;
        this.facts = facts;
    }

    /** The current tenant when one is bound (an on-demand run), else every tenant. */
    public List<String> tenants() {
        return TenantContext.current().map(List::of).orElseGet(() -> jdbc.sql("select tenant_id from tenant order by tenant_id").query(String.class).list());
    }

    public Optional<LearnedFact> active(SubjectType st, String id, FactKey key) { return facts.active(st, id, key); }

    /**
     * Writes a new version of a numeric fact when it moved by at least {@code minChange} (or did not exist), closing the
     * previous version. Returns true when a version was written.
     */
    public boolean writeNumber(SubjectType st, String id, FactKey key, double value, double confidence, int sampleSize, String learner, double minChange) {
        Optional<LearnedFact> current = facts.active(st, id, key);
        if (current.isPresent() && current.get().value() instanceof TypedValue.NumberValue n && Math.abs(n.value() - value) < minChange) return false;
        write(st, id, key, new TypedValue.NumberValue(value), confidence, sampleSize, learner, current);
        return true;
    }

    /** Writes a new version of a list fact when its entries changed. */
    public boolean writeList(SubjectType st, String id, FactKey key, List<String> values, double confidence, int sampleSize, String learner) {
        Optional<LearnedFact> current = facts.active(st, id, key);
        if (current.isPresent() && current.get().value() instanceof TypedValue.ListValue l && l.values().equals(values)) return false;
        write(st, id, key, new TypedValue.ListValue(List.copyOf(values)), confidence, sampleSize, learner, current);
        return true;
    }

    /** Closes the active fact, if any (the thing it described no longer holds). */
    public void close(SubjectType st, String id, FactKey key) {
        facts.supersede(st, id, key, Instant.now());
    }

    private void write(SubjectType st, String id, FactKey key, TypedValue value, double confidence, int sampleSize, String learner, Optional<LearnedFact> current) {
        Instant now = Instant.now();
        int version = current.map(f -> f.version() + 1).orElseGet(() -> facts.history(st, id, key, 1).stream().findFirst().map(f -> f.version() + 1).orElse(1));
        facts.supersede(st, id, key, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), st, id, key, value, Math.max(0, Math.min(1, confidence)), sampleSize, learner, version, now, null, now));
    }
}
