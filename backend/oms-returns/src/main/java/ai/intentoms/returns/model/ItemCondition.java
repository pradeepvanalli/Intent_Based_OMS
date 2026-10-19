package ai.intentoms.returns.model;

import ai.intentoms.platform.error.ValidationException;

import java.util.List;
import java.util.Locale;

/** Inspection conditions (FR-RET-04/06). */
public final class ItemCondition {
    private ItemCondition() {}
    public static final String NEW = "new";
    public static final String LIKE_NEW = "like_new";
    public static final String DAMAGED = "damaged";
    public static final String MISSING_PARTS = "missing_parts";
    public static final String WRONG_ITEM = "wrong_item";
    /** What came back is not what the buyer described (the claimed defect is absent, parts swapped, a counterfeit). */
    public static final String NOT_AS_DESCRIBED = "not_as_described";
    public static final List<String> ALL = List.of(NEW, LIKE_NEW, DAMAGED, MISSING_PARTS, WRONG_ITEM, NOT_AS_DESCRIBED);

    /**
     * FD-06 return grading: an inspection that found a different item than the one sold, or an item that is not what the
     * buyer claimed, is confirmed return fraud. The buyer's own reason code ({@code not_as_described} as a complaint about
     * the listing) is a claim, not a grading, and does not count.
     */
    public static boolean isFraud(String condition) {
        return WRONG_ITEM.equals(condition) || NOT_AS_DESCRIBED.equals(condition);
    }

    public static String normalise(String condition) {
        if (condition == null || condition.isBlank()) throw new ValidationException("condition is required: " + ALL);
        String c = condition.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!ALL.contains(c)) throw new ValidationException("Unknown condition '" + condition + "'; expected one of " + ALL);
        return c;
    }
}
