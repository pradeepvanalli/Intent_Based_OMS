package ai.intentoms.simulation.synthetic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Shape of a synthetic order population (FR-SIM-05: testing new order types and peak load). Mixes are relative weights
 * (they need not sum to 1); empty pools fall back to the generator's built-in demo defaults.
 *
 * @param count             orders to generate (1..50 000)
 * @param channelMix        channel → weight, e.g. {web: 0.7, marketplace: 0.2, agent: 0.1}
 * @param orderTypeMix      order type → weight, e.g. {standard_ship: 0.9, bopis: 0.1}
 * @param skuPool           SKUs to draw lines from (prices come from the catalogue when known)
 * @param zipPool           destination ZIPs; entries may be {@code "zip"} or {@code "zip|city|state"}
 * @param intentNoteSamples free-text notes attached to a share of orders (fulfilment intent inference)
 * @param seed              random seed; the same spec and seed always produce the same requests
 * @param maxLinesPerOrder  upper bound on lines per order (default 3)
 * @param maxQtyPerLine     upper bound on quantity per line (default 3)
 * @param noteRate          share of orders that get a note (default 0.4)
 * @param channelOrderPrefix prefix for channel order ids (default "SYN")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyntheticSpec(int count, Map<String, Double> channelMix, Map<String, Double> orderTypeMix, List<String> skuPool, List<String> zipPool, List<String> intentNoteSamples,
                            Long seed, Integer maxLinesPerOrder, Integer maxQtyPerLine, Double noteRate, String channelOrderPrefix) {
    public static final int MAX_COUNT = 50_000;

    public SyntheticSpec {
        count = Math.max(1, Math.min(MAX_COUNT, count));
        channelMix = channelMix == null ? Map.of() : Map.copyOf(channelMix);
        orderTypeMix = orderTypeMix == null ? Map.of() : Map.copyOf(orderTypeMix);
        skuPool = skuPool == null ? List.of() : List.copyOf(skuPool);
        zipPool = zipPool == null ? List.of() : List.copyOf(zipPool);
        intentNoteSamples = intentNoteSamples == null ? List.of() : List.copyOf(intentNoteSamples);
        if (seed == null) seed = 42L;
        if (maxLinesPerOrder == null || maxLinesPerOrder <= 0) maxLinesPerOrder = 3;
        if (maxQtyPerLine == null || maxQtyPerLine <= 0) maxQtyPerLine = 3;
        if (noteRate == null || noteRate < 0 || noteRate > 1) noteRate = 0.4;
        if (channelOrderPrefix == null || channelOrderPrefix.isBlank()) channelOrderPrefix = "SYN";
    }

    public static SyntheticSpec of(int count, long seed) { return new SyntheticSpec(count, null, null, null, null, null, seed, null, null, null, null); }
}
