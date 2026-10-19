package ai.intentoms.app.glue;

import ai.intentoms.IntentOmsApplication;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.testing.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** EX-03 / EX-04: experiments split by customer, so an order and a customer subject resolve to the same customer key. */
@SpringBootTest(classes = IntentOmsApplication.class, properties = {"oms.demo.seed=false"})
@ActiveProfiles("test")
class OrderCustomerKeyResolverIntegrationTest extends PostgresTestBase {
    @Autowired OrderCustomerKeyResolver resolver;

    @Test
    void anOrderResolvesToTheCustomerWhoPlacedItAndAnOrderWithoutOneDoesNot() {
        String customer = "cust-route-" + Ids.shortId();
        String withCustomer = "ord_route_" + Ids.shortId();
        String anonymous = "ord_anon_" + Ids.shortId();
        insertOrder(withCustomer, customer);
        insertOrder(anonymous, null);

        assertThat(resolver.customerOf("order", withCustomer)).contains(customer);
        assertThat(resolver.customerOf("order", anonymous)).isEmpty();
        assertThat(resolver.customerOf("customer", customer)).contains(customer);
        assertThat(resolver.customerOf("order", "ord_missing_" + Ids.shortId())).isEmpty();
        assertThat(resolver.customerOf("sku", "TEE-BLK-M")).isEmpty();
        assertThat(resolver.customerOf(null, withCustomer)).isEmpty();
    }

    private void insertOrder(String id, String customerRef) {
        jdbc.sql("""
            insert into orders(id, tenant_id, channel, channel_order_id, order_type, customer_ref, customer, state_model_id, state_model_version, status, placed_at, search_text)
            values (:id, :t, 'web', :ch, 'standard_ship', :c, cast(:cust as jsonb), 'order', 1, 'captured', now(), :id)
            """).param("id", id).param("t", TENANT).param("ch", "W-" + id).param("c", customerRef)
            .param("cust", Json.write(customerRef == null ? Map.of() : Map.of("ref", customerRef))).update();
    }
}
