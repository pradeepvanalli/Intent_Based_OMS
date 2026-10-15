package ai.intentoms.decision.studio;

import ai.intentoms.decision.DecisionTestBase;
import ai.intentoms.decision.TestYaml;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.definition.GoldenCaseRepository;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.model.LintReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The demo catalogue must load, lint clean and pass its (relaxed) evaluation gates with the heuristic provider. */
class DemoCatalogueIntegrationTest extends DecisionTestBase {
    @Autowired DecisionStudioService studio;
    @Autowired DecisionDefinitionRepository definitions;
    @Autowired GoldenCaseRepository golden;

    @Test
    void catalogueIsLiveAndLintsWithoutErrors() throws Exception {
        List<String> files;
        try (var s = Files.list(TestYaml.DEMO_DECISIONS)) { files = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yaml")).sorted().toList(); }
        assertThat(files).hasSize(15); // +1: stuck_order_diagnosis.yaml (Agentic Control Tower)
        for (String f : files) {
            String name = f.substring(0, f.length() - 5);
            assertThat(definitions.byStatus(name, "live")).as(name + " live").isNotEmpty();
            LintReport lint = studio.lint(TestYaml.demo(name));
            assertThat(lint.ok()).as(name + " lint: " + lint.issues()).isTrue();
        }
        assertThat(golden.count("fulfilment_intent_gold")).isGreaterThanOrEqualTo(12);
        assertThat(golden.count("exception_classify_gold")).isGreaterThanOrEqualTo(12);
        assertThat(golden.count("return_risk_gold")).isGreaterThanOrEqualTo(12);
    }

    @Test
    void goldenSetsPassTheDemoGates() {
        for (String name : new String[]{"fulfilment_intent", "exception_classify", "return_risk"}) {
            EvaluationReport r = studio.evaluate(name, definitions.byStatus(name, "live").get(0).definition().version());
            assertThat(r.passed()).as("%s gate=%s failures=%s", name, r.gateFailures(), r.failures()).isTrue();
            assertThat(r.cases()).isGreaterThanOrEqualTo(12);
            assertThat(r.bins()).hasSize(10);
            assertThat(definitions.byStatus(name, "live").get(0).evaluationReport()).containsEntry("passed", true);
        }
    }
}
