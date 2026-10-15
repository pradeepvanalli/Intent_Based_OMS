package ai.intentoms.returns.model;

import java.util.Set;

/** Disposition vocabulary of the {@code return_disposition} decision (FR-RET-06). */
public final class Disposition {
    private Disposition() {}
    public static final String RESTOCK = "restock";
    public static final String REFURBISH = "refurbish";
    public static final String LIQUIDATE = "liquidate";
    public static final String RETURN_TO_VENDOR = "return_to_vendor";
    public static final String DONATE = "donate";
    public static final String DESTROY = "destroy";
    public static final Set<String> ALL = Set.of(RESTOCK, REFURBISH, LIQUIDATE, RETURN_TO_VENDOR, DONATE, DESTROY);
}
