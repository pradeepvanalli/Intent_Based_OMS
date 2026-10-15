package ai.intentoms.integrations.graphql;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Keeps {@code POST /graphql} authenticated across the servlet ASYNC dispatch (FR-CAP-01, NFR-07). Spring for GraphQL's
 * MVC handler completes the response asynchronously; the container then re-dispatches the request and Spring Security's
 * {@code AuthorizationFilter} evaluates it again. The platform {@code AuthenticationFilter} binds the principal to the
 * thread only (and is a once-per-request filter, so it does not run on the ASYNC dispatch), which would make the second
 * pass anonymous and answer 401. This filter runs right after the security chain on the initial dispatch, limited to the
 * GraphQL path, and stores the authenticated context in the {@link RequestAttributeSecurityContextRepository} that the
 * chain's {@code SecurityContextHolderFilter} reads back on the ASYNC dispatch. REST endpoints are synchronous and unaffected.
 */
public class GraphQlAsyncSecurityFilter extends OncePerRequestFilter {
    private final RequestAttributeSecurityContextRepository repository = new RequestAttributeSecurityContextRepository();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context.getAuthentication() != null && !repository.containsContext(request)) repository.saveContext(context, request, response);
        chain.doFilter(request, response);
    }
}
