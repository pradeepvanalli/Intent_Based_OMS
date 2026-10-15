package ai.intentoms.workflow;

import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Shared context for workflow module integration tests: pollers off, steps driven through {@code WorkflowWorker.runOnce()}. */
@SpringBootTest(classes = TestApplication.class, properties = {"oms.workflow.enabled=false"})
@ActiveProfiles("test")
public abstract class WorkflowTestBase extends PostgresTestBase {}
