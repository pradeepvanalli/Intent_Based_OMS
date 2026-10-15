package ai.intentoms.sourcing.model;

import java.util.function.Supplier;

/**
 * Thread-bound {@link SimulationOverrides} read by the candidate generator, economics and plan builder. Simulation
 * sets them around a sourcing run; live traffic never sets them, so {@link #current()} returns {@code none()}.
 */
public final class SimulationContext {
    private static final ThreadLocal<SimulationOverrides> CURRENT = new InheritableThreadLocal<>();

    private SimulationContext() {}

    public static SimulationOverrides current() {
        SimulationOverrides o = CURRENT.get();
        return o == null ? SimulationOverrides.none() : o;
    }

    public static boolean active() { return CURRENT.get() != null; }
    public static void set(SimulationOverrides o) { if (o == null) CURRENT.remove(); else CURRENT.set(o); }
    public static void clear() { CURRENT.remove(); }

    public static <T> T with(SimulationOverrides o, Supplier<T> body) {
        SimulationOverrides prev = CURRENT.get();
        set(o);
        try { return body.get(); } finally { if (prev == null) CURRENT.remove(); else CURRENT.set(prev); }
    }

    public static void run(SimulationOverrides o, Runnable body) { with(o, () -> { body.run(); return null; }); }
}
