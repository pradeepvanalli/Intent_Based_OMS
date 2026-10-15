package ai.intentoms.testing;

import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

/**
 * Base for integration tests. Database resolution order (one database per test JVM so modules can run in parallel
 * without Flyway history clashes):
 * <ol>
 *   <li>{@code OMS_TEST_JDBC_URL} (+ {@code OMS_TEST_JDBC_USER}/{@code OMS_TEST_JDBC_PASSWORD}) — use that database as is.</li>
 *   <li>The project's compose PostgreSQL on localhost:5440 (user/pass oms/oms) when reachable — a throwaway database
 *       {@code oms_test_<id>} is created and dropped on JVM exit.</li>
 *   <li>A Testcontainers PostgreSQL 16 container (requires Docker).</li>
 * </ol>
 * Tenant "test" is bound and an admin principal is authenticated before each test.
 */
public abstract class PostgresTestBase {
    public static final String TENANT = "test";
    private static final String URL;
    private static final String USER;
    private static final String PASSWORD;

    static {
        String env = System.getenv("OMS_TEST_JDBC_URL");
        if (env != null) {
            URL = env;
            USER = System.getenv().getOrDefault("OMS_TEST_JDBC_USER", "oms");
            PASSWORD = System.getenv().getOrDefault("OMS_TEST_JDBC_PASSWORD", "oms");
        } else {
            String local = tryLocalCompose();
            if (local != null) {
                URL = local;
                USER = "oms";
                PASSWORD = "oms";
            } else {
                PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine").withReuse(true);
                pg.start();
                URL = pg.getJdbcUrl();
                USER = pg.getUsername();
                PASSWORD = pg.getPassword();
            }
        }
    }

    private static String tryLocalCompose() {
        String admin = "jdbc:postgresql://localhost:5440/oms?connectTimeout=2&loginTimeout=2";
        String db = "oms_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(admin, "oms", "oms"); Statement s = c.createStatement()) {
            s.execute("create database " + db);
        } catch (Exception e) {
            return null;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (Connection c = DriverManager.getConnection(admin, "oms", "oms"); Statement s = c.createStatement()) {
                s.execute("drop database if exists " + db + " with (force)");
            } catch (Exception ignored) { }
        }));
        return "jdbc:postgresql://localhost:5440/" + db;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> URL);
        r.add("spring.datasource.username", () -> USER);
        r.add("spring.datasource.password", () -> PASSWORD);
        r.add("oms.outbox.enabled", () -> "false");
        r.add("oms.security.dev-users", () -> "admin:admin:test:admin");
    }

    @Autowired protected JdbcClient jdbc;

    @BeforeEach
    void bindTenant() {
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", TENANT).update();
        TenantContext.set(TENANT);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(
            new Principal(Principal.Type.USER, "tester", TENANT, Set.of("admin"), Set.of("*"), 3)));
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /** Switch the acting principal (for permission tests). */
    protected void actAs(String id, Set<String> permissions) {
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(
            new Principal(Principal.Type.USER, id, TENANT, Set.of(), permissions, 3)));
    }
}
