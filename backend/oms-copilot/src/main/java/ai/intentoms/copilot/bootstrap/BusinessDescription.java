package ai.intentoms.copilot.bootstrap;

import java.util.List;
import java.util.Map;

/**
 * What a new tenant tells the bootstrap copilot. {@code text} is free prose (framed as untrusted data); the structured
 * fields drive the deterministic starter blueprint and refine the model's proposal.
 *
 * @param channels          web | marketplace | store | pos | b2b | agent | ship_from_store | same_day | drop_ship
 * @param nodes             node skeletons {id, type dc|store|vendor|3pl, state, capabilities[]}
 * @param categories        product categories (used to pick compliance rules: alcohol, tobacco, blade, battery, hazmat ...)
 * @param returnWindowDays  default return window (default 30)
 * @param restockingFeePct  restocking fee share (default 0)
 * @param keepItEnabled     offer keep-it refunds (default true)
 * @param marginFloor       contribution margin floor ratio (default 0.12)
 * @param hazmat            sells hazardous goods
 * @param ageRestricted     sells age-restricted goods
 * @param international     ships outside the US
 * @param stage             stage the blueprint's valid changes immediately as drafts (default true)
 */
public record BusinessDescription(String text, List<String> channels, List<Map<String, Object>> nodes, List<String> categories, Integer returnWindowDays, Double restockingFeePct,
                                  Boolean keepItEnabled, Double marginFloor, Boolean hazmat, Boolean ageRestricted, Boolean international, Boolean stage) {
    public BusinessDescription {
        text = text == null ? "" : text.trim();
        channels = channels == null || channels.isEmpty() ? List.of("web") : channels.stream().map(c -> c.trim().toLowerCase()).toList();
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        categories = categories == null ? List.of() : categories.stream().map(c -> c.trim().toLowerCase()).toList();
        returnWindowDays = returnWindowDays == null ? 30 : returnWindowDays;
        restockingFeePct = restockingFeePct == null ? 0.0 : restockingFeePct;
        keepItEnabled = keepItEnabled == null ? Boolean.TRUE : keepItEnabled;
        marginFloor = marginFloor == null ? 0.12 : marginFloor;
        hazmat = hazmat != null ? hazmat : categories.stream().anyMatch(c -> c.contains("hazmat") || c.contains("aerosol") || c.contains("fragrance") || c.contains("perfume") || c.contains("battery"));
        ageRestricted = ageRestricted != null ? ageRestricted : categories.stream().anyMatch(c -> c.contains("alcohol") || c.contains("wine") || c.contains("tobacco") || c.contains("vape") || c.contains("blade") || c.contains("knife"));
        international = international == null ? Boolean.FALSE : international;
        stage = stage == null ? Boolean.TRUE : stage;
    }
}
