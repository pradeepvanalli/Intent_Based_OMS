package ai.intentoms.testing;

import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.pii.Tokenizer;
import ai.intentoms.platform.security.ApiKeyService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class LedgerIntegrationTest extends PostgresTestBase {
    @Autowired EventStore events;
    @Autowired TenantConfigStore config;
    @Autowired Tokenizer tokenizer;
    @Autowired ApiKeyService apiKeys;

    @Test
    void appendsInOrderWithOptimisticConcurrency() {
        String id = "ord_" + System.nanoTime();
        List<Event> first = events.append("order", id, 0, List.of(
            new NewEvent("order.captured", Map.of("channel", "web"), Actor.user("alice")),
            new NewEvent("order.validated", Map.of(), Actor.system("validator"))));
        assertThat(first).extracting(Event::sequence).containsExactly(1L, 2L);
        assertThat(events.currentVersion("order", id)).isEqualTo(2);
        assertThatThrownBy(() -> events.append("order", id, 1, List.of(new NewEvent("order.x", Map.of(), Actor.system("t")))))
            .isInstanceOf(ConflictException.class);
        events.append("order", id, new NewEvent("order.sourced", Map.of("plan", "p1"), Actor.decision("rec1")));
        List<Event> all = events.load("order", id);
        assertThat(all).hasSize(3);
        assertThat(all.get(2).actor()).isEqualTo(new Actor("decision", "rec1"));
        assertThat(all.get(0).toCloudEvent()).containsEntry("type", "ai.intentoms.order.captured").containsKey("time");
        assertThat(jdbc.sql("select count(*) from outbox where aggregate_id = :id").param("id", id).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void ledgerIsAppendOnlyAtDatabaseLevel() {
        String id = "ord_ro_" + System.nanoTime();
        events.append("order", id, new NewEvent("order.captured", Map.of(), Actor.system("t")));
        assertThatThrownBy(() -> jdbc.sql("delete from event where aggregate_id = :id").param("id", id).update())
            .hasMessageContaining("append-only");
    }

    @Test
    void concurrentAppendsNeverProduceDuplicateSequences() throws Exception {
        String id = "ord_cc_" + System.nanoTime();
        AtomicInteger ok = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 25; i++) {
                fs.add(pool.submit(() -> TenantContext.runAs(TENANT, () -> {
                    events.append("order", id, new NewEvent("order.ping", Map.of(), Actor.system("t")));
                    ok.incrementAndGet();
                })));
            }
            for (Future<?> f : fs) f.get();
        }
        assertThat(ok.get()).isEqualTo(25);
        assertThat(events.load("order", id)).extracting(Event::sequence).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 25).boxed().toList());
    }

    @Test
    void tenantConfigAndTokenizer() {
        config.set("sourcing.margin_floor", 0.12);
        assertThat(config.getDouble("sourcing.margin_floor", 0)).isEqualTo(0.12);
        assertThat(config.celTenantMap()).containsEntry("margin_floor", 0.12);
        String t1 = tokenizer.email("Alice@Example.com");
        String t2 = tokenizer.email("alice@example.com ");
        assertThat(t1).isEqualTo(t2).startsWith("em_");
        assertThat(t1).doesNotContain("alice");
    }

    @Test
    void apiKeysAuthenticateAndRevoke() {
        var issued = apiKeys.issue(TENANT, Principal.Type.AGENT, "shopbot", Set.of("agent_shopper"), Set.of("order.read", "order.create"), 2, "test");
        var p = apiKeys.authenticate(issued.plaintext()).orElseThrow();
        assertThat(p.type()).isEqualTo(Principal.Type.AGENT);
        assertThat(p.has("order.create")).isTrue();
        assertThat(p.has("refund.issue")).isFalse();
        assertThat(apiKeys.authenticate(issued.plaintext() + "x")).isEmpty();
        apiKeys.revoke(issued.keyId());
        assertThat(apiKeys.authenticate(issued.plaintext())).isEmpty();
    }
}
