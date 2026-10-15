package ai.intentoms.testing.security;

import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.security.JwtService;
import ai.intentoms.platform.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login rate limiting, audit trail, logout, password change/policy, admin user creation and security headers. */
class AuthHardeningIntegrationTest extends SecurityHardeningTestBase {
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    @BeforeEach
    void anonymous() { SecurityContextHolder.clearContext(); }

    private MvcResult login(String user, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).header("X-Forwarded-For", ip + ", 10.0.0.1")
            .content(Json.write(Map.of("tenantId", TENANT, "userId", user, "password", password)))).andReturn();
    }

    private long audits(String action, String user) {
        return jdbc.sql("select count(*) from audit_log where tenant_id = :t and action = :a and object_type = 'app_user' and object_id = :u")
            .param("t", TENANT).param("a", action).param("u", user).query(Long.class).single();
    }

    private void createUser(String id, String password, String role) {
        jdbc.sql("insert into app_user(tenant_id, user_id, display_name, password_hash, roles) values (:t, :u, :u, :h, :r::jsonb) on conflict (tenant_id, user_id) do update set password_hash = excluded.password_hash")
            .param("t", TENANT).param("u", id).param("h", new BCryptPasswordEncoder().encode(password)).param("r", "[\"" + role + "\"]").update();
    }

    @Test
    void successfulAndFailedLoginsAreAuditedAndUnknownUsersLookLikeWrongPasswords() throws Exception {
        createUser("audited", "correct-horse-battery", "ops_analyst");
        long okBefore = audits("auth.login", "audited"), failBefore = audits("auth.login_failed", "audited");
        MvcResult ok = login("audited", "correct-horse-battery", "10.1.0.1");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"token\"").contains("ops_analyst");
        assertThat(login("audited", "nope", "10.1.0.1").getResponse().getStatus()).isEqualTo(401);
        assertThat(login("ghost-user", "nope", "10.1.0.1").getResponse().getStatus()).isEqualTo(401);
        assertThat(audits("auth.login", "audited")).isEqualTo(okBefore + 1);
        assertThat(audits("auth.login_failed", "audited")).isEqualTo(failBefore + 1);
        assertThat(audits("auth.login_failed", "ghost-user")).isGreaterThanOrEqualTo(1);
        String details = jdbc.sql("select details::text from audit_log where action = 'auth.login' and object_id = 'audited' order by id desc limit 1").query(String.class).single();
        assertThat(Json.readMap(details)).containsEntry("ip", "10.1.0.1").containsEntry("userId", "audited");
    }

    @Test
    void loginIsRateLimitedPerIpWith429() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(login("limit-user-" + i, "x", "10.2.0.9").getResponse().getStatus()).isEqualTo(401);
        MvcResult blocked = login("limit-user-99", "x", "10.2.0.9");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getContentAsString()).contains("rate_limited");
        // a different client IP is unaffected
        assertThat(login("limit-user-99", "x", "10.2.0.10").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void loginIsRateLimitedPerUserAcrossIps() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(login("spray-target", "x", "10.3." + i + ".1").getResponse().getStatus()).isEqualTo(401);
        assertThat(login("spray-target", "x", "10.3.200.1").getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void logoutIsNoContentWithOrWithoutToken() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
        String token = jwt.issue(new Principal(Principal.Type.USER, "bob", TENANT, Set.of(), Set.of(), 3));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        assertThat(audits("auth.logout", "bob")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void changePasswordEnforcesCurrentPasswordAndPolicy() throws Exception {
        createUser("changer", "old-password-123", "cs_rep");
        String token = jwt.issue(new Principal(Principal.Type.USER, "changer", TENANT, Set.of("cs_rep"), Set.of(), 3));
        // wrong current password
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(Map.of("currentPassword", "wrong", "newPassword", "a-perfectly-fine-new-password")))).andExpect(status().isUnauthorized());
        // too short
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(Map.of("currentPassword", "old-password-123", "newPassword", "short")))).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("Password must be at least 12 characters"));
        // ok
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(Map.of("currentPassword", "old-password-123", "newPassword", "a-perfectly-fine-new-password")))).andExpect(status().isNoContent());
        assertThat(login("changer", "old-password-123", "10.4.0.1").getResponse().getStatus()).isEqualTo(401);
        assertThat(login("changer", "a-perfectly-fine-new-password", "10.4.0.1").getResponse().getStatus()).isEqualTo(200);
        assertThat(audits("auth.password_changed", "changer")).isEqualTo(1);
        // unauthenticated
        mvc.perform(post("/api/v1/auth/change-password").contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(Map.of("currentPassword", "x", "newPassword", "y")))).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCreatesUsersSubjectToPasswordPolicy() throws Exception {
        String admin = jwt.issue(new Principal(Principal.Type.USER, "root", TENANT, Set.of("admin"), Set.of("*"), 3));
        String weak = jwt.issue(new Principal(Principal.Type.USER, "weak", TENANT, Set.of("cs_rep"), Set.of("order.read"), 3));
        mvc.perform(post("/api/v1/auth/users").header("Authorization", "Bearer " + weak).contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(Map.of("userId", "newbie", "password", "long-enough-password", "roles", Set.of("cs_rep"))))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/users").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(Map.of("userId", "newbie", "password", "tooshort", "roles", Set.of("cs_rep"))))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/auth/users").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(Map.of("userId", "newbie", "password", "long-enough-password", "roles", Set.of("cs_rep"))))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/users").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
            .content(Json.write(Map.of("userId", "newbie", "password", "long-enough-password", "roles", Set.of("cs_rep"))))).andExpect(status().isConflict());
        assertThat(login("newbie", "long-enough-password", "10.5.0.1").getResponse().getContentAsString()).contains("cs_rep");
    }

    @Test
    void securityHeadersArePresentOnEveryResponse() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(header().exists("Content-Security-Policy"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().exists("Permissions-Policy"))
            .andExpect(header().doesNotExist("Strict-Transport-Security"));
        MvcResult api = mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized()).andReturn();
        assertThat(api.getResponse().getHeader("Content-Security-Policy")).startsWith("default-src 'self'; script-src 'self'");
        MvcResult swagger = mvc.perform(get("/swagger-ui/index.html")).andReturn();
        assertThat(swagger.getResponse().getHeader("Content-Security-Policy")).contains("script-src 'self' 'unsafe-inline'");
    }
}
