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
    public static final List<String> ALL = List.of(NEW, LIKE_NEW, DAMAGED, MISSING_PARTS, WRONG_ITEM);

    public static String normalise(String condition) {
        if (condition == null || condition.isBlank()) throw new ValidationException("condition is required: " + ALL);
        String c = condition.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!ALL.contains(c)) throw new ValidationException("Unknown condition '" + condition + "'; expected one of " + ALL);
        return c;
    }
}
