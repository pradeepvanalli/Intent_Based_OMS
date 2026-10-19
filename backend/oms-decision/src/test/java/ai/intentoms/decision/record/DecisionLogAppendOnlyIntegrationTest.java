package ai.intentoms.decision.record;

import ai.intentoms.decision.DecisionTestBase;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §9.1 decision_log is append-only (audit 3, T-decision_log): the database refuses any UPDATE or DELETE of a decision's
 * own columns; labels and overrides are appended to decision_annotation through the repository, which alone may
 * refresh the record's cache columns; legal erasure pseudonymises personal columns under its own session setting.
 * OC-01: an L0-rule record carries the same typed context features as a model-decided one.
 */
class DecisionLogAppendOnlyIntegrationTest extends DecisionTestBase {
    @Autowired DecisionService decisions;
    @Autowired DecisionRecordRepository records;
    @Autowired DecisionInputErasure erasure;

    private DecisionRecord ruleDecided() {
        Map<String, Object> input = Map.of("return", Map.of("reason", "changed_mind", "days_since_delivery", 45, "receipt", "present", "item_condition", "matches"),
            "order", Map.of("final_sale", false, "nonce", System.nanoTime()), "customer", Map.of("history", Map.of("returns_12m", 1, "not_received_claims", 0, "flags", List.of("first_return"))),
            "policy", Map.of("window_days", 30));
        DecisionResult r = decisions.decide("return_risk", input, DecisionOptions.live("return", "rma_log_" + System.nanoTime(), Actor.system("test")));
        assertThat(r.layer()).isEqualTo(Layer.L0_RULE);
        return records.find(r.recordId()).orElseThrow();
    }

    @Test
    void aDirectUpdateOrDeleteOfADecisionIsRefused() {
        DecisionRecord rec = ruleDecided();
        assertThatThrownBy(() -> jdbc.sql("update decision_record set value = '\"tampered\"'::jsonb where tenant_id = :t and id = :id").param("t", TENANT).param("id", rec.id()).update())
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("update decision_record set confidence = 0.1, labels = '{}'::jsonb where tenant_id = :t and id = :id").param("t", TENANT).param("id", rec.id()).update())
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("update decision_record set labels = labels || '{\"x\": {}}'::jsonb where tenant_id = :t and id = :id").param("t", TENANT).param("id", rec.id()).update())
            .as("even the cache columns change only through the annotation path").hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from decision_record where tenant_id = :t and id = :id").param("t", TENANT).param("id", rec.id()).update())
            .hasMessageContaining("append-only");
        assertThat(records.find(rec.id()).orElseThrow().value()).isEqualTo(rec.value());
    }

    @Test
    void labelsAndOverridesAreAppendedAnnotationsAndTheRecordCachesThem() {
        DecisionRecord rec = ruleDecided();
        records.label(rec.id(), "human_resolution", Map.of("value", "low", "outcome", "override", "resolvedBy", "user:ops"));
        records.override(rec.id(), "low", "customer is known", "user:ops", Instant.now());
        DecisionRecord after = records.find(rec.id()).orElseThrow();
        assertThat(after.labels()).containsKey("human_resolution");
        assertThat(after.overriddenValue()).isEqualTo("low");
        assertThat(after.value()).as("the decision itself is unchanged").isEqualTo(rec.value());
        List<Map<String, Object>> annotations = records.annotations(rec.id());
        assertThat(annotations).extracting(a -> a.get("kind")).containsExactly("label", "override");
        String annotationId = (String) annotations.get(0).get("annotation_id");
        assertThatThrownBy(() -> jdbc.sql("update decision_annotation set name = 'x' where annotation_id = :a").param("a", annotationId).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from decision_annotation where annotation_id = :a").param("a", annotationId).update()).hasMessageContaining("append-only");
    }

    @Test
    void legalErasureIsTheOneExceptionAndOnlyForPersonalColumns() {
        String ref = "cust-log-" + System.nanoTime();
        Map<String, Object> input = Map.of("customer", Map.of("ref", ref, "name", "Jane Doe"), "order", Map.of("id", "ord_log"));
        DecisionRecord base = ruleDecided();
        DecisionRecord rec = new DecisionRecord(ai.intentoms.platform.ids.Ids.id(), "fulfilment_intent", 1, base.mode(), "customer", ref, ref, base.layer(), base.band(), base.action(),
            "ship_complete", Map.of(), 0.9, null, null, "h", Map.of(), input, List.of(), List.of(), 1, 0, 0, false, null, "system:test",
            Map.of("learnedFacts", List.of(Map.of("subjectType", "CUSTOMER", "subjectId", ref, "factKey", "SPLIT_TOLERANCE", "learned", 0.7),
                Map.of("subjectType", "NODE", "subjectId", "DC-NJ", "factKey", "NODE_ON_TIME_RATE", "learned", 0.9))),
            null, null, null, null, Map.of(), Instant.now(), Map.of(), List.of("ship_complete"), "ship_complete", 1.0, 1);
        records.insert(rec);
        TenantContext.runAs(TENANT, () -> erasure.erase(ref, "cu_00000000000000000000"));
        DecisionRecord erased = records.find(rec.id()).orElseThrow();
        assertThat(Json.write(erased.input())).doesNotContain("Jane").doesNotContain(ref);
        assertThat(erased.subjectId()).isEqualTo("cu_00000000000000000000");
        assertThat(Json.write(erased.details())).as("no learned fact about the erased customer").doesNotContain(ref).contains("NODE_ON_TIME_RATE").doesNotContain("SPLIT_TOLERANCE");
        assertThat(erased.value()).isEqualTo("ship_complete");
    }

    @Test
    void anL0RuleRecordLogsTheSameContextFeaturesAsAnyOther() {
        DecisionRecord rec = ruleDecided();
        assertThat(rec.contextFeatures()).as("OC-01: not empty for a rule-decided record").isNotEmpty();
        assertThat(Json.write(rec.contextFeatures())).contains("changed_mind");
    }
}
