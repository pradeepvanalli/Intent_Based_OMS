package ai.intentoms.integrations.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Deploy-time settings of the integrations module ({@code oms.integrations.*}; section 11 "Integrations and open
 * standards", FR-CAP-01). Every adapter is off by default so the system runs with no credentials: the deterministic
 * mocks shipped by oms-order / oms-orchestration stay in place until a provider is selected here. Secrets are only ever
 * read from these properties (environment variables in deployment); {@code GET /api/v1/integrations/status} reports
 * whether they are present, never their values.
 *
 * @param graphiql    serve the GraphiQL explorer at {@code /graphiql} (development only)
 * @param marketplace marketplace connectors (Amazon SP-API poller)
 * @param notify      customer notification providers (SendGrid e-mail, Twilio SMS)
 * @param address     address verification provider ({@code mock} | {@code smarty})
 * @param carrier     carrier aggregator ({@code mock} | {@code easypost})
 * @param wms         warehouse connector ({@code mock} | {@code http})
 */
@ConfigurationProperties(prefix = "oms.integrations")
public final class IntegrationsProperties {
    private final boolean graphiql;
    private final Marketplace marketplace;
    private final Notify notify;
    private final Address address;
    private final Carrier carrier;
    private final Wms wms;

    /** Constructor binding; {@code notify} cannot be a record component ({@code Object.notify}), hence a class. */
    public IntegrationsProperties(@DefaultValue("false") boolean graphiql, @DefaultValue Marketplace marketplace, @DefaultValue Notify notify,
                                  @DefaultValue Address address, @DefaultValue Carrier carrier, @DefaultValue Wms wms) {
        this.graphiql = graphiql;
        this.marketplace = marketplace;
        this.notify = notify;
        this.address = address;
        this.carrier = carrier;
        this.wms = wms;
    }

    public boolean graphiql() { return graphiql; }
    public Marketplace marketplace() { return marketplace; }
    public Notify notifications() { return notify; }
    public Address address() { return address; }
    public Carrier carrier() { return carrier; }
    public Wms wms() { return wms; }

    public record Marketplace(@DefaultValue Amazon amazon) {}

    /**
     * Amazon Selling Partner API (Orders v0) poller. {@code enabled} turns the {@code @Scheduled} poller on;
     * LWA (Login with Amazon) credentials refresh the access token before each poll.
     *
     * @param tenant         the OMS tenant the polled orders belong to
     * @param baseUrl        SP-API endpoint (NA default)
     * @param lwaUrl         LWA token endpoint
     * @param marketplaceIds Amazon marketplace ids to poll (ATVPDKIKX0DER = amazon.com)
     * @param lookback       how far back the first poll looks when no watermark is stored
     * @param pollInterval   delay between polls
     */
    public record Amazon(@DefaultValue("false") boolean enabled, @DefaultValue("demo") String tenant, String clientId, String clientSecret, String refreshToken,
                         @DefaultValue("https://sellingpartnerapi-na.amazon.com") String baseUrl, @DefaultValue("https://api.amazon.com/auth/o2/token") String lwaUrl,
                         @DefaultValue("ATVPDKIKX0DER") List<String> marketplaceIds, @DefaultValue("PT24H") Duration lookback, @DefaultValue("PT5M") Duration pollInterval,
                         @DefaultValue("PT10S") Duration timeout) {
        public boolean credentialsPresent() { return present(clientId) && present(clientSecret) && present(refreshToken); }
    }

    /** Customer notifications (section 11 P0: Twilio, SendGrid). {@code enabled} installs {@code CompositeCustomerNotifier} as the primary notifier. */
    public record Notify(@DefaultValue("false") boolean enabled, @DefaultValue SendGrid sendgrid, @DefaultValue Twilio twilio, @DefaultValue("PT5S") Duration timeout) {}

    public record SendGrid(String apiKey, @DefaultValue("https://api.sendgrid.com") String baseUrl, @DefaultValue("orders@example.com") String fromEmail, @DefaultValue("Intent OMS") String fromName) {
        public boolean configured() { return present(apiKey); }
    }

    public record Twilio(String accountSid, String authToken, String from, @DefaultValue("https://api.twilio.com") String baseUrl) {
        public boolean configured() { return present(accountSid) && present(authToken) && present(from); }
    }

    /** Address verification (section 11 P0: USPS / Smarty). */
    public record Address(@DefaultValue("mock") String provider, @DefaultValue Smarty smarty) {}

    public record Smarty(String authId, String authToken, @DefaultValue("https://us-street.api.smarty.com") String baseUrl, @DefaultValue("PT1.5S") Duration timeout) {
        public boolean configured() { return present(authId) && present(authToken); }
    }

    /** Carrier aggregator (section 11 P0: EasyPost / ShipEngine). */
    public record Carrier(@DefaultValue("mock") String provider, @DefaultValue EasyPost easypost) {}

    public record EasyPost(String apiKey, @DefaultValue("https://api.easypost.com") String baseUrl, @DefaultValue("PT10S") Duration timeout) {
        public boolean configured() { return present(apiKey); }
    }

    /** WMS connector (section 11 P0: generic + one). The HTTP connector's endpoints and secret are per tenant in {@code tenant_config}. */
    public record Wms(@DefaultValue("mock") String provider, @DefaultValue Http http) {}

    public record Http(@DefaultValue("PT5S") Duration timeout, @DefaultValue("3") int retries, @DefaultValue("PT0.2S") Duration backoff) {}

    static boolean present(String s) { return s != null && !s.isBlank(); }
}
