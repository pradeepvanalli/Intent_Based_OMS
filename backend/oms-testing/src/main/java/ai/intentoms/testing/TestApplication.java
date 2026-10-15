package ai.intentoms.testing;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/**
 * Minimal Spring Boot application for module integration tests; scans all ai.intentoms packages on the test classpath.
 * Bean names are fully qualified so same-named helper classes in different modules (e.g. each module's
 * IdempotentRequests) never clash.
 */
@SpringBootApplication
@ComponentScan(basePackages = "ai.intentoms", nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class TestApplication {}
