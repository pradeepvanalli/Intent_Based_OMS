package ai.intentoms.statemachine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared YAML fixtures for unit and integration tests. */
public final class TestModels {
    private TestModels() {}

    /** Demo tenant models directory (tests run with the module directory as cwd). */
    public static final Path DEMO_DIR = Path.of("..", "..", "config", "tenants", "demo", "state-models").toAbsolutePath().normalize();

    public static String demo(String file) {
        try { return Files.readString(DEMO_DIR.resolve(file)); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** PRD Appendix A verbatim (indentation normalised). */
    public static final String APPENDIX_A = """
        stateModel: order_line.standard_ship
        version: 3
        entity: order_line
        quantityLevel: true
        states:
          - { id: created, meta: Created }
          - { id: awaiting_sourcing }
          - { id: released, sla: { maxAge: PT4H, onBreach: playbook.store_backlog } }
          - { id: picking }
          - { id: packed }
          - { id: shipped }
          - { id: backordered, sla: { maxAge: P3D, onBreach: decision.backorder_next_action } }
          - { id: delivered, meta: Terminal }
          - { id: cancelled, meta: Terminal }
        transitions:
          - from: created
            to: awaiting_sourcing
            trigger: event.order_validated
          - from: awaiting_sourcing
            to: released
            trigger: decision.sourcing_plan
            guards:
              - "plan.margin >= tenant.margin_floor || approval.present"
              - "!order.holds.exists(h, 'release' in h.blocks)"
            actions: [reserve_inventory, send_release_to_wms]
          - from: awaiting_sourcing
            to: backordered
            trigger: decision.sourcing_plan
            guards: ["plan.result == 'no_feasible_node'"]
          - from: released
            to: awaiting_sourcing
            trigger: event.store_rejected
            actions: [penalise_node, release_reservation]
          - from: [created, awaiting_sourcing, released]
            to: cancelled
            trigger: api.cancel_line
            permission: order.cancel
            sla_hint: 42
        """;

    /** Minimal valid model: a -> b -> end, plus an api cancel. */
    public static String simple(String id, int version) {
        return """
            stateModel: %s
            version: %d
            entity: thing
            quantityLevel: true
            states:
              - { id: a, meta: Created }
              - { id: b }
              - { id: end, meta: Terminal }
            transitions:
              - { from: a, to: b, trigger: event.go }
              - { from: b, to: end, trigger: event.finish }
              - { from: [a, b], to: end, trigger: api.cancel, permission: order.cancel }
            """.formatted(id, version);
    }
}
