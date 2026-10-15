package ai.intentoms.platform.security;

import ai.intentoms.platform.error.ForbiddenException;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
public class PermissionAspect {
    private final AuthorizationService authz;

    public PermissionAspect(AuthorizationService authz) { this.authz = authz; }

    @Before("@annotation(ai.intentoms.platform.security.RequiresPermission) || @within(ai.intentoms.platform.security.RequiresPermission)")
    public void check(JoinPoint jp) {
        MethodSignature sig = (MethodSignature) jp.getSignature();
        RequiresPermission ann = sig.getMethod().getAnnotation(RequiresPermission.class);
        if (ann == null) ann = jp.getTarget().getClass().getAnnotation(RequiresPermission.class);
        if (ann == null) return;
        Principal p = authz.requirePrincipal();
        boolean ok = ann.any() ? Arrays.stream(ann.value()).anyMatch(p::has) : Arrays.stream(ann.value()).allMatch(p::has);
        if (!ok) throw new ForbiddenException("Missing permission " + String.join(ann.any() ? " | " : " & ", ann.value()));
    }
}
