package ai.intentoms.copilot.change;

import ai.intentoms.copilot.config.ConfigKeyCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigKeyCatalogTest {
    @Test
    void whitelistTypesAndRangesAreEnforced() {
        assertThat(ConfigKeyCatalog.validate("sourcing.margin_floor", 0.15)).isEmpty();
        assertThat(ConfigKeyCatalog.validate("sourcing.margin_floor", 1.5)).singleElement().asString().contains("<= 0.9");
        assertThat(ConfigKeyCatalog.validate("sourcing.max_split_packages", 2.5)).singleElement().asString().contains("integer");
        assertThat(ConfigKeyCatalog.validate("returns.keep_it_enabled", "maybe")).isNotEmpty();
        assertThat(ConfigKeyCatalog.validate("orchestration.pickup_window", "P5D")).isEmpty();
        assertThat(ConfigKeyCatalog.validate("orchestration.pickup_window", "5 days")).isNotEmpty();
        assertThat(ConfigKeyCatalog.validate("sourcing.channel_default_policy", Map.of("web", "ship_as_available"))).isEmpty();
        assertThat(ConfigKeyCatalog.validate("sourcing.channel_default_policy", Map.of("web", "teleport"))).isNotEmpty();
        assertThat(ConfigKeyCatalog.validate("capture.validation_steps", List.of("schema", "compliance"))).isEmpty();
        assertThat(ConfigKeyCatalog.validate("capture.validation_steps", List.of("schema", "bribe"))).isNotEmpty();
        assertThat(ConfigKeyCatalog.validate("ops.secret_backdoor", true)).singleElement().asString().contains("whitelist");
    }
}
