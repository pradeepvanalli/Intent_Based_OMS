package ai.intentoms.finance;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

/**
 * Finance integration tests run under a fresh tenant per test method so rolling-window spend, tariff rows and
 * config written by one test (or a previous run against the reused container) can never leak into another.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
public abstract class FinanceTestBase extends PostgresTestBase {
    protected String tenant;

    @BeforeEach
    void freshTenant() {
        tenant = "fin_" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(
            new Principal(Principal.Type.USER, "tester", tenant, Set.of("admin"), Set.of("*"), 3)));
    }
}
