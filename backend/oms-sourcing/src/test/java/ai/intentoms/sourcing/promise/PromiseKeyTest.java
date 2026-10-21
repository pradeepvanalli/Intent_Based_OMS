package ai.intentoms.sourcing.promise;

import ai.intentoms.sourcing.model.PromiseRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D5-3 (CX-02, audit 5): the cache key must include {@code channel} — the served buffer is channel-dependent
 * ({@code DefaultPromiseService.buffer(channel)} -&gt; {@code PromiseBufferLearner.segment(channel)}), so two
 * requests that differ only in channel must never collide in the promise cache.
 */
class PromiseKeyTest {
    private static PromiseRequest req(String channel) {
        return new PromiseRequest(List.of(new PromiseRequest.PromiseLine("TEE-BLK-M", 2)), "10001", "NY", channel, "ground");
    }

    @Test
    void requestsIdenticalExceptChannelProduceDifferentKeys() {
        String web = PromiseKey.of("acme", req("web"));
        String marketplace = PromiseKey.of("acme", req("marketplace"));
        assertThat(web).isNotEqualTo(marketplace);
    }

    @Test
    void sameChannelStillProducesTheSameKey() {
        assertThat(PromiseKey.of("acme", req("web"))).isEqualTo(PromiseKey.of("acme", req("web")));
    }

    @Test
    void channelIsCaseInsensitiveLikeShipMethod() {
        assertThat(PromiseKey.of("acme", req("WEB"))).isEqualTo(PromiseKey.of("acme", req("web")));
    }
}
