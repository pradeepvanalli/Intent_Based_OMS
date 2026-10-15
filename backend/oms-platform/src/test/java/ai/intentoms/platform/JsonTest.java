package ai.intentoms.platform;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTest {
    @Test
    void canonicalHashIsOrderIndependent() {
        String a = Json.sha256(Map.of("b", 1, "a", Map.of("y", 2, "x", 1)));
        String b = Json.sha256(Map.of("a", Map.of("x", 1, "y", 2), "b", 1));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void pathNavigatesMapsAndLists() {
        Map<String, Object> root = Map.of("order", Map.of("lines", List.of(Map.of("sku", "A"), Map.of("sku", "B")), "customer", Map.of("tier", "gold")));
        assertThat(Json.path(root, "order.lines[1].sku")).isEqualTo("B");
        assertThat(Json.path(root, "order.customer.tier")).isEqualTo("gold");
        assertThat(Json.path(root, "order.missing.deep")).isNull();
    }

    @Test
    void uuid7IsTimeOrdered() {
        String first = Ids.id();
        String second = Ids.id();
        assertThat(second.compareTo(first)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void yamlRoundTrip() {
        Map<String, Object> m = Json.yamlMap("a: 1\nb:\n  - x\n  - y\n");
        assertThat(m).containsEntry("a", 1);
        assertThat(Json.toYaml(m)).contains("- x");
    }
}
