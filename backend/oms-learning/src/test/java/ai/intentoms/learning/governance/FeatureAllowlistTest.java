package ai.intentoms.learning.governance;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GV-07: protected attributes are recognised by whole word and never reach a learner or a decision. */
class FeatureAllowlistTest {
    @Test
    void protectedNamesAreMatchedByWholeWord() {
        assertThat(FeatureAllowlist.isProtected("customer.age_band")).isTrue();
        assertThat(FeatureAllowlist.isProtected("buyerGender")).isTrue();
        assertThat(FeatureAllowlist.isProtected("date_of_birth")).isTrue();
        assertThat(FeatureAllowlist.isProtected("ethnicity")).isTrue();
        assertThat(FeatureAllowlist.isProtected("page_views")).isFalse();
        assertThat(FeatureAllowlist.isProtected("usage")).isFalse();
        assertThat(FeatureAllowlist.isProtected("stock_age_days")).isFalse();  // the age of stock, not of a person
        assertThat(FeatureAllowlist.isProtected("STOCK_AGE_DAYS")).isFalse();
        assertThat(FeatureAllowlist.isProtected("customer_age")).isTrue();
        assertThat(FeatureAllowlist.isProtected("NODE_ON_TIME_RATE")).isFalse();
    }

    @Test
    void learnersMayOnlyUseDeclaredNonProtectedFeatures() {
        LearnerDescriptor d = LearnerDescriptor.of("test", "Test", "t", LearnerDescriptor.Loop.FAST, "GV-07").features("order.channel", "customer.history");
        assertThat(FeatureAllowlist.violations(d, List.of("order.channel", "customer.history.orders_12m"))).isEmpty();
        assertThat(FeatureAllowlist.violations(d, List.of("order.total"))).singleElement().asString().contains("not declared");
        assertThatThrownBy(() -> FeatureAllowlist.requireAllowed(d, List.of("customer.gender"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("protected");
        assertThat(FeatureAllowlist.filter(d, Map.of("order.channel", "web", "customer.gender", "f"))).containsOnlyKeys("order.channel");
    }

    @Test
    void protectedKeysAddedToADecisionInputAreStripped() {
        Map<String, Object> cand = new LinkedHashMap<>(Map.of("id", "n1"));
        Map<String, Object> original = new LinkedHashMap<>(Map.of("candidates", new ArrayList<>(List.of(cand)), "customer", new LinkedHashMap<>(Map.of("tier", "gold"))));
        Map<String, Object> merged = ai.intentoms.learning.hooks.LearningDecisionHooksTestAccess.copy(original);
        @SuppressWarnings("unchecked") Map<String, Object> customer = (Map<String, Object>) merged.get("customer");
        customer.put("religion", "x");
        customer.put("split_tolerance", 0.3);
        @SuppressWarnings("unchecked") Map<String, Object> c0 = (Map<String, Object>) ((List<Object>) merged.get("candidates")).get(0);
        c0.put("on_time_rate", 0.9);

        List<String> removed = FeatureAllowlist.stripAddedProtected(original, merged);

        assertThat(removed).containsExactly("customer.religion");
        assertThat(customer).containsKeys("tier", "split_tolerance").doesNotContainKey("religion");
        assertThat(c0).containsKey("on_time_rate");
    }
}
