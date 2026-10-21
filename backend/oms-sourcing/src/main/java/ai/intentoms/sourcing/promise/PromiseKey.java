package ai.intentoms.sourcing.promise;

import ai.intentoms.sourcing.model.PromiseRequest;
import ai.intentoms.sourcing.rates.ZipGeo;

import java.util.Locale;
import java.util.TreeMap;

/** Cache key: {@code tenant|sku:qty,sku:qty (sorted)|zip3 or state|method|channel}. */
public final class PromiseKey {
    private PromiseKey() {}

    public static String of(String tenant, PromiseRequest r) {
        TreeMap<String, Integer> lines = new TreeMap<>();
        for (PromiseRequest.PromiseLine l : r.lines()) lines.merge(l.sku(), l.qty(), Integer::sum);
        StringBuilder sb = new StringBuilder(64).append(tenant).append('|');
        boolean first = true;
        for (var e : lines.entrySet()) { if (!first) sb.append(','); sb.append(e.getKey()).append(':').append(e.getValue()); first = false; }
        String z3 = ZipGeo.zip3(r.destinationZip());
        sb.append('|').append(z3 != null ? z3 : (r.destinationState() == null ? "?" : r.destinationState()));
        sb.append('|').append(r.shipMethod().toLowerCase(Locale.ROOT));
        // D5-3 (CX-02): the served buffer is channel-dependent (DefaultPromiseService.buffer(channel) ->
        // PromiseBufferLearner.segment(channel)), so two requests differing only in channel must not share a cache
        // entry within the TTL — one channel's shopper was being served another channel's buffered promise date.
        sb.append('|').append(r.channel() == null ? "?" : r.channel().toLowerCase(Locale.ROOT));
        return sb.toString();
    }
}
