package ai.intentoms.simulation.synthetic;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-SIM-05: deterministic with a seed, respects the mixes and pools, produces valid capture requests. */
class SyntheticOrderGeneratorTest {
    private final SyntheticOrderGenerator generator = new SyntheticOrderGenerator(sku -> sku.startsWith("KNOWN") ? Optional.of(Money.usd("20.00")) : Optional.empty());

    @Test
    void sameSeedSameRequestsDifferentSeedDifferentRequests() {
        SyntheticSpec spec = SyntheticSpec.of(40, 7L);
        List<CaptureRequest> a = generator.generate(spec), b = generator.generate(spec);
        assertThat(a).hasSize(40).isEqualTo(b);
        List<CaptureRequest> c = generator.generate(SyntheticSpec.of(40, 8L));
        assertThat(c).isNotEqualTo(a);
        assertThat(a.stream().map(CaptureRequest::channelOrderId).collect(Collectors.toSet())).hasSize(40);
    }

    @Test
    void honoursMixesPoolsAndBounds() {
        SyntheticSpec spec = new SyntheticSpec(60, Map.of("marketplace", 1.0), Map.of("standard_ship", 3.0, "bopis", 1.0), List.of("KNOWN-1", "KNOWN-2", "OTHER-9"),
            List.of("60611|Chicago|IL"), List.of("ship it all together"), 3L, 2, 2, 1.0, "LOAD");
        List<CaptureRequest> reqs = generator.generate(spec);
        assertThat(reqs).hasSize(60);
        assertThat(reqs).allMatch(r -> "marketplace".equals(r.channel()));
        assertThat(reqs).extracting(CaptureRequest::orderType).containsOnly("standard_ship", "bopis").contains("bopis");
        assertThat(reqs).allMatch(r -> r.channelOrderId().startsWith("LOAD-3-"));
        assertThat(reqs).allMatch(r -> r.lines().size() >= 1 && r.lines().size() <= 2);
        assertThat(reqs).allMatch(r -> r.lines().stream().allMatch(l -> l.qty() >= 1 && l.qty() <= 2));
        assertThat(reqs).allMatch(r -> r.notes().size() == 1 && "ship it all together".equals(r.notes().get(0).text()));
        assertThat(reqs).allMatch(r -> r.addresses().size() == 1 && "60611".equals(r.addresses().get(0).zip()) && "IL".equals(r.addresses().get(0).state()));
        Set<String> skus = reqs.stream().flatMap(r -> r.lines().stream()).map(CaptureRequest.LineInput::sku).collect(Collectors.toSet());
        assertThat(skus).isSubsetOf("KNOWN-1", "KNOWN-2", "OTHER-9");
        // Catalogue prices when known, deterministic pseudo prices otherwise.
        assertThat(reqs.stream().flatMap(r -> r.lines().stream()).filter(l -> l.sku().startsWith("KNOWN")).map(CaptureRequest.LineInput::unitPrice)).containsOnly(Money.usd("20.00"));
        assertThat(reqs.stream().flatMap(r -> r.lines().stream()).filter(l -> l.sku().equals("OTHER-9")).map(CaptureRequest.LineInput::unitPrice).distinct()).hasSize(1);
        // Tenders cover merchandise plus shipping; BOPIS orders carry a pickup address and no shipping charge.
        assertThat(reqs).allMatch(r -> r.tenderTotal().compareTo(r.merchandiseTotal().add(r.chargeTotal())) >= 0);
        assertThat(reqs).filteredOn(r -> "bopis".equals(r.orderType())).allMatch(r -> "pickup".equals(r.addresses().get(0).type()) && r.charges().isEmpty());
    }

    @Test
    void agentChannelOrdersCarryProvenanceAndAnAgentTender() {
        SyntheticSpec spec = new SyntheticSpec(10, Map.of("agent", 1.0), null, null, null, null, 11L, null, null, 0.0, null);
        List<CaptureRequest> reqs = generator.generate(spec);
        assertThat(reqs).allMatch(CaptureRequest::isAgentOrder);
        assertThat(reqs).allMatch(r -> r.tenders().size() == 1 && "agent_token".equals(r.tenders().get(0).type()));
        assertThat(reqs).allMatch(r -> r.provenance().instructionText().startsWith("Buy ") && r.provenance().spendLimit().compareTo(r.tenderTotal()) > 0);
        assertThat(reqs).allMatch(r -> r.notes().isEmpty());
    }

    @Test
    void specNormalisesDefaultsAndBounds() {
        SyntheticSpec s = new SyntheticSpec(0, null, null, null, null, null, null, 0, 0, 2.0, " ");
        assertThat(s.count()).isEqualTo(1);
        assertThat(s.seed()).isEqualTo(42L);
        assertThat(s.maxLinesPerOrder()).isEqualTo(3);
        assertThat(s.maxQtyPerLine()).isEqualTo(3);
        assertThat(s.noteRate()).isEqualTo(0.4);
        assertThat(s.channelOrderPrefix()).isEqualTo("SYN");
        assertThat(new SyntheticSpec(1_000_000, null, null, null, null, null, 1L, null, null, null, null).count()).isEqualTo(SyntheticSpec.MAX_COUNT);
    }
}
