package ai.intentoms.decision.definition;

import ai.intentoms.decision.TestYaml;
import ai.intentoms.decision.model.Bands;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionDefinitionParserTest {
    private final DecisionDefinitionParser parser = new DecisionDefinitionParser();

    @Test
    void parsesAppendixBFormat() {
        DecisionDefinition d = parser.parse(TestYaml.INTENT);
        assertThat(d.name()).isEqualTo("fulfilment_policy");
        assertThat(d.version()).isEqualTo(7);
        assertThat(d.tier()).isEqualTo(Tier.T1);
        assertThat(d.provider()).isEqualTo("jev");
        assertThat(d.fallbackProvider()).isEqualTo("heuristic");
        assertThat(d.dflt()).isEqualTo("channel_default_policy");
        assertThat(d.stateInclude()).containsExactly("order.channel", "order.notes", "customer.tier", "plan_options[].eta");
        assertThat(d.questions()).containsKeys("intent", "split_ok", "is_gift");
        assertThat(d.questions().get("intent").choiceCriteria()).hasSize(5);
        assertThat(d.questions().get("intent").escape()).isEqualTo("unclear");
        assertThat(d.questions().get("split_ok").reasonCode()).isTrue();
        assertThat(d.questions().get("split_ok").effectiveInstructions()).startsWith("This buyer");
        assertThat(d.bands()).isEqualTo(new Bands(0.8, 0.5));
        assertThat(d.postChecks()).hasSize(1);
        assertThat(d.evaluation().goldenSet()).isEqualTo("fulfilment_policy_gold_v7");
        assertThat(d.evaluation().minAccuracyInActBand()).isEqualTo(0.90);
        assertThat(d.main()).isPresent();
        assertThat(d.main().get().key()).isEqualTo("intent");
    }

    @Test
    void choiceWithoutEscapeOptionIsRejected() {
        String yaml = """
            decision: x
            version: 1
            tier: T1
            questions:
              q:
                type: choice
                instructions: pick
                criteria:
                  a: first option
                  b: second option
            """;
        assertThatThrownBy(() -> parser.parse(yaml)).isInstanceOf(ValidationException.class).hasMessageContaining("escape option");
    }

    @Test
    void escapeCanBeMarkedExplicitly() {
        String yaml = """
            decision: x
            version: 1
            tier: T1
            questions:
              q:
                type: choice
                instructions: pick
                criteria:
                  a: first option
                  b: { description: fallback option, escape: true }
            """;
        DecisionDefinition d = parser.parse(yaml);
        assertThat(d.questions().get("q").escape()).isEqualTo("b");
        assertThat(d.questions().get("q").choiceCriteria().get("b")).isEqualTo("fallback option");
    }

    @Test
    void bandsDefaultByTier() {
        assertThat(parse(Tier.T0).effectiveBands()).isEqualTo(new Bands(0.5, 0.5));
        assertThat(parse(Tier.T1).effectiveBands()).isEqualTo(new Bands(0.8, 0.5));
        assertThat(parse(Tier.T2).effectiveBands()).isEqualTo(new Bands(0.9, 0.5));
        Bands t3 = parse(Tier.T3).effectiveBands();
        assertThat(t3.neverActs()).isTrue();
        assertThat(t3.of(0.99)).isEqualTo(ai.intentoms.decision.model.Band.CONFIRM);
    }

    @Test
    void t3IgnoresExplicitActBand() {
        String yaml = base("T3") + "bands:\n  act: { confidence_gte: 0.9 }\n  confirm: { confidence_gte: 0.5 }\n";
        DecisionDefinition d = parser.parse(yaml);
        assertThat(d.effectiveBands().neverActs()).isTrue();
        assertThat(parser.parseLenient(yaml).issues()).anyMatch(i -> i.code().equals("t3_bands"));
    }

    @Test
    void scoreLevelsMustBeTwoToTen() {
        String one = "decision: x\nversion: 1\ntier: T1\nquestions:\n  s:\n    type: score\n    instructions: rate\n    criteria: [only]\n";
        assertThatThrownBy(() -> parser.parse(one)).hasMessageContaining("2..10");
        StringBuilder many = new StringBuilder("decision: x\nversion: 1\ntier: T1\nquestions:\n  s:\n    type: score\n    instructions: rate\n    criteria:\n");
        for (int i = 0; i < 11; i++) many.append("      - level ").append(i).append(" description\n");
        assertThatThrownBy(() -> parser.parse(many.toString())).hasMessageContaining("2..10");
    }

    @Test
    void tooManyChoiceOptionsIsRejected() {
        StringBuilder sb = new StringBuilder("decision: x\nversion: 1\ntier: T1\nquestions:\n  c:\n    type: choice\n    instructions: pick\n    criteria:\n      unclear: escape\n");
        for (int i = 0; i < 256; i++) sb.append("      opt").append(i).append(": option number ").append(i).append('\n');
        assertThatThrownBy(() -> parser.parse(sb.toString())).hasMessageContaining("255");
    }

    @Test
    void tierIsRequired() {
        assertThatThrownBy(() -> parser.parse("decision: x\nversion: 1\nquestions:\n  n:\n    type: noul\n    statement: s\n")).hasMessageContaining("tier");
    }

    @Test
    void extensionsAreParsed() {
        String yaml = """
            decision: scorer
            version: 2
            tier: T1
            mainQuestion: a
            status: shadow
            canaryPercent: 25
            changeReason: tune
            ticket: OMS-1
            cache: { ttl: PT10M }
            rules:
              - { id: r2, when: "input.x > 2", then: escalate, priority: 20 }
              - { id: r1, when: "input.x > 1", then: block, priority: 10 }
            composite:
              weights: { a: 0.7, b: 0.3 }
              outputKey: total
            topK: { scoreQuestion: a, k: 10, candidatesField: candidates }
            questions:
              a: { type: score, instructions: rate a, criteria: [low score level, high score level] }
              b: { type: score, instructions: rate b, criteria: [low score level, mid score level, high score level] }
            """;
        DecisionDefinition d = parser.parse(yaml);
        assertThat(d.rules()).extracting("id").containsExactly("r1", "r2");
        assertThat(d.composite().weights()).containsEntry("a", 0.7);
        assertThat(d.composite().outputKey()).isEqualTo("total");
        assertThat(d.topK().k()).isEqualTo(10);
        assertThat(d.cache().ttl()).isEqualTo(java.time.Duration.ofMinutes(10));
        assertThat(d.status()).isEqualTo("shadow");
        assertThat(d.canaryPercent()).isEqualTo(25);
        assertThat(d.mainQuestion()).isEqualTo("a");
    }

    private DecisionDefinition parse(Tier tier) { return parser.parse(base(tier.name())); }

    private static String base(String tier) {
        return "decision: x\nversion: 1\ntier: " + tier + "\nquestions:\n  q:\n    type: choice\n    instructions: pick\n    criteria:\n      a: first option here\n      unclear: not enough information\n";
    }
}
