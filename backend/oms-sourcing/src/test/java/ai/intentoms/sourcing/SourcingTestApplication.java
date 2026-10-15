package ai.intentoms.sourcing;

import ai.intentoms.testing.TestApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/**
 * Test application for this module. Identical to {@code ai.intentoms.testing.TestApplication} except that beans are
 * named by their fully-qualified class name: oms-finance, oms-inventory and oms-order each ship a component called
 * {@code IdempotentRequests}, which collide under the default short-name generator once a module depends on all of them.
 * The shared {@link TestApplication} is excluded from the scan so its default-named scan does not run as well.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "ai.intentoms", nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TestApplication.class))
public class SourcingTestApplication {}
