package ai.intentoms.app.admin;

import ai.intentoms.IntentOmsApplication;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.security.JwtService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.Roles;
import ai.intentoms.testing.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * D-1 (audit 3, the N3 bypass): nobody can grant a role or permission they do not hold. Through the real HTTP stack
 * (authentication filter, permission aspect, controllers), as the audit reproduced it: a tenant admin minting a
 * {@code platform_engineer} API key or user is refused, an integration key of type SYSTEM is not a platform operator, the
 * global kill switch and the tenant listing stay platform-only, a strategy lead cannot hand out admin, and an agent
 * administrator cannot give an agent tools it does not hold itself.
 */
@SpringBootTest(classes = IntentOmsApplication.class, properties = {"oms.demo.seed=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CredentialGrantIntegrationTest extends PostgresTestBase {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    @BeforeEach
    void anonymous() { SecurityContextHolder.clearContext(); }

    private String token(String id, String tenant, String role) {
        return jwt.issue(new Principal(Principal.Type.USER, id, tenant, Set.of(role), Roles.permissionsFor(Set.of(role)), 3));
    }

    private MvcResult call(MockHttpServletRequestBuilder req, String bearer, Object body) throws Exception {
        if (bearer != null) req = req.header("Authorization", "Bearer " + bearer);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
        return mvc.perform(req).andReturn();
    }

    private MvcResult withKey(MockHttpServletRequestBuilder req, String apiKey, Object body) throws Exception {
        req = req.header("X-Api-Key", apiKey);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
        return mvc.perform(req).andReturn();
    }

    private static int status(MvcResult r) { return r.getResponse().getStatus(); }

    private static String text(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }

    private static String apiKey(MvcResult r) throws Exception { return String.valueOf(Json.readMap(text(r)).get("apiKey")); }

    private static String uniq(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8); }

    @Test
    void aTenantAdminCannotMintAPlatformCredentialAndCrossTenantEndpointsStayPlatformOnly() throws Exception {
        String admin = token("root", TENANT, "admin");

        // The audit's repro: the demo admin mints a platform_engineer key (and the same through users).
        MvcResult key = call(post("/api/v1/auth/api-keys"), admin, Map.of("subjectId", uniq("escalate"), "roles", List.of("platform_engineer")));
        assertThat(status(key)).isEqualTo(403);
        assertThat(text(key)).contains("Only a platform operator may grant role platform_engineer");
        assertThat(status(call(post("/api/v1/auth/users"), admin, Map.of("userId", uniq("plat"), "password", "a-long-enough-password", "roles", List.of("platform_engineer")))))
            .isEqualTo(403);
        assertThat(status(call(post("/api/v1/auth/api-keys"), admin, Map.of("subjectId", uniq("sys"), "type", "SYSTEM", "roles", List.of("system"))))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/auth/api-keys"), admin, Map.of("subjectId", uniq("dec"), "type", "DECISION", "roles", List.of())))).isEqualTo(422);

        // An integration key of type SYSTEM is still allowed, but it is a tenant integration, not a platform operator.
        MvcResult integration = call(post("/api/v1/auth/api-keys"), admin, Map.of("subjectId", uniq("erp"), "type", "SYSTEM", "roles", List.of("admin")));
        assertThat(status(integration)).isEqualTo(200);
        String sysKey = apiKey(integration);
        assertThat(status(withKey(get("/api/v1/admin/tenants"), sysKey, null))).isEqualTo(403);
        MvcResult sysSwitch = withKey(get("/api/v1/learning/kill-switch"), sysKey, null);
        assertThat(status(sysSwitch)).isEqualTo(200);
        assertThat(Json.readMap(text(sysSwitch))).containsEntry("canSetGlobal", false);
        assertThat(status(withKey(post("/api/v1/learning/kill-switch"), sysKey, Map.of("scope", "global", "enabled", false)))).isEqualTo(403);

        // The tenant admin's own session: no cross-tenant listing, no global switch.
        assertThat(status(call(get("/api/v1/admin/tenants"), admin, null))).isEqualTo(403);
        assertThat(Json.readMap(text(call(get("/api/v1/learning/kill-switch"), admin, null)))).containsEntry("canSetGlobal", false);

        // A platform operator still mints platform credentials, and they work across tenants.
        String platform = token("plat", TENANT, "platform_engineer");
        MvcResult platKey = call(post("/api/v1/auth/api-keys"), platform, Map.of("subjectId", uniq("ops"), "roles", List.of("platform_engineer")));
        assertThat(status(platKey)).isEqualTo(200);
        assertThat(status(withKey(get("/api/v1/admin/tenants"), apiKey(platKey), null))).isEqualTo(200);
        assertThat(Json.readMap(text(withKey(get("/api/v1/learning/kill-switch"), apiKey(platKey), null)))).containsEntry("canSetGlobal", true);
    }

    @Test
    void nobodyGrantsARoleOrPermissionTheyDoNotHold() throws Exception {
        String lead = token("lead", TENANT, "strategy_lead"); // config.admin, but not "*"
        MvcResult toAdmin = call(post("/api/v1/auth/users"), lead, Map.of("userId", uniq("boss"), "password", "a-long-enough-password", "roles", List.of("admin")));
        assertThat(status(toAdmin)).isEqualTo(403);
        assertThat(text(toAdmin)).contains("role admin needs permission *");
        MvcResult toCs = call(post("/api/v1/auth/users"), lead, Map.of("userId", uniq("cs"), "password", "a-long-enough-password", "roles", List.of("cs_rep")));
        assertThat(status(toCs)).isEqualTo(403);
        assertThat(text(toCs)).contains("refund.issue");
        assertThat(status(call(post("/api/v1/auth/users"), lead, Map.of("userId", uniq("peer"), "password", "a-long-enough-password", "roles", List.of("strategy_lead")))))
            .isEqualTo(201);
        assertThat(status(call(post("/api/v1/auth/api-keys"), lead, Map.of("subjectId", uniq("k"), "permissions", List.of("refund.issue"))))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/auth/api-keys"), lead, Map.of("subjectId", uniq("k"), "permissions", List.of("*"))))).isEqualTo(403);
        assertThat(status(call(post("/api/v1/auth/api-keys"), lead, Map.of("subjectId", uniq("k"), "permissions", List.of("order.read"))))).isEqualTo(200);

        // An agent administrator without order.cancel cannot register an agent that cancels orders.
        String risk = token("risk", TENANT, "risk_analyst");
        String agent = uniq("agt").toLowerCase();
        MvcResult cancels = call(post("/api/v1/agents"), risk, Map.of("id", agent, "owner", "risk", "protocol", "mcp", "trustTier", 1,
            "limits", Map.of("allowedActions", List.of("order_get", "order_cancel"))));
        assertThat(status(cancels)).isEqualTo(403);
        assertThat(text(cancels)).contains("order.cancel");
        assertThat(status(call(post("/api/v1/agents"), risk, Map.of("id", agent, "owner", "risk", "protocol", "mcp", "trustTier", 1,
            "limits", Map.of("allowedActions", List.of("order_get")))))).isEqualTo(201);
        assertThat(status(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/agents/" + agent), risk,
            Map.of("limits", Map.of("allowedActions", List.of("order_get", "order_cancel")))))).isEqualTo(403);
    }

    @Test
    void aKeyIsRevokedOnlyFromItsOwnTenant() throws Exception {
        jdbc.sql("insert into tenant(tenant_id, name) values ('other_t', 'other_t') on conflict do nothing").update();
        MvcResult issued = call(post("/api/v1/auth/api-keys"), token("root", TENANT, "admin"), Map.of("subjectId", uniq("svc"), "roles", List.of("ops_analyst")));
        String keyId = String.valueOf(Json.readMap(text(issued)).get("keyId"));
        String key = apiKey(issued);

        assertThat(status(call(delete("/api/v1/auth/api-keys/" + keyId), token("intruder", "other_t", "admin"), null))).isEqualTo(404);
        assertThat(status(withKey(get("/api/v1/auth/me"), key, null))).isEqualTo(200);
        assertThat(status(call(delete("/api/v1/auth/api-keys/" + keyId), token("root", TENANT, "admin"), null))).isEqualTo(200);
        assertThat(status(withKey(get("/api/v1/auth/me"), key, null))).isEqualTo(401);
    }
}
