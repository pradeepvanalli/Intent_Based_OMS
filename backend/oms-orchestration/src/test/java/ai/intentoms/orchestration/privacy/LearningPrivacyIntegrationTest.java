package ai.intentoms.orchestration.privacy;

import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.workflow.StandardShipOrderWorkflow;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.finance.model.Money;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF-07: after a full order lifecycle (capture, sourcing, release, shipment, delivery) has fed every learner, no
 * learning table and no decision state sent to a model provider holds the customer's raw name, email, phone or street.
 * Identifiers stay pseudonymous (customer ref, tokens).
 */
class LearningPrivacyIntegrationTest extends OrchestrationTestBase {
    /** Every table the self-optimizing layer writes. */
    static final List<String> LEARNING_TABLES = List.of("learned_fact", "episode", "outcome", "segment", "hypothesis", "proposal", "experiment", "objective_profile",
        "learner", "learned_parameter_live", "node_assignment", "lane_delivery_sample", "node_reliability_stat", "customer_preference_evidence", "customer_order_ref",
        "customer_evidence_event", "promise_conversion", "learning_llm_call", "learning_digest", "learning_baseline");

    @Test
    void noRawPiiReachesLearningTablesOrModelInputs() {
        String suffix = Ids.shortId().toLowerCase();
        String name = "Zelda Quintanilla" + suffix;
        String email = "zelda.q." + suffix + "@pii-example.org";
        String phone = "+1 312 555 01" + (10 + Math.abs(suffix.hashCode() % 89));
        String street = "4417 Larkspur Gardens Ave " + suffix;
        String ref = "cust-pii-" + suffix;
        CaptureRequest req = new CaptureRequest("web", "WEB-PII-" + suffix, "standard_ship", ref,
            new CaptureRequest.CustomerInput(ref, name, email, phone, "gold", true, 41, Map.of("orders_12m", 3)),
            List.of(line("1", "HOOD-GRY-M", 1, "68.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", name, street, null, "Chicago", "IL", "60606", "US", phone, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_visa_ok", Money.usd("80.00"), Map.of())),
            List.of(new CaptureRequest.NoteInput("customer", null, "Please send everything together.", false)), List.of(), "ground", null, "USD", Map.of(), null);

        Order o = capture(req);
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        String node = nodeOf(o);
        inbound.wms(node, wmsEvent("wms-pii-" + suffix, "shipped", o.id(), Map.of("carrier", "UPS", "service", "ground", "tracking", "1ZPII" + suffix)));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", "car-pii-" + suffix, "type", "delivered", "orderId", o.id(), "tracking", "1ZPII" + suffix));
        driveToTerminal(StandardShipOrderWorkflow.TYPE, o.id());
        drainOutbox();

        // The flow really did reach learning (otherwise the scan proves nothing).
        assertThat(count("select count(*) from episode where tenant_id = :t and order_id = :o", o.id())).isGreaterThan(0);
        assertThat(count("select count(*) from outcome o join episode e on e.tenant_id = o.tenant_id and e.episode_id = o.episode_id where e.tenant_id = :t and e.order_id = :o", o.id()))
            .isGreaterThan(0);

        List<String> needles = List.of(name, email, phone, street, "Zelda", "Larkspur");
        List<String> leaks = new ArrayList<>();
        for (String table : LEARNING_TABLES) {
            boolean exists = jdbc.sql("select count(*) from information_schema.tables where table_name = :n").param("n", table).query(Long.class).single() > 0;
            if (!exists) continue;
            for (String needle : needles) {
                long hits = jdbc.sql("select count(*) from " + table + " x where x::text ilike :p").param("p", "%" + needle + "%").query(Long.class).single();
                if (hits > 0) leaks.add(table + " contains '" + needle + "'");
            }
        }
        // Model inputs: the tokenised state each decision of this order sent to its provider.
        for (String needle : needles) {
            long hits = jdbc.sql("select count(*) from decision_record where tenant_id = :t and subject_id = :o and state::text ilike :p")
                .param("t", tenant).param("o", o.id()).param("p", "%" + needle + "%").query(Long.class).single();
            if (hits > 0) leaks.add("decision_record.state contains '" + needle + "'");
        }
        assertThat(leaks).as("raw PII found in learning tables or model inputs").isEmpty();
    }

    private long count(String sql, String orderId) {
        return jdbc.sql(sql).param("t", tenant).param("o", orderId).query(Long.class).single();
    }
}
