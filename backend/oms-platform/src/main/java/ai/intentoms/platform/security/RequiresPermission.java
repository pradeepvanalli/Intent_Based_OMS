package ai.intentoms.platform.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declarative permission check on controller or service methods; enforced by {@link PermissionAspect}. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    String[] value();
    /** When true, any of the listed permissions suffices; otherwise all are required. */
    boolean any() default true;
}
