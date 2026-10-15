package ai.intentoms.simulation.margin;

import ai.intentoms.sourcing.model.Policies;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Keyword fallback for fulfilment intent, ship-method mapping, region mapping and the Markdown renderer. */
class IntentDetectorTest {
    @Test
    void keywordsMapNotesToPoliciesAndFlags() {
        var together = IntentDetector.fromKeywords("Please ship everything together in one delivery");
        assertThat(together.policy()).isEqualTo(Policies.SHIP_COMPLETE);
        assertThat(together.splitOk()).isFalse();
        assertThat(together.label()).isEqualTo("together");
        var urgent = IntentDetector.fromKeywords("I need the sneakers before Friday, the rest can come later");
        assertThat(urgent.policy()).isEqualTo(Policies.EARLIEST_PARTIAL);
        assertThat(urgent.urgent()).isTrue();
        var gift = IntentDetector.fromKeywords("This is a gift for my sister, please no receipt in the box");
        assertThat(gift.policy()).isNull();
        assertThat(gift.gift()).isTrue();
        assertThat(gift.label()).isEqualTo("gift");
        assertThat(gift.any()).isTrue();
        var pickup = IntentDetector.fromKeywords("Can I pick this up in your downtown store instead?");
        assertThat(pickup.policy()).isEqualTo(Policies.PICKUP_CONSOLIDATED);
        var separate = IntentDetector.fromKeywords("Send each item as soon as it is ready, I do not mind separate boxes");
        assertThat(separate.policy()).isEqualTo(Policies.SHIP_AS_AVAILABLE);
        assertThat(separate.splitOk()).isTrue();
        assertThat(IntentDetector.fromKeywords("Leave the parcel with the concierge").any()).isFalse();
        assertThat(IntentDetector.fromKeywords(null).label()).isEqualTo("none");
        assertThat(IntentDetector.fromKeywords("Gift for mom, please ship together").label()).isEqualTo("together");
        assertThat(IntentDetector.fromKeywords("Gift for mom, please ship together").gift()).isTrue();
        assertThat(IntentDetector.label(Policies.EARLIEST_PARTIAL, true)).isEqualTo("urgent");
        assertThat(IntentDetector.label(null, true)).isEqualTo("gift");
    }

    @Test
    void shipMethodsAndRegions() {
        assertThat(ReplayPlanner.shipMethod("Standard Shipping")).isEqualTo("ground");
        assertThat(ReplayPlanner.shipMethod("Express Shipping")).isEqualTo("two_day");
        assertThat(ReplayPlanner.shipMethod("Overnight")).isEqualTo("next_day");
        assertThat(ReplayPlanner.shipMethod("Local Pickup")).isEqualTo("pickup");
        assertThat(ReplayPlanner.shipMethod("Same-Day Delivery")).isEqualTo("same_day");
        assertThat(ReplayPlanner.shipMethod(null)).isEqualTo("ground");
        assertThat(ReplayFindings.region("WA")).isEqualTo("Pacific Northwest");
        assertThat(ReplayFindings.region("ca")).isEqualTo("California");
        assertThat(ReplayFindings.region("TX")).isEqualTo("Texas & South Central");
        assertThat(ReplayFindings.region("NJ")).isEqualTo("Northeast");
        assertThat(ReplayFindings.region(null)).isEqualTo("Unknown");
        assertThat(ReplayFindings.usd(-12.5)).isEqualTo("−$12.50");
        assertThat(ReplayFindings.usd(1234.5)).isEqualTo("$1,234.50");
    }

    @Test
    void markdownAndFindingsOnAnEmptyReport() {
        MarginReplayReport r = new MarginReplayReport(MarginReplayOptions.defaults());
        r.skip("#1", "non-US destination (CA)", 2);
        assertThat(ReplayFindings.write(r)).singleElement().satisfies(f -> assertThat(f).contains("No order could be replayed").contains("non-US"));
        Map<String, Object> results = r.results("x.csv", 10, OrderCsvParser.Format.SHOPIFY, 1, false, 0, 5, 0);
        assertThat(results).containsKeys("summary", "topFindings", "byRegion", "assumptions", "perOrder", "markdownSummary", "orders", "costPerOrder", "splitRate");
        String md = (String) results.get("markdownSummary");
        assertThat(md).startsWith("# Margin Replay — x.csv").contains("## Headline").contains("## Assumptions").contains("Nothing from the file was persisted");
        assertThat((List<?>) results.get("assumptions")).hasSizeGreaterThanOrEqualTo(6);
        assertThat(((Map<?, ?>) results.get("summary")).get("skipped")).isEqualTo(1);
        assertThat(new MarginReplayOptions("Shopify", null, 2.0, "single_dc:DC-TX", false)).satisfies(o -> {
            assertThat(o.format()).isEqualTo("shopify");
            assertThat(o.assumeStock()).isTrue();
            assertThat(o.unknownSkuCogsRatio()).isEqualTo(1.0);
            assertThat(o.singleDcNode()).isEqualTo("DC-TX");
            assertThat(o.useDecisions()).isFalse();
        });
        assertThat(MarginReplayOptions.fromParams(Map.of("assumeStock", "false", "baseline", "nearest_node")).assumeStock()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MarginReplayOptions(null, null, null, "weird", null)).isInstanceOf(ai.intentoms.platform.error.ValidationException.class);
    }
}
