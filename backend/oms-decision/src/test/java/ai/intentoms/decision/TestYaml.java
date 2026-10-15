package ai.intentoms.decision;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared YAML fixtures for unit and integration tests. */
public final class TestYaml {
    private TestYaml() {}

    /** Demo config directory relative to the module basedir surefire runs in. */
    public static final Path DEMO_DECISIONS = Path.of("..", "..", "config", "tenants", "demo", "decisions").toAbsolutePath().normalize();
    public static final Path DEMO_GOLDEN = Path.of("..", "..", "config", "tenants", "demo", "golden-sets").toAbsolutePath().normalize();

    public static String demo(String name) {
        try { return Files.readString(DEMO_DECISIONS.resolve(name + ".yaml")); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    public static final String INTENT = """
        decision: fulfilment_policy
        version: 7
        owner: omnichannel-strategy
        tier: T1
        provider: jev
        fallbackProvider: heuristic
        timeoutMs: 800
        default: channel_default_policy
        state:
          include:
            - order.channel
            - order.notes
            - customer.tier
            - plan_options[].eta
        questions:
          intent:
            type: choice
            instructions: Which fulfilment outcome does the buyer most want?
            criteria:
              ship_complete: Everything together, even if it arrives later
              ship_as_available: Each item as soon as it is ready
              earliest_partial: One specific item is needed urgently
              pickup_consolidated: Prefers to collect in store
              unclear: Not enough information to tell
          split_ok:
            type: noul
            statement: This buyer would accept the order in more than one package.
            reasonCode: true
          is_gift:
            type: noul
            statement: The order notes indicate the order is a gift.
        bands:
          act: { confidence_gte: 0.8 }
          confirm: { confidence_gte: 0.5 }
          escalate: { otherwise: true }
        postChecks:
          - "!has(chosen_plan.margin) || chosen_plan.margin >= tenant.margin_floor"
        evaluation:
          goldenSet: fulfilment_policy_gold_v7
          minAccuracyInActBand: 0.90
          maxCalibrationError: 0.05
        """;
}
