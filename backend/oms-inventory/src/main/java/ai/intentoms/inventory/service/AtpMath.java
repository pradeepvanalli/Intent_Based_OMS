package ai.intentoms.inventory.service;

import ai.intentoms.inventory.model.Atp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/** Pure ATP arithmetic (FR-INV-01, FR-INV-03); unit-tested without a database. */
public final class AtpMath {
    private AtpMath() {}

    /** {@code onHand + (includeInbound ? inbound : 0) - reserved - allocated - safety}; may be negative when oversold. */
    public static int available(int onHand, int inbound, boolean includeInbound, int reserved, int allocated, int safety) {
        return onHand + (includeInbound ? inbound : 0) - reserved - allocated - safety;
    }

    /** Network total: each node contributes {@code max(0, available)} because a negative node cannot fulfil anything. */
    public static int networkTotal(Collection<Atp> perNode) {
        int total = 0;
        for (Atp a : perNode) total += Math.max(0, a.available());
        return total;
    }

    /**
     * Applies the channel buffer: {@code floor(total * (1 - buffer))} computed in decimal so e.g. 10 units with a
     * 0.9 buffer yield exactly 1, not 0. Buffers outside [0, 1] are clamped.
     */
    public static int applyBuffer(int total, double buffer) {
        if (total <= 0) return Math.max(0, total);
        double b = Math.min(1.0, Math.max(0.0, buffer));
        if (b == 0.0) return total;
        return BigDecimal.valueOf(total).multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(b))).setScale(0, RoundingMode.FLOOR).intValueExact();
    }
}
