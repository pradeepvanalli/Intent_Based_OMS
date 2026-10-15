package ai.intentoms.integrations.config;

import ai.intentoms.integrations.graphql.GraphQlAsyncSecurityFilter;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Module wiring: binds {@link IntegrationsProperties} and provides the HTTP client factory every outbound adapter uses
 * (JDK HttpClient pinned to HTTP/1.1 — every provider here speaks it and h2c upgrades confuse proxies — with explicit connect / read timeouts). Scheduling is enabled platform-wide
 * ({@code PlatformConfig}), so the marketplace poller only needs its {@code @ConditionalOnProperty} gate.
 */
@Configuration
@EnableConfigurationProperties(IntegrationsProperties.class)
public class IntegrationsConfig {

    /** A {@link RestClient} bound to {@code baseUrl} with {@code timeout} as both connect and read timeout. */
    public static RestClient client(RestClient.Builder builder, String baseUrl, Duration timeout) {
        Duration t = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(5) : timeout;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(t).followRedirects(HttpClient.Redirect.NORMAL).build());
        factory.setReadTimeout(t);
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    IntegrationsStatus integrationsStatus(IntegrationsProperties props) { return new IntegrationsStatus(props); }

    /** Registered just after the Spring Security chain and only for the GraphQL endpoint (see {@link GraphQlAsyncSecurityFilter}). */
    @Bean
    FilterRegistrationBean<GraphQlAsyncSecurityFilter> graphQlAsyncSecurityFilter() {
        FilterRegistrationBean<GraphQlAsyncSecurityFilter> reg = new FilterRegistrationBean<>(new GraphQlAsyncSecurityFilter());
        reg.addUrlPatterns("/graphql");
        reg.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        reg.setName("graphQlAsyncSecurityFilter");
        return reg;
    }
}
