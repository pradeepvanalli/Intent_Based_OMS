package ai.intentoms.platform.security;

import ai.intentoms.platform.tenant.TenantFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /**
     * Both filters are Spring beans so they can be injected into the security chain; disable their automatic servlet
     * registration so each request passes through them exactly once (inside the chain, after the security context filter).
     */
    @Bean
    FilterRegistrationBean<AuthenticationFilter> authenticationFilterRegistration(AuthenticationFilter f) {
        FilterRegistrationBean<AuthenticationFilter> reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantFilter f) {
        FilterRegistrationBean<TenantFilter> reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SecurityProperties props, AuthenticationFilter authFilter, TenantFilter tenantFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(authFilter, AnonymousAuthenticationFilter.class)
            .addFilterAfter(tenantFilter, AuthenticationFilter.class)
            .authorizeHttpRequests(auth -> {
                for (String p : props.publicPaths()) auth.requestMatchers(p).permitAll();
                auth.requestMatchers("/mcp/**", "/ucp/**", "/webhooks/**").permitAll(); // these verify their own signatures
                // Single-page UI deep links (e.g. /orders/ord_123): any GET outside the API/ops surfaces serves index.html.
                auth.requestMatchers(req -> "GET".equals(req.getMethod()) && !isProtectedPrefix(req.getRequestURI())).permitAll();
                auth.anyRequest().authenticated();
            });
        return http.build();
    }

    private static boolean isProtectedPrefix(String uri) {
        return uri.startsWith("/api/") || uri.startsWith("/actuator") || uri.startsWith("/v3/") || uri.startsWith("/mcp") || uri.startsWith("/ucp/") || uri.startsWith("/webhooks/");
    }
}
