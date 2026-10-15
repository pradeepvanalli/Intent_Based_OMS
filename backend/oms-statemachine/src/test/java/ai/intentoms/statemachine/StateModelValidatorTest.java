package ai.intentoms.statemachine;

import ai.intentoms.platform.cel.CelEngine;
import ai.intentoms.statemachine.model.StateModelParser;
import ai.intentoms.statemachine.model.ValidationIssue;
import ai.intentoms.statemachine.model.ValidationReport;
import ai.intentoms.statemachine.service.StateModelValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One test per static check of FR-SM-10. */
class StateModelValidatorTest {
    private final StateModelValidator validator = new StateModelValidator(new CelEngine());

    private static String model(String states, String transitions) {
        return "stateModel: t.m\nversion: 1\nentity: thing\nstates:\n" + states + "transitions:\n" + transitions;
    }

    private static final String BASE_STATES = """
          - { id: created, meta: Created }
          - { id: active }
          - { id: done, meta: Terminal }
        """;

    private List<String> codes(ValidationReport r) { return r.issues().stream().map(ValidationIssue::code).toList(); }
    private List<String> errorCodes(ValidationReport r) { return r.errors().stream().map(ValidationIssue::code).toList(); }

    @Test
    void validModelPassesWithoutIssues() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES, """
              - { from: created, to: active, trigger: event.start }
              - { from: active, to: done, trigger: event.finish }
            """));
        assertThat(r.ok()).isTrue();
        assertThat(r.issues()).isEmpty();
    }

    @Test
    void appendixAPassesButDemoFileIsTheCompleteVersion() {
        ValidationReport r = validator.validateYaml(TestModels.APPENDIX_A);
        // Appendix A as printed leaves picking/packed/shipped/delivered unreachable and picking/packed/shipped dead ends.
        assertThat(r.ok()).isFalse();
        assertThat(errorCodes(r)).contains("unreachable_state", "dead_end");
        assertThat(validator.validateYaml(TestModels.demo("order_line.standard_ship.yaml")).ok()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"order_line.standard_ship.yaml", "order.standard_ship.yaml", "order_line.bopis.yaml", "order.bopis.yaml", "return.standard.yaml", "shipment.standard.yaml"})
    void demoModelsAreCleanOfErrorsAndWarnings(String file) {
        ValidationReport r = validator.validateYaml(TestModels.demo(file));
        assertThat(r.issues()).as(file + " issues").isEmpty();
        assertThat(r.ok()).isTrue();
    }

    @Test
    void unreachableState() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES + "  - { id: orphan }\n", """
              - { from: created, to: active, trigger: event.start }
              - { from: active, to: done, trigger: event.finish }
              - { from: orphan, to: done, trigger: event.finish }
            """));
        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).extracting(ValidationIssue::code, ValidationIssue::ref).containsExactly(org.assertj.core.groups.Tuple.tuple("unreachable_state", "state:orphan"));
    }

    @Test
    void deadEnd() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES, """
              - { from: created, to: active, trigger: event.start }
              - { from: created, to: done, trigger: event.skip }
            """));
        assertThat(errorCodes(r)).containsExactly("dead_end");
        assertThat(r.errors().get(0).message()).contains("'active'");
    }

    @Test
    void apiTriggerWithoutPermission() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES, """
              - { from: created, to: active, trigger: event.start }
              - { from: active, to: done, trigger: api.finish }
            """));
        assertThat(errorCodes(r)).containsExactly("missing_permission");
    }

    @Test
    void unknownSourceAndTargetStates() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES, """
              - { from: created, to: active, trigger: event.start }
              - { from: active, to: nowhere, trigger: event.finish }
              - { from: ghost, to: done, trigger: event.finish }
            """));
        assertThat(errorCodes(r)).containsExactly("unknown_state", "unknown_state", "unreachable_state");
        assertThat(r.errors()).extracting(ValidationIssue::message).anyMatch(m -> m.contains("unknown target state 'nowhere'")).anyMatch(m -> m.contains("unknown source state 'ghost'"));
    }

    @Test
    void guardThatDoesNotCompile() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES, """
              - { from: created, to: active, trigger: event.start, guards: ["plan.margin >="] }
              - { from: active, to: done, trigger: event.finish }
            """));
        assertThat(errorCodes(r)).containsExactly("invalid_guard");
    }

    @Test
    void guardReferencingUndeclaredRoot() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES, """
              - { from: created, to: active, trigger: event.start, guards: ["basket.total > 10"] }
              - { from: active, to: done, trigger: event.finish }
            """));
        assertThat(errorCodes(r)).contains("unknown_guard_root");
        assertThat(r.errors().get(0).message()).contains("basket");
    }

    @Test
    void comprehensionVariablesAreNotMistakenForRoots() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES, """
              - { from: created, to: active, trigger: event.start, guards: ["!order.holds.exists(h, 'release' in h.blocks)", "lines.all(l, l.qty > 0)"] }
              - { from: active, to: done, trigger: event.finish }
            """));
        assertThat(r.issues()).isEmpty();
    }

    @Test
    void duplicateStateIds() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES + "  - { id: active }\n", """
              - { from: created, to: active, trigger: event.start }
              - { from: active, to: done, trigger: event.finish }
            """));
        assertThat(errorCodes(r)).containsExactly("duplicate_state");
    }

    @Test
    void missingInitialState() {
        ValidationReport r = validator.validateYaml(model("""
              - { id: created }
              - { id: done, meta: Terminal }
            """, "  - { from: created, to: done, trigger: event.finish }\n"));
        assertThat(errorCodes(r)).containsExactly("missing_initial");
    }

    @Test
    void multipleInitialStates() {
        ValidationReport r = validator.validateYaml(model("""
              - { id: created, meta: Created }
              - { id: other, meta: Created }
              - { id: done, meta: Terminal }
            """, "  - { from: [created, other], to: done, trigger: event.finish }\n"));
        assertThat(errorCodes(r)).containsExactly("multiple_initial");
    }

    @Test
    void missingTerminalState() {
        ValidationReport r = validator.validateYaml(model("""
              - { id: created, meta: Created }
              - { id: active }
            """, """
              - { from: created, to: active, trigger: event.start }
              - { from: active, to: created, trigger: event.reset, guards: ["ctx.again"] }
            """));
        assertThat(errorCodes(r)).containsExactly("missing_terminal");
    }

    @Test
    void unboundedLoopIsAWarningOnlyWhenUnguardedAndAutomatic() {
        String loop = """
              - { from: created, to: active, trigger: event.start }
              - { from: active, to: created, trigger: event.reset }
              - { from: active, to: done, trigger: event.finish }
            """;
        ValidationReport r = validator.validateYaml(model(BASE_STATES, loop));
        assertThat(r.ok()).isTrue();
        assertThat(codes(r)).containsExactly("unbounded_loop");
        assertThat(r.warnings().get(0).message()).contains("active").contains("created");

        assertThat(validator.validateYaml(model(BASE_STATES, loop.replace("trigger: event.reset", "trigger: event.reset, guards: [\"ctx.retries < 3\"]"))).issues()).isEmpty();
        assertThat(validator.validateYaml(model(BASE_STATES, loop.replace("trigger: event.reset", "trigger: timer.retry"))).issues()).isEmpty();
        assertThat(validator.validateYaml(model(BASE_STATES, loop.replace("trigger: event.reset", "trigger: api.reset, permission: order.modify"))).issues()).isEmpty();
    }

    @Test
    void slaOnBreachFormat() {
        String good = model("""
              - { id: created, meta: Created }
              - { id: active, sla: { maxAge: PT1H, onBreach: playbook.nudge } }
              - { id: done, meta: Terminal }
            """, """
              - { from: created, to: active, trigger: event.start }
              - { from: active, to: done, trigger: event.finish }
            """);
        assertThat(validator.validateYaml(good).issues()).isEmpty();
        assertThat(validator.validateYaml(good.replace("playbook.nudge", "decision.next_action")).issues()).isEmpty();
        ValidationReport bad = validator.validateYaml(good.replace("playbook.nudge", "email_ops"));
        assertThat(errorCodes(bad)).containsExactly("invalid_sla_on_breach");
        assertThat(errorCodes(validator.validateYaml(good.replace("playbook.nudge", "webhook.x")))).containsExactly("invalid_sla_on_breach");
    }

    @Test
    void invalidTriggerAndUnknownTriggerKind() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES, """
              - { from: created, to: active, trigger: start }
              - { from: active, to: done, trigger: webhook.finish }
            """));
        assertThat(errorCodes(r)).containsExactly("invalid_trigger");
        assertThat(codes(r)).contains("unknown_trigger_kind");
    }

    @Test
    void rollupRulesAreChecked() {
        String y = """
            stateModel: order.x
            version: 1
            entity: order
            states: [ { id: created, meta: Created }, { id: shipped, meta: Terminal } ]
            transitions: [ { from: created, to: shipped, trigger: event.shipped } ]
            rollup:
              - { parentState: nowhere, when: "state.shipped > 0" }
              - { parentState: shipped, when: "plan.margin > 0" }
              - { parentState: shipped, when: "state.shipped >" }
            """;
        ValidationReport r = validator.validateYaml(y);
        assertThat(errorCodes(r)).containsExactlyInAnyOrder("unknown_rollup_state", "unknown_rollup_root", "invalid_rollup_expression");
    }

    @Test
    void parseErrorsAreReportedNotThrown() {
        ValidationReport r = validator.validateYaml("stateModel: x\nstates:\n  - { id: a, sla: { maxAge: soon, onBreach: playbook.x } }");
        assertThat(r.ok()).isFalse();
        assertThat(errorCodes(r)).containsExactly("parse_error");
    }

    @Test
    void missingEntityAndUnknownParent() {
        ValidationReport r = validator.validateYaml("""
            stateModel: x
            version: 0
            states:
              - { id: created, meta: Created }
              - { id: sub, parent: missing }
              - { id: done, meta: Terminal }
            transitions:
              - { from: created, to: sub, trigger: event.a }
              - { from: sub, to: done, trigger: event.b }
            """);
        assertThat(errorCodes(r)).containsExactlyInAnyOrder("missing_entity", "invalid_version", "unknown_parent");
    }

    @Test
    void duplicateTransitionAndTerminalOutgoingAreWarnings() {
        ValidationReport r = validator.validateYaml(model(BASE_STATES, """
              - { from: created, to: active, trigger: event.start }
              - { from: created, to: active, trigger: event.start }
              - { from: active, to: done, trigger: event.finish }
              - { from: done, to: active, trigger: event.reopen, guards: ["ctx.allowed"] }
            """));
        assertThat(r.ok()).isTrue();
        assertThat(codes(r)).containsExactlyInAnyOrder("duplicate_transition", "terminal_outgoing");
        assertThat(StateModelParser.parse(TestModels.simple("s", 1)).transitions()).hasSize(3);
    }
}
