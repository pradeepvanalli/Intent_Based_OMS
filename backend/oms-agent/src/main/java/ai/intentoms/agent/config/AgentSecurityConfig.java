package ai.intentoms.agent.config;

import ai.intentoms.agent.auth.AgentAuthFilter;
import ai.intentoms.platform.security.AuthenticationFilter;
import ai.intentoms.platform.tenant.TenantFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Security chain for the agent-facing endpoints ({@code /mcp}, {@code /ucp/**}) (FR-AGT-03, GR-14). It runs ahead of
 * the platform chain and adds {@link AgentAuthFilter} after the platform {@link AuthenticationFilter}: API keys and JWTs
 * resolve first, signed agent requests (HMAC headers) are verified next, then the tenant is bound. Authorisation is
 * enforced by the controllers themselves (JSON-RPC error codes for MCP, problem details for UCP), so the chain permits all.
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentSecurityConfig {

    /** The filter is a bean so it can be injected into the chain; disable its automatic servlet registration. */
    @Bean
    FilterRegistrationBean<AgentAuthFilter> agentAuthFilterRegistration(AgentAuthFilter f) {
        FilterRegistrationBean<AgentAuthFilter> reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    @Order(1)
    SecurityFilterChain agentFilterChain(HttpSecurity http, AuthenticationFilter authFilter, AgentAuthFilter agentFilter, TenantFilter tenantFilter) throws Exception {
        http.securityMatcher("/mcp", "/mcp/**", "/ucp/**")
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(authFilter, AnonymousAuthenticationFilter.class)
            .addFilterAfter(agentFilter, AuthenticationFilter.class)
            .addFilterAfter(tenantFilter, AgentAuthFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
