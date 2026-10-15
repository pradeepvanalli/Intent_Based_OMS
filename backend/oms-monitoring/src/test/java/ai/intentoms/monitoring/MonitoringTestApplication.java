package ai.intentoms.monitoring;

import ai.intentoms.testing.TestApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/**
 * Test application for this module: scans every {@code ai.intentoms} package on the test classpath (like
 * {@code TestApplication}) but names beans by fully-qualified class so same-named components in different modules
 * (e.g. an {@code IdempotentRequests} helper in two modules) can coexist. The shared {@code TestApplication} is excluded
 * because its own component scan would re-register everything under simple names.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "ai.intentoms", nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TestApplication.class))
public class MonitoringTestApplication {}
