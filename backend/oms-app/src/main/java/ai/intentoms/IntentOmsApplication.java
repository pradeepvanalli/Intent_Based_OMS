package ai.intentoms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/** Intent OMS entry point: one deployable assembling every module (ADR-001). Bean names are fully qualified to avoid cross-module clashes. */
@SpringBootApplication
@ComponentScan(basePackages = "ai.intentoms", nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class IntentOmsApplication {
    public static void main(String[] args) { SpringApplication.run(IntentOmsApplication.class, args); }
}
