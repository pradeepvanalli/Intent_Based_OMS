package ai.intentoms;

import ai.intentoms.testing.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the whole modular monolith against a real PostgreSQL to prove every module's wiring and migrations compose. */
@SpringBootTest(classes = IntentOmsApplication.class, properties = {"oms.demo.seed=true"})
@ActiveProfiles("test")
class IntentOmsApplicationTest extends PostgresTestBase {
    @Test
    void contextLoadsAndAllMigrationsApplied() {
        long tables = jdbc.sql("select count(*) from information_schema.tables where table_schema = 'public'").query(Long.class).single();
        assertThat(tables).isGreaterThan(20);
    }
}
