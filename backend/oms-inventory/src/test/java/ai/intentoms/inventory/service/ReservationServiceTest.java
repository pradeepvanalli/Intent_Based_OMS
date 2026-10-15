package ai.intentoms.inventory.service;

import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.model.Reservation;
import ai.intentoms.inventory.model.ReservationLine;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-INV-02 / NFR-06: row-locked reservations, atomic multi-line reserve, TTL expiry, lifecycle events.
 * The scheduled sweep is disabled so {@code expireDue()} is exercised deterministically under the test clock.
 */
@SpringBootTest(classes = TestApplication.class, properties = "oms.inventory.expiry-sweep-enabled=false")
@ActiveProfiles("test")
class ReservationServiceTest extends PostgresTestBase {
    @Autowired ReservationService reservations;
    @Autowired InventoryService inventory;
    @Autowired EventStore events;
    @Autowired TimeSource time;

    @AfterEach
    void resetClock() { time.reset(); }

    private static String sku() { return "sku-" + UUID.randomUUID().toString().substring(0, 8); }
    private static String ref() { return "ref-" + UUID.randomUUID().toString().substring(0, 8); }

    @Test
    void reserveBeyondAtpFailsWithDetails() {
        String sku = sku();
        inventory.upsert(List.of(InventoryPosition.onHand(sku, "dc1", 5)), "wms");
        reservations.reserve(sku, "dc1", 3, "cart", ref(), Duration.ofMinutes(15));

        assertThatThrownBy(() -> reservations.reserve(sku, "dc1", 3, "cart", ref(), Duration.ofMinutes(15)))
            .isInstanceOf(ConflictException.class)
            .hasMessage("insufficient_atp")
            .satisfies(e -> assertThat(((ConflictException) e).details()).containsEntry("available", 2).containsEntry("requested", 3).containsEntry("sku", sku));
        assertThatThrownBy(() -> reservations.reserve(sku, "nowhere", 1, "cart", ref(), Duration.ofMinutes(15)))
            .isInstanceOf(ConflictException.class).hasMessage("insufficient_atp");

        Atp atp = inventory.atp(sku, "dc1");
        assertThat(atp.reserved()).isEqualTo(3);
        assertThat(atp.available()).isEqualTo(2);
    }

    @Test
    void concurrentReservesNeverExceedOnHand() throws Exception {
        String sku = sku();
        inventory.upsert(List.of(InventoryPosition.onHand(sku, "dc1", 10)), "wms");
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                int qty = i % 2 == 0 ? 10 : 1;
                futures.add(pool.submit(() -> {
                    TenantContext.set(TENANT);
                    try {
                        start.await();
                        reservations.reserve(sku, "dc1", qty, "checkout", ref(), Duration.ofMinutes(5));
                        reserved.addAndGet(qty);
                    } catch (ConflictException e) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        TenantContext.clear();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get();
        }
        assertThat(reserved.get()).isEqualTo(10);
        assertThat(rejected.get()).isGreaterThan(0);
        Atp atp = inventory.atp(sku, "dc1");
        assertThat(atp.reserved()).isEqualTo(10);
        assertThat(atp.available()).isZero();
        Integer sum = jdbc.sql("select coalesce(sum(qty), 0) from inventory_reservation where tenant_id = :t and sku = :s and status = 'ACTIVE'")
            .param("t", TENANT).param("s", sku).query(Integer.class).single();
        assertThat(sum).isLessThanOrEqualTo(10);
    }

    @Test
    void reserveAllIsAtomic() {
        String a = sku(), b = sku(), ref = ref();
        inventory.upsert(List.of(InventoryPosition.onHand(a, "dc1", 5), InventoryPosition.onHand(b, "dc1", 2)), "wms");

        assertThatThrownBy(() -> reservations.reserveAll(List.of(new ReservationLine(b, "dc1", 5), new ReservationLine(a, "dc1", 3)), "checkout", ref, Duration.ofMinutes(5)))
            .isInstanceOf(ConflictException.class)
            .satisfies(e -> assertThat(((ConflictException) e).details()).containsEntry("sku", b).containsEntry("available", 2).containsEntry("requested", 5));
        assertThat(reservations.forRef(ref)).isEmpty();
        assertThat(inventory.atp(a, "dc1").reserved()).isZero();

        List<Reservation> ok = reservations.reserveAll(List.of(new ReservationLine(a, "dc1", 3), new ReservationLine(b, "dc1", 2), new ReservationLine(a, "dc1", 2)), "checkout", ref, Duration.ofMinutes(5));
        assertThat(ok).hasSize(3).allMatch(Reservation::active);
        assertThat(inventory.atp(a, "dc1").available()).isZero();
        assertThat(inventory.atp(b, "dc1").available()).isZero();
        assertThat(inventory.networkAtp(List.of(a, b))).containsEntry(a, 0).containsEntry(b, 0);
    }

    @Test
    void ttlExpiryReturnsStockAndWritesEvent() {
        String sku = sku(), ref = ref();
        inventory.upsert(List.of(InventoryPosition.onHand(sku, "dc1", 4)), "wms");
        Reservation r = reservations.reserve(sku, "dc1", 4, "cart", ref, Duration.ofMinutes(15));
        assertThat(r.expiresAt()).isNotNull();
        assertThat(inventory.atp(sku, "dc1").available()).isZero();

        String orderSku = sku();
        inventory.upsert(List.of(InventoryPosition.onHand(orderSku, "dc1", 1)), "wms");
        Reservation forever = reservations.reserve(orderSku, "dc1", 1, "order", ref(), Duration.ofSeconds(1));
        assertThat(forever.expiresAt()).as("order reservations never expire").isNull();

        Instant later = time.now().plus(Duration.ofHours(1));
        time.override(Clock.fixed(later, ZoneOffset.UTC));
        int n = reservations.expireDue();
        assertThat(n).as("at least the cart hold under test expired").isGreaterThanOrEqualTo(1);
        assertThat(reservations.get(r.id()).status()).isEqualTo(Reservation.EXPIRED);
        assertThat(inventory.atp(sku, "dc1").available()).isEqualTo(4);
        assertThat(events.load("reservation", r.id())).extracting(Event::type).containsExactly("reservation.created", "reservation.expired");
        assertThat(reservations.release(r.id(), "late")).as("release after expiry is a no-op").extracting(Reservation::status).isEqualTo(Reservation.EXPIRED);
    }

    @Test
    void convertAndConsumeLifecycleIsRecordedInLedger() {
        String sku = sku(), cart = ref(), order = "ord-" + ref();
        inventory.upsert(List.of(InventoryPosition.onHand(sku, "dc1", 10)), "wms");
        Reservation r = reservations.reserve(sku, "dc1", 4, "cart", cart, Duration.ofMinutes(15));

        Reservation converted = reservations.convert(r.id(), order);
        assertThat(converted.source()).isEqualTo("order");
        assertThat(converted.expiresAt()).isNull();
        assertThat(converted.status()).isEqualTo(Reservation.ACTIVE);
        assertThat(reservations.forRef(order)).extracting(Reservation::id).containsExactly(r.id());
        assertThat(reservations.forRef(cart)).extracting(Reservation::id).containsExactly(r.id());
        assertThat(reservations.convert(r.id(), order)).as("idempotent").isEqualTo(converted);
        assertThatThrownBy(() -> reservations.convert(r.id(), "other-order")).isInstanceOf(ConflictException.class);
        assertThat(inventory.atp(sku, "dc1").available()).isEqualTo(6);

        Reservation consumed = reservations.consume(r.id());
        assertThat(consumed.status()).isEqualTo(Reservation.CONSUMED);
        Atp after = inventory.atp(sku, "dc1");
        assertThat(after.onHand()).isEqualTo(6);
        assertThat(after.reserved()).isZero();
        assertThat(after.available()).isEqualTo(6);
        assertThat(reservations.consume(r.id())).isEqualTo(consumed);
        assertThatThrownBy(() -> reservations.release(r.id(), "too late")).isInstanceOf(ConflictException.class);

        List<Event> ledger = events.load("reservation", r.id());
        assertThat(ledger).extracting(Event::type).containsExactly("reservation.created", "reservation.converted", "reservation.consumed");
        assertThat(ledger.get(1).payload()).containsEntry("orderId", order).containsEntry("sku", sku).containsEntry("nodeId", "dc1").containsEntry("qty", 4).containsEntry("refId", cart);
        assertThat(ledger.get(2).payload()).containsEntry("onHandAfter", 6).containsEntry("orderId", order);
    }

    @Test
    void releaseReturnsStockOnce() {
        String sku = sku();
        inventory.upsert(List.of(InventoryPosition.onHand(sku, "dc1", 2)), "wms");
        Reservation r = reservations.reserve(sku, "dc1", 2, "cart", ref(), Duration.ofMinutes(15));
        assertThat(reservations.release(r.id(), "customer removed item").status()).isEqualTo(Reservation.RELEASED);
        assertThat(reservations.release(r.id(), "again").status()).isEqualTo(Reservation.RELEASED);
        assertThat(inventory.atp(sku, "dc1").available()).isEqualTo(2);
        List<Event> ledger = events.load("reservation", r.id());
        assertThat(ledger).extracting(Event::type).containsExactly("reservation.created", "reservation.released");
        assertThat(ledger.get(1).payload()).containsEntry("reason", "customer removed item");
    }
}
