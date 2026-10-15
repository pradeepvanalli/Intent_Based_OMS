package ai.intentoms.returns.service;

import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.returns.config.ReturnsConfigKeys;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.store.ReturnRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-channel identity resolution for the risk decision (FR-RET-07): the {@code customer.history} input is built from
 * every RMA of the last 12 months that matches the customer ref <em>or</em> the tokenised e-mail, so store, web and
 * agent returns of the same person count together. Only aggregates reach the model (GR-09, GR-16): counts and flags,
 * never names or e-mails.
 */
@Component
public class CustomerReturnHistory {
    public static final String NOT_RECEIVED = "not_received";
    public static final String FLAG_FLAGGED_CUSTOMER = "flagged_customer";
    public static final String FLAG_SERIAL_RETURNER = "serial_returner";
    public static final String FLAG_REPEATED_NOT_RECEIVED = "repeated_not_received_claims";

    private final ReturnRepository returns;
    private final TenantConfigStore config;
    private final TimeSource time;

    public CustomerReturnHistory(ReturnRepository returns, TenantConfigStore config, TimeSource time) {
        this.returns = returns;
        this.config = config;
        this.time = time;
    }

    /**
     * @param customerRef   the order's customer reference (may be null for guest store returns)
     * @param emailToken    tokenised e-mail (never the raw address)
     * @param excludeRmaId  the RMA being decided, excluded from its own history
     * @return {@code {returns_12m, not_received_claims, refunded_12m, flags[]}}
     */
    public Map<String, Object> build(String customerRef, String emailToken, String excludeRmaId) {
        Instant since = time.now().minus(365, ChronoUnit.DAYS);
        List<ReturnRma> history = returns.history(customerRef, emailToken, since, excludeRmaId);
        int notReceived = 0, refunded = 0;
        for (ReturnRma r : history) {
            if (NOT_RECEIVED.equals(r.reasonCode()) || r.lines().stream().anyMatch(l -> NOT_RECEIVED.equals(l.reasonCode()))) notReceived++;
            if (r.refunded()) refunded++;
        }
        List<String> flags = new ArrayList<>();
        if (customerRef != null && config.getList(ReturnsConfigKeys.FLAGGED_CUSTOMERS).stream().anyMatch(o -> customerRef.equals(String.valueOf(o)))) flags.add(FLAG_FLAGGED_CUSTOMER);
        if (history.size() >= config.getInt(ReturnsConfigKeys.SERIAL_RETURNER_THRESHOLD, ReturnsConfigKeys.DEFAULT_SERIAL_RETURNER_THRESHOLD)) flags.add(FLAG_SERIAL_RETURNER);
        if (notReceived >= config.getInt(ReturnsConfigKeys.NOT_RECEIVED_CLAIMS_THRESHOLD, ReturnsConfigKeys.DEFAULT_NOT_RECEIVED_CLAIMS_THRESHOLD)) flags.add(FLAG_REPEATED_NOT_RECEIVED);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("returns_12m", history.size());
        out.put("not_received_claims", notReceived);
        out.put("refunded_12m", refunded);
        out.put("flags", flags);
        return out;
    }
}
