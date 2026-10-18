package ai.intentoms.learning.learner;

import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Writes the fulfilment and promise learners' facts the same way every time: a new version that supersedes the
 * previous one (versions keep counting, so a decision record's {@code learnedFacts} can name exactly which value it
 * used, XP-03 / NF-04), skipped when the value has not materially moved, and announced as {@code fact.learned}.
 */
@Component
public class FulfilmentFacts {
    private final LearnedFactRepository facts;

    public FulfilmentFacts(LearnedFactRepository facts) {
        this.facts = facts;
    }

    /**
     * Publishes a number when it is new, moved by at least {@code minChange}, or rests on materially more evidence
     * (10% more samples, at least one): a stable value still gains confidence, so a consumer's minimum-sample check
     * is eventually met. Returns true when written.
     */
    public boolean number(SubjectType st, String subjectId, FactKey key, double value, int sampleSize, String learner, double minChange) {
        Optional<LearnedFact> prev = facts.active(st, subjectId, key);
        if (prev.isPresent() && prev.get().value() instanceof TypedValue.NumberValue nv && Math.abs(nv.value() - value) < minChange && !moreEvidence(prev.get(), sampleSize)) {
            return false;
        }
        write(st, subjectId, key, new TypedValue.NumberValue(value), sampleSize, learner, prev);
        return true;
    }

    static boolean moreEvidence(LearnedFact prev, int sampleSize) {
        return sampleSize >= prev.sampleSize() + Math.max(1, (int) Math.ceil(prev.sampleSize() * 0.1));
    }

    /** Publishes a list value when it differs from the active one. */
    public boolean list(SubjectType st, String subjectId, FactKey key, List<String> values, int sampleSize, String learner) {
        Optional<LearnedFact> prev = facts.active(st, subjectId, key);
        if (prev.isPresent() && prev.get().value() instanceof TypedValue.ListValue lv && lv.values().equals(values) && !moreEvidence(prev.get(), sampleSize)) return false;
        write(st, subjectId, key, new TypedValue.ListValue(values), sampleSize, learner, prev);
        return true;
    }

    private void write(SubjectType st, String subjectId, FactKey key, TypedValue value, int sampleSize, String learner, Optional<LearnedFact> prev) {
        Instant now = Instant.now();
        int version = prev.map(f -> f.version() + 1).orElse(1);
        facts.supersede(st, subjectId, key, now);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), st, subjectId, key, value, Math.min(1.0, sampleSize / 30.0), sampleSize, learner, version, now, null, now));
    }
}
