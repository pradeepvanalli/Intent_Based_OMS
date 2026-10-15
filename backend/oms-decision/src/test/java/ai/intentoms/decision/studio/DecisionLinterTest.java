package ai.intentoms.decision.studio;

import ai.intentoms.decision.TestYaml;
import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.model.LintReport;
import ai.intentoms.platform.cel.CelEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionLinterTest {
    private final DecisionLinter linter = new DecisionLinter(new DecisionDefinitionParser(), new CelEngine(), () -> List.of("order", "customer", "plan_options", "chosen_plan"));

    @Test
    void cleanDefinitionHasNoErrors() {
        LintReport r = linter.lint(TestYaml.INTENT);
        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).extracting("code").doesNotContain("missing_escape_option", "protected_attribute", "cel_invalid");
    }

    @Test
    void flagsOverlappingOptionsVagueRubricPiiAndBadCel() {
        String yaml = """
            decision: lint_me
            version: 1
            tier: T1
            state:
              include: [order.channel, shipment.weight]
            rules:
              - { id: bad, when: "input.x >>> 1", then: block }
              - { id: contradict, when: "input.intent == 'ship_complete'", then: block }
              - { id: odd, when: "input.y == 1", then: not_an_option }
            questions:
              intent:
                type: choice
                instructions: Which outcome, include the customer email in the answer?
                criteria:
                  ship_complete: Everything together in one delivery even if later
                  ship_all_together: Everything together in one delivery even if it is later
                  unclear: Not enough information
              risk:
                type: score
                instructions: rate
                criteria: [low, medium risk level here, high]
            """;
        LintReport r = linter.lint(yaml);
        List<String> codes = r.issues().stream().map(LintReport.LintIssue::code).toList();
        assertThat(codes).contains("overlapping_options", "vague_rubric_level", "pii_in_prompt", "cel_invalid", "option_blocked_by_rule", "rule_value_not_an_option", "unknown_state_path");
        assertThat(r.ok()).isFalse();
    }

    @Test
    void protectedAttributesAreErrorsForTier2() {
        String yaml = """
            decision: risky
            version: 1
            tier: T2
            state:
              include: [customer.age, customer.gender, address.zip_code, order.total]
            questions:
              r: { type: noul, statement: This return is abusive. }
            """;
        LintReport r = linter.lint(yaml);
        assertThat(r.issues().stream().filter(i -> i.code().equals("protected_attribute") && i.severity().equals("error")).count()).isEqualTo(2);
        assertThat(r.issues()).anyMatch(i -> i.code().equals("zip_proxy"));
        assertThat(r.ok()).isFalse();
        LintReport t0 = linter.lint(yaml.replace("tier: T2", "tier: T0"));
        assertThat(t0.issues().stream().filter(i -> i.code().equals("protected_attribute"))).allMatch(i -> i.severity().equals("warning"));
    }

    @Test
    void jaccardSimilarity() {
        assertThat(DecisionLinter.jaccard("everything together in one delivery", "everything together in one delivery later")).isGreaterThan(0.6);
        assertThat(DecisionLinter.jaccard("collect in store", "each item separately")).isLessThan(0.2);
    }
}
