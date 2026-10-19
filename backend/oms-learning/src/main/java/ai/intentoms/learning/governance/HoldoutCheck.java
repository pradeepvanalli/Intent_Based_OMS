package ai.intentoms.learning.governance;

/** EX-04: whether a routing key ({@link LearningSubject}) belongs to the tenant's persistent holdout. */
public interface HoldoutCheck {
    boolean inHoldout(String routingKey);
}
