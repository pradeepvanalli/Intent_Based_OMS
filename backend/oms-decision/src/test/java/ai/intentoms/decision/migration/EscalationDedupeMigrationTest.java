package ai.intentoms.decision.migration;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upgrade path of the escalation de-duplication: a database that still holds the duplicate OPEN stuck-order
 * escalations from before V402/V403 (one per hourly re-diagnosis) must migrate. V402's partial unique index covers OPEN
 * rows with {@code dedupe = true}, so V403 has to fold the duplicates before it flags the decision; flagging first is
 * what failed on a real database. The migration's own statements are run here against rows shaped like that history.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class EscalationDedupeMigrationTest extends PostgresTestBase {

    @Test
    void v403FoldsExistingDuplicatesBeforeFlaggingTheDecision() throws Exception {
        String order = "ord_" + Ids.shortId();
        List<String> ids = List.of("esc_" + Ids.shortId(), "esc_" + Ids.shortId(), "esc_" + Ids.shortId());
        for (int i = 0; i < ids.size(); i++) {
            jdbc.sql("""
                insert into decision_escalation(id, tenant_id, record_id, decision, status, task_id, created_at, subject_type, subject_id, dedupe)
                values (:id, :t, :rec, 'stuck_order_diagnosis', 'OPEN', :task, now() + (:i * interval '1 second'), 'order', :o, false)
                """).param("id", ids.get(i)).param("t", TENANT).param("rec", "rec_" + i).param("task", "task_" + i).param("i", i).param("o", order).update();
        }

        // Flagging first, as the migration once did, is refused by the unique index while the duplicates are still OPEN.
        assertThatThrownBy(() -> jdbc.sql("update decision_escalation set dedupe = true where decision = 'stuck_order_diagnosis' and subject_id = :o").param("o", order).update())
            .isInstanceOf(DataIntegrityViolationException.class);

        // The migration's statements, run as Flyway would (its comment lines dropped first: they may contain semicolons).
        String sql = new String(getClass().getResourceAsStream("/db/migration/V403__stuck_order_escalation_dedupe.sql").readAllBytes(), StandardCharsets.UTF_8)
            .lines().filter(l -> !l.strip().startsWith("--")).collect(java.util.stream.Collectors.joining("\n"));
        for (String statement : sql.split(";")) if (!statement.strip().isEmpty()) jdbc.sql(statement).update();

        List<Map<String, Object>> rows = jdbc.sql("select id, status, task_id, dedupe from decision_escalation where subject_id = :o order by created_at").param("o", order).query().listOfRows();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("id", ids.get(0)).containsEntry("status", "OPEN").containsEntry("task_id", "task_0");
        assertThat(rows.subList(1, 3)).allSatisfy(r -> assertThat(r).containsEntry("status", "LINKED").containsEntry("task_id", "task_0"));
        assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("dedupe", true));
    }
}
