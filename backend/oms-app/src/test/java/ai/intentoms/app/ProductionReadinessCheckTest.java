package ai.intentoms.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure environment checks; no Spring context or database. */
class ProductionReadinessCheckTest {

    private static MockEnvironment devDefaults() {
        return new MockEnvironment()
            .withProperty("oms.security.jwt-secret", "change-me-in-production-please-32chars")
            .withProperty("oms.security.dev-users", "admin:admin:demo:admin,ops:ops:demo:ops_analyst")
            .withProperty("oms.demo.seed", "true")
            .withProperty("oms.agent.demo-expose-secrets", "true")
            .withProperty("oms.security.cors.allowed-origins", "*")
            .withProperty("management.endpoints.web.exposure.include", "health,info,prometheus,metrics");
    }

    private static MockEnvironment prodSafe() {
        return new MockEnvironment()
            .withProperty("oms.security.jwt-secret", "a-very-long-random-secret-value-with-48-characters!!")
            .withProperty("oms.security.master-key", "6g2mFhX0kq0H4y8uZ9qVt1pWw3xC5rJd7nB2sL4vK8M=")
            .withProperty("oms.security.dev-users", "")
            .withProperty("oms.demo.seed", "false")
            .withProperty("oms.agent.demo-expose-secrets", "false")
            .withProperty("oms.security.cors.allowed-origins", "https://oms.example.com,https://admin.example.com")
            .withProperty("management.endpoints.web.exposure.include", "health,prometheus");
    }

    @Test
    void developmentDefaultsProduceEveryViolation() {
        List<String> v = ProductionReadinessCheck.violations(devDefaults());
        assertThat(v).hasSize(7);
        assertThat(String.join("\n", v))
            .contains("jwt-secret is the default")
            .contains("dev-users must be empty")
            .contains("oms.demo.seed must be false")
            .contains("demo-expose-secrets must be false")
            .contains("cors.allowed-origins must list explicit")
            .contains("exposes [info, metrics]")
            .contains("master-key is not set");
    }

    @Test
    void productionSafeEnvironmentHasNoViolations() {
        assertThat(ProductionReadinessCheck.violations(prodSafe())).isEmpty();
    }

    @Test
    void shortSecretAndWildcardOriginAreRejected() {
        MockEnvironment env = prodSafe().withProperty("oms.security.jwt-secret", "short").withProperty("oms.security.cors.allowed-origins", "https://*.example.com");
        List<String> v = ProductionReadinessCheck.violations(env);
        assertThat(v).hasSize(2);
        assertThat(v.get(0)).contains("too short (5 chars");
        assertThat(v.get(1)).contains("allowed-origins");
    }

    @Test
    void secretFileIsHonoured() throws Exception {
        Path f = Files.createTempFile("jwt", ".secret");
        Files.writeString(f, "file-provided-secret-that-is-definitely-long-enough\n");
        MockEnvironment env = prodSafe().withProperty("oms.security.jwt-secret", "change-me-in-production-please-32chars").withProperty("oms.security.jwt-secret-file", f.toString());
        assertThat(ProductionReadinessCheck.violations(env)).isEmpty();
        MockEnvironment missing = prodSafe().withProperty("oms.security.jwt-secret-file", "/nonexistent/jwt.secret");
        assertThat(ProductionReadinessCheck.violations(missing)).singleElement().asString().contains("cannot be read");
    }

    @Test
    void onlyEnforcedForProdProfileOrExplicitFlag() {
        MockEnvironment dev = devDefaults();
        assertThat(ProductionReadinessCheck.enforced(dev)).isFalse();
        dev.setActiveProfiles("prod");
        assertThat(ProductionReadinessCheck.enforced(dev)).isTrue();
        MockEnvironment flagged = devDefaults().withProperty("oms.security.enforce-production", "true");
        assertThat(ProductionReadinessCheck.enforced(flagged)).isTrue();
    }

    @Test
    void listenerThrowsListingEveryViolation() {
        MockEnvironment env = devDefaults();
        env.setActiveProfiles("prod");
        var event = new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(), new SpringApplication(), new String[0], env);
        assertThatThrownBy(() -> new ProductionReadinessCheck().onApplicationEvent(event))
            .isInstanceOf(ProductionReadinessCheck.NotProductionReadyException.class)
            .hasMessageContaining("7 violation(s)")
            .hasMessageContaining("HARDENING.md");
        // not enforced without the profile
        var devEvent = new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(), new SpringApplication(), new String[0], devDefaults());
        new ProductionReadinessCheck().onApplicationEvent(devEvent);
    }
}
