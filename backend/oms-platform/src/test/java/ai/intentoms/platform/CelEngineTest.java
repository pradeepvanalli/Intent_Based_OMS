package ai.intentoms.platform;

import ai.intentoms.platform.cel.CelEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CelEngineTest {
    private final CelEngine cel = new CelEngine();

    @Test
    void evaluatesMarginFloorGuardFromAppendixA() {
        String guard = "plan.margin >= tenant.margin_floor || approval.present";
        assertThat(cel.evalBool(guard, Map.of("plan", Map.of("margin", 0.15), "tenant", Map.of("margin_floor", 0.12), "approval", Map.of("present", false)))).isTrue();
        assertThat(cel.evalBool(guard, Map.of("plan", Map.of("margin", 0.05), "tenant", Map.of("margin_floor", 0.12), "approval", Map.of("present", false)))).isFalse();
        assertThat(cel.evalBool(guard, Map.of("plan", Map.of("margin", 0.05), "tenant", Map.of("margin_floor", 0.12), "approval", Map.of("present", true)))).isTrue();
    }

    @Test
    void evaluatesHoldGuardWithMacros() {
        String guard = "!order.holds.exists(h, 'release' in h.blocks)";
        Map<String, Object> withHold = Map.of("order", Map.of("holds", List.of(Map.of("type", "fraud", "blocks", List.of("release")))));
        Map<String, Object> without = Map.of("order", Map.of("holds", List.of()));
        assertThat(cel.evalBool(guard, withHold)).isFalse();
        assertThat(cel.evalBool(guard, without)).isTrue();
    }

    @Test
    void missingRootsDefaultToEmptyMaps() {
        assertThat(cel.evalBool("!order.has_hazmat || size(plan_options) == 0", Map.of())).isTrue();
    }

    @Test
    void validateRejectsUnknownRootAndSyntaxErrors() {
        assertThat(cel.validate("plan.margin >= tenant.margin_floor")).isEmpty();
        assertThat(cel.validate("unknown_root.x > 1")).isNotEmpty();
        assertThat(cel.validate("plan.margin >=")).isNotEmpty();
        assertThat(cel.referencedRoots("plan.margin >= tenant.margin_floor || approval.present")).containsExactly("plan", "tenant", "approval");
    }

    @Test
    void stringAndListExtensionsWork() {
        assertThat(cel.evalBool("order.zip.startsWith('9') && order.state in ['CA', 'OR']", Map.of("order", Map.of("zip", "94107", "state", "CA")))).isTrue();
        assertThat(cel.eval("lines.filter(l, l.hazmat).size()", Map.of("lines", List.of(Map.of("hazmat", true), Map.of("hazmat", false))))).isEqualTo(1L);
    }
}
