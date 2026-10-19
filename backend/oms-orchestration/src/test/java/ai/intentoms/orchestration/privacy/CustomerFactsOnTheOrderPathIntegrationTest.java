package ai.intentoms.orchestration.privacy;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.CustomerSubjects;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.workflow.StandardShipOrderWorkflow;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF-07 on the live order path: the order names the customer by raw reference, learning keeps their facts under the
 * vault token, and the decisions about the order (fulfilment_intent) still find and list those facts, by token.
 */
class CustomerFactsOnTheOrderPathIntegrationTest extends OrchestrationTestBase {
    @Autowired LearnedFactRepository facts;
    @Autowired LearnedFactCache cache;
    @Autowired CustomerSubjects subjects;
    @Autowired DecisionService decisions;

    @Test
    @SuppressWarnings("unchecked")
    void anOrdersDecisionsReadTheCustomersTokenKeyedFacts() {
        String token = subjects.key("cust-1001");   // webOrder's customer
        Instant now = Instant.now();
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, token, FactKey.PREFERRED_CONTACT_CHANNEL, new TypedValue.TextValue("sms"), 0.8, 4,
            "ci_preference", 99, now, null, now));
        cache.invalidateAll();

        Order o = capture(webOrder("TOKEN-FACTS-" + Ids.shortId(), "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        DecisionRecord intent = decisions.forSubject("order", o.id()).stream().filter(r -> "fulfilment_intent".equals(r.decision())).findFirst().orElseThrow();
        List<Map<String, Object>> used = (List<Map<String, Object>>) intent.details().get("learnedFacts");
        assertThat(used).as("the customer's facts were read for the order's decision").isNotNull()
            .anySatisfy(u -> assertThat(u).containsEntry("subjectType", "CUSTOMER").containsEntry("subjectId", token).containsEntry("factKey", "PREFERRED_CONTACT_CHANNEL"));
        assertThat(used).noneMatch(u -> "cust-1001".equals(u.get("subjectId")));
    }
}
