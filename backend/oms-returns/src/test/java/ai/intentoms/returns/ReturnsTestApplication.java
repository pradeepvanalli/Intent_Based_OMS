package ai.intentoms.returns;

import ai.intentoms.testing.TestApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/**
 * Test application for the returns module: scans every {@code ai.intentoms} package like the shared
 * {@code TestApplication} but names beans by their fully-qualified class, so same-named helper classes in different
 * modules (e.g. {@code IdempotentRequests} in finance, inventory and order) cannot collide and block the context.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "ai.intentoms", nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = {TypeExcludeFilter.class, AutoConfigurationExcludeFilter.class}),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = TestApplication.class)
    })
public class ReturnsTestApplication {}
