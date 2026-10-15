package ai.intentoms.workflow;

import ai.intentoms.workflow.engine.WorkflowProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wires the workflow module's deploy-time properties ({@code oms.workflow.*}). */
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowConfig {}
