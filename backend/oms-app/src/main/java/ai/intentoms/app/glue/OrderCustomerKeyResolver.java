package ai.intentoms.app.glue;

import ai.intentoms.learning.experiment.CustomerKeyResolver;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * EX-03 / EX-04: experiments split by customer. An order resolves to the customer who placed it, a return to the
 * customer on the RMA (or its order's customer), and a customer subject is itself. Orders with no customer reference
 * route on their own id, as before. Results are cached by the experiment router.
 */
@Component
public class OrderCustomerKeyResolver implements CustomerKeyResolver {
    private final JdbcClient jdbc;

    public OrderCustomerKeyResolver(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<String> customerOf(String subjectType, String subjectId) {
        if (subjectType == null || subjectId == null || subjectId.isBlank()) return Optional.empty();
        String tenant = TenantContext.require();
        return switch (subjectType.toLowerCase(java.util.Locale.ROOT)) {
            case "customer" -> Optional.of(subjectId);
            case "order" -> jdbc.sql("select customer_ref from orders where tenant_id = :t and id = :id and customer_ref is not null and customer_ref <> ''")
                .param("t", tenant).param("id", subjectId).query(String.class).optional();
            case "return", "rma" -> jdbc.sql("""
                select coalesce(nullif(r.customer_ref, ''), o.customer_ref) from return_rma r left join orders o on o.tenant_id = r.tenant_id and o.id = r.order_id
                where r.tenant_id = :t and r.rma_id = :id
                """).param("t", tenant).param("id", subjectId).query(String.class).optional().filter(c -> c != null && !c.isBlank());
            default -> Optional.empty();
        };
    }
}
