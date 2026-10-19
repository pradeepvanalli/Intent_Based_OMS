package ai.intentoms.app.admin;

import ai.intentoms.IntentOmsApplication;
import ai.intentoms.platform.api.AdminController;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.testing.PostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A tenant admin holds every permission in their tenant ("*") but is not a platform operator: no cross-tenant listing. */
@SpringBootTest(classes = IntentOmsApplication.class, properties = {"oms.demo.seed=false"})
@ActiveProfiles("test")
class TenantAdminAccessIntegrationTest extends PostgresTestBase {
    @Autowired AdminController admin;

    private void as(String user, String role) {
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.USER, user, TENANT, Set.of(role), Set.of("*"), 3)));
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void onlyAPlatformOperatorListsTenantsWhileEveryoneSeesTheirOwn() {
        as("admin", "admin");
        assertThatThrownBy(() -> admin.tenants()).isInstanceOf(ForbiddenException.class).hasMessageContaining("platform operator");
        assertThat(admin.currentTenant()).containsEntry("tenant_id", TENANT);

        as("platform", "platform_engineer");
        assertThat(admin.tenants()).anySatisfy(t -> assertThat(t.get("tenant_id")).isEqualTo(TENANT));
    }
}
