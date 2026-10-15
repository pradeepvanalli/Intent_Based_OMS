package ai.intentoms.integrations.graphql;

import ai.intentoms.platform.error.OmsException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link OmsException} (RFC 9457 problem details on REST) onto GraphQL errors: the problem {@code code} and HTTP
 * status land in {@code extensions} ({@code code}, {@code status}, plus every detail such as {@code issues}), so a
 * client sees {@code validation_failed}, {@code forbidden}, {@code not_found}, {@code conflict} identically over both
 * transports. Anything else becomes a generic {@code internal_error} without leaking internals.
 */
@Component
public class OmsGraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {
    private static final Logger log = LoggerFactory.getLogger(OmsGraphQlExceptionResolver.class);

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof OmsException e) {
            Map<String, Object> ext = new LinkedHashMap<>();
            ext.put("code", e.code());
            ext.put("status", e.status().value());
            e.details().forEach(ext::put);
            return GraphqlErrorBuilder.newError(env).message(e.getMessage()).errorType(errorType(e.status())).extensions(ext).build();
        }
        if (ex instanceof IllegalArgumentException e) {
            return GraphqlErrorBuilder.newError(env).message(e.getMessage()).errorType(ErrorType.BAD_REQUEST).extensions(Map.of("code", "bad_request", "status", 400)).build();
        }
        log.error("Unhandled GraphQL error at {}", env.getExecutionStepInfo().getPath(), ex);
        return GraphqlErrorBuilder.newError(env).message("Internal error").errorType(ErrorType.INTERNAL_ERROR).extensions(Map.of("code", "internal_error", "status", 500)).build();
    }

    static ErrorType errorType(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> ErrorType.NOT_FOUND;
            case FORBIDDEN -> ErrorType.FORBIDDEN;
            case UNAUTHORIZED -> ErrorType.UNAUTHORIZED;
            case BAD_REQUEST, UNPROCESSABLE_ENTITY, CONFLICT, TOO_MANY_REQUESTS -> ErrorType.BAD_REQUEST;
            default -> ErrorType.INTERNAL_ERROR;
        };
    }
}
