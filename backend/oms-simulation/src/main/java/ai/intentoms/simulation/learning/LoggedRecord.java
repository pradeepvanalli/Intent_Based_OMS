package ai.intentoms.simulation.learning;

/**
 * One logged decision + its matured episode reward, in the minimal shape the off-policy estimator needs
 * (EX-01). Decoupled from {@code DecisionRecord}/{@code Episode} so the estimator stays a pure, independently
 * testable function of plain data.
 */
public record LoggedRecord(String chosenArm, double propensity, double confidence, String band, double reward) {}
