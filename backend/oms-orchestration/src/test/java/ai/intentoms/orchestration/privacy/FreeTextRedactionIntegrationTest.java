package ai.intentoms.orchestration.privacy;

import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.workflow.StandardShipOrderWorkflow;
import ai.intentoms.order.model.Order;
import ai.intentoms.returns.model.ReturnChannel;
import ai.intentoms.returns.model.ReturnLineRequest;
import ai.intentoms.returns.model.ReturnRequest;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.service.ReturnService;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit A3 / NF-07: contact details a customer types into free text (an order note, a return reason) never reach a
 * decision's input, state or context features, which is also what a model prompt is built from.
 */
class FreeTextRedactionIntegrationTest extends OrchestrationTestBase {
    private static final String NOTE = "call me at 973-555-0177 or mail quillon.a3a@example.com";
    @Autowired ReturnService returns;

    /** input, state and context_features of every decision about the subject (or correlated with it), as stored. */
    private List<Map<String, Object>> decisionRows(String subjectId) {
        return jdbc.sql("""
            select decision, coalesce(input::text, '') as input, coalesce(state::text, '') as state, coalesce(context_features::text, '') as features
            from decision_record where tenant_id = :t and (subject_id = :s or correlation_id = :s)
            """).param("t", tenant).param("s", subjectId).query().listOfRows();
    }

    private static void assertNoContactDetails(List<Map<String, Object>> rows) {
        assertThat(rows).isNotEmpty().allSatisfy(r -> {
            String all = r.get("input") + " " + r.get("state") + " " + r.get("features");
            assertThat(all).as("decision %s", r.get("decision")).doesNotContain("973-555-0177").doesNotContain("5550177").doesNotContain("quillon").doesNotContain("example.com");
        });
    }

    @Test
    void anOrderNoteWithAnEmailAndAPhoneNumberYieldsDecisionRecordsWithNeither() {
        Order o = capture(webOrder("A3-NOTE", "tok_visa_ok", NOTE));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        drainOutbox();

        List<Map<String, Object>> rows = decisionRows(o.id());
        assertNoContactDetails(rows);
        assertThat(rows).filteredOn(r -> "fulfilment_intent".equals(r.get("decision"))).singleElement()
            .satisfies(r -> assertThat(String.valueOf(r.get("input"))).as("the note still reaches the decision, redacted").contains("call me at [phone] or mail [email]"));
        assertThat(orders.get(o.id()).notes()).as("the order itself keeps the note for the people who handle it")
            .anySatisfy(n -> assertThat(n.text()).isEqualTo(NOTE));
    }

    @Test
    void aReturnReasonWithContactDetailsYieldsDecisionRecordsWithNeither() {
        Order o = capture(webOrder("A3-RET", "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent("A3-RET-ship", "shipped", o.id(), Map.of("carrier", "UPS", "tracking", "TRK-A3-RET")));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", "A3-RET-del", "type", "delivered", "orderId", o.id(), "tracking", "TRK-A3-RET"));
        driveToTerminal(StandardShipOrderWorkflow.TYPE, o.id());

        ReturnRma rma = returns.create(new ReturnRequest(o.id(), List.of(new ReturnLineRequest("1", 1, null, "too small, " + NOTE)), ReturnChannel.WEB, null, null, false, null, null), SYSTEM);
        List<Map<String, Object>> rows = decisionRows(rma.rmaId());
        assertThat(rows).anyMatch(r -> "return_reason".equals(r.get("decision")));
        assertNoContactDetails(rows);
    }

    /** NF-07 (audit 3): names and street addresses typed into free text, the order's own ones and any street address. */
    private static void assertNoNamesOrStreets(List<Map<String, Object>> rows) {
        assertThat(rows).isNotEmpty().allSatisfy(r -> {
            String all = (r.get("input") + " " + r.get("state") + " " + r.get("features")).toLowerCase(java.util.Locale.ROOT);
            assertThat(all).as("decision %s", r.get("decision")).doesNotContain("dana whitfield").doesNotContain("12 elm street").doesNotContain("233 s wacker")
                .doesNotContain("9 maple ave");
        });
    }

    @Test
    void anOrderNoteWithTheCustomersNameAndStreetAddressesYieldsDecisionRecordsWithNeither() {
        Order o = capture(webOrder("A3-NAME", "tok_visa_ok", "Call Dana Whitfield at 12 Elm Street, or leave it at 233 s wacker dr"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        drainOutbox();

        List<Map<String, Object>> rows = decisionRows(o.id());
        assertNoNamesOrStreets(rows);
        assertThat(rows).filteredOn(r -> "fulfilment_intent".equals(r.get("decision"))).singleElement()
            .satisfies(r -> assertThat(String.valueOf(r.get("input"))).as("the note keeps what the customer wants").contains("Call [name] at [address], or leave it at [address]"));
    }

    /**
     * A3 residue: a third party the order's customer mentions in a note — a gift recipient, someone to call — is not
     * the customer's or ship-to's own known name, so it used to reach the decision input verbatim (by design, the
     * redactor would not guess at a name it was not told about). The cue heuristic now redacts it too, and still
     * leaves a single-word place name after a pair-only cue (the ship-to city, "to Chicago") alone, same as before.
     */
    @Test
    void anOrderNoteNamingAThirdPartyYieldsDecisionRecordsWithoutTheThirdPartysName() {
        Order o = capture(webOrder("A3-THIRD", "tok_visa_ok", "Gift for Marisol Reyes, call Dan at the desk, ship to Chicago as usual"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        drainOutbox();

        List<Map<String, Object>> rows = decisionRows(o.id());
        assertThat(rows).isNotEmpty().allSatisfy(r -> {
            String all = (r.get("input") + " " + r.get("state") + " " + r.get("features")).toLowerCase(java.util.Locale.ROOT);
            assertThat(all).as("decision %s", r.get("decision")).doesNotContain("marisol").doesNotContain("reyes").doesNotContain(" dan ");
        });
        assertThat(rows).filteredOn(r -> "fulfilment_intent".equals(r.get("decision"))).singleElement()
            .satisfies(r -> assertThat(String.valueOf(r.get("input")))
                .as("the third party's names are gone, the note's own meaning and the ship-to city survive")
                .contains("Gift for [name], call [name] at the desk, ship to Chicago as usual"));
    }

    @Test
    void aReturnReasonWithANameAndAStreetAddressYieldsDecisionRecordsWithNeither() {
        Order o = capture(webOrder("A3-RETNAME", "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent("A3-RETNAME-ship", "shipped", o.id(), Map.of("carrier", "UPS", "tracking", "TRK-A3-RETNAME")));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", "A3-RETNAME-del", "type", "delivered", "orderId", o.id(), "tracking", "TRK-A3-RETNAME"));
        driveToTerminal(StandardShipOrderWorkflow.TYPE, o.id());

        ReturnRma rma = returns.create(new ReturnRequest(o.id(), List.of(new ReturnLineRequest("1", 1, null, "too small, send the new one to Dana Whitfield, 9 Maple Ave")),
            ReturnChannel.WEB, null, null, false, null, null), SYSTEM);
        List<Map<String, Object>> rows = decisionRows(rma.rmaId());
        assertThat(rows).anyMatch(r -> "return_reason".equals(r.get("decision")));
        assertNoNamesOrStreets(rows);
    }
}
