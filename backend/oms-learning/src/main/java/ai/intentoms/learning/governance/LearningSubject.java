package ai.intentoms.learning.governance;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Who the learned values being read right now are for: the routing key of the decision or plan in progress
 * ({@code customer:<ref>}, or {@code <subjectType>:<subjectId>} until a customer is known). Set around the work by the
 * caller (the decision fabric's learning hook, the sourcing bandit) so {@link ParameterResolver} can keep a holdout
 * customer on the baseline (EX-04) without every read site passing the key along.
 */
public final class LearningSubject {
    private LearningSubject() {}

    private static final ThreadLocal<String> KEY = new ThreadLocal<>();

    public static <T> T with(String routingKey, Supplier<T> body) {
        if (routingKey == null || routingKey.isBlank()) return body.get();
        String previous = KEY.get();
        KEY.set(routingKey);
        try { return body.get(); }
        finally { if (previous == null) KEY.remove(); else KEY.set(previous); }
    }

    public static Optional<String> current() { return Optional.ofNullable(KEY.get()); }

    /** {@code customer:<ref>} when the caller knows the customer, else {@code <type>:<id>}, else null. */
    public static String routingKey(String subjectType, String subjectId, Object customerRef) {
        if (customerRef != null && !String.valueOf(customerRef).isBlank()) return "customer:" + customerRef;
        if (subjectId == null || subjectId.isBlank()) return null;
        return subjectType == null || subjectType.isBlank() ? subjectId : subjectType + ":" + subjectId;
    }
}
