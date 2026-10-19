package ai.intentoms.platform.security;

/** Canonical permission names. Modules add their own constants following the same "<domain>.<action>" grammar. */
public final class Permissions {
    private Permissions() {}
    public static final String ORDER_READ = "order.read";
    public static final String ORDER_CREATE = "order.create";
    public static final String ORDER_MODIFY = "order.modify";
    public static final String ORDER_CANCEL = "order.cancel";
    public static final String ORDER_HOLD = "order.hold";
    public static final String ORDER_RELEASE = "order.release";
    public static final String ORDER_BULK = "order.bulk";
    public static final String RETURN_CREATE = "return.create";
    public static final String RETURN_DECIDE = "return.decide";
    public static final String REFUND_ISSUE = "refund.issue";
    public static final String REFUND_APPROVE = "refund.approve";
    public static final String INVENTORY_READ = "inventory.read";
    public static final String INVENTORY_WRITE = "inventory.write";
    public static final String PROMISE_READ = "promise.read";
    public static final String DECISION_READ = "decision.read";
    public static final String DECISION_EDIT = "decision.edit";
    public static final String DECISION_APPROVE = "decision.approve";
    public static final String DECISION_PUBLISH = "decision.publish";
    public static final String DECISION_OVERRIDE = "decision.override";
    public static final String STATEMODEL_EDIT = "statemodel.edit";
    public static final String STATEMODEL_PUBLISH = "statemodel.publish";
    public static final String TASK_READ = "task.read";
    public static final String TASK_WORK = "task.work";
    public static final String EXCEPTION_READ = "exception.read";
    public static final String EXCEPTION_WORK = "exception.work";
    public static final String AGENT_ADMIN = "agent.admin";
    public static final String CONFIG_ADMIN = "config.admin";
    public static final String ANALYTICS_READ = "analytics.read";
    public static final String SIMULATION_RUN = "simulation.run";
    public static final String APPEASEMENT_ISSUE = "appeasement.issue";
    public static final String MARGIN_FLOOR_APPROVE = "margin.approve";
    public static final String LEARNING_READ = "learning.read";
    public static final String LEARNING_ADMIN = "learning.admin";
    /** Right-to-erasure and PII key rotation (GV-06); only platform admins hold it (via "*"). */
    public static final String PRIVACY_ERASE = "privacy.erase";
}
