package ai.intentoms.integrations;

import ai.intentoms.finance.model.Money;
import ai.intentoms.orchestration.seed.DemoSeeder;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester;
import org.springframework.graphql.test.tester.GraphQlTester;
import io.micrometer.context.ContextSnapshotFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Integration base for oms-integrations: a fresh tenant per test seeded with the demo configuration (same loaders as
 * {@link DemoSeeder}), workflow pollers off, and every outbound provider pointed at one shared WireMock server. The
 * SendGrid / Twilio / EasyPost credentials are dummies so the adapters are "configured" and call WireMock; the
 * address provider stays {@code mock} so capture never depends on a stub (Smarty has its own standalone test).
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    "oms.workflow.enabled=false", "oms.monitoring.enabled=false", "oms.demo.seed=false", "oms.config-dir=../../config",
    "oms.integrations.notify.enabled=true",
    "oms.integrations.notify.sendgrid.api-key=SG.test-key", "oms.integrations.notify.sendgrid.from-email=orders@test.example",
    "oms.integrations.notify.twilio.account-sid=ACtest", "oms.integrations.notify.twilio.auth-token=tok", "oms.integrations.notify.twilio.from=+15550000000",
    "oms.integrations.carrier.provider=easypost", "oms.integrations.carrier.easypost.api-key=EZTKtest",
    "oms.integrations.wms.provider=http", "oms.integrations.wms.http.retries=3", "oms.integrations.wms.http.backoff=PT0.05S"})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")
@AutoConfigureMockMvc
public abstract class IntegrationsTestBase extends PostgresTestBase {
    protected static final Actor SYSTEM = Actor.system("test");
    protected static final WireMockServer WM;

    static {
        WM = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        WM.start();
        Runtime.getRuntime().addShutdownHook(new Thread(WM::stop));
    }

    @DynamicPropertySource
    static void providerUrls(DynamicPropertyRegistry r) {
        r.add("oms.integrations.notify.sendgrid.base-url", WM::baseUrl);
        r.add("oms.integrations.notify.twilio.base-url", WM::baseUrl);
        r.add("oms.integrations.carrier.easypost.base-url", WM::baseUrl);
        r.add("oms.integrations.address.smarty.base-url", WM::baseUrl);
    }

    @Autowired protected DemoSeeder seeder;
    @Autowired protected OrderService orders;
    @Autowired protected TenantConfigStore config;
    @Autowired protected TimeSource time;
    @Autowired protected ExecutionGraphQlService graphQlService;

    protected String tenant;

    /**
     * In-process GraphQL tester. Over HTTP Spring for GraphQL captures the request thread's ThreadLocals (security context,
     * tenant) into a context snapshot that data fetchers restore; the in-process tester has no HTTP layer, so the wrapper
     * captures the test thread's ThreadLocals at subscription time the same way.
     */
    protected GraphQlTester graphql() {
        return ExecutionGraphQlServiceTester.create(request -> graphQlService.execute(request)
            .contextWrite(ctx -> ContextSnapshotFactory.builder().build().captureAll().updateContext(ctx)));
    }

    @BeforeEach
    void freshTenant() {
        WM.resetAll();
        tenant = "int_" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        bind();
        seeder.seedConfig();
        seeder.seedInventory();
    }

    @AfterEach
    void resetClock() { time.reset(); }

    /** Binds the test tenant and an admin principal (MockMvc requests clear both at the end of the request). */
    protected void bind() {
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.USER, "tester", tenant, Set.of("admin"), Set.of("*"), 3)));
    }

    protected void actAs(String id, Principal.Type type, Set<String> permissions) {
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(type, id, tenant, Set.of(), permissions, 3)));
    }

    protected static CaptureRequest.LineInput line(String id, String sku, int qty, String price) { return new CaptureRequest.LineInput(id, sku, null, qty, Money.usd(price), null, null, null); }

    /** Web order: hoodie ×2 + sneakers to Chicago, card ok. Customer has e-mail and phone so both channels can be exercised. */
    protected CaptureRequest webOrder(String channelOrderId) {
        return new CaptureRequest("web", channelOrderId, "standard_ship", "cust-2001",
            new CaptureRequest.CustomerInput("cust-2001", "Dana Whitfield", "dana@example.com", "+15551234567", "gold", true, 34, Map.of("split_acceptance_rate", 0.2)),
            List.of(line("1", "HOOD-GRY-M", 2, "68.00"), line("2", "SNK-RUN-10", 1, "145.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Dana Whitfield", "233 S Wacker Dr", null, "Chicago", "IL", "60606", "US", "+15551234567", "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_ok_1", Money.usd("320.00"), Map.of())), List.of(), List.of(), "ground", null, "USD", Map.of(), null);
    }

    protected Order capture(CaptureRequest req) { return orders.capture(req, "test-" + req.channelOrderId()); }
}
