package ai.intentoms.learning.model;

/** How far a learner may go without a human (requirements doc §4, GV-01). Default is L1 for every new learner. */
public enum AutonomyLevel {
    L0_OBSERVE,
    L1_RECOMMEND,
    L2_ACT_WITH_APPROVAL,
    L3_AUTONOMOUS
}
