package ai.intentoms.learning.model;

/**
 * Reward-term weights (requirements doc §7): R(e) = fill*FillRate + otd*OnTime - cost*(Cost/OrderValue) -
 * split*Splits - fraud*FraudLoss - cx*Contacts + inv*AgedStockRelief. Every term is normalized to [0,1] against the
 * tenant's own replay baseline before these weights are applied, so weights mean the same thing across tenants.
 */
public record ObjectiveWeights(double fill, double otd, double cost, double split, double fraud, double cx, double inv) {
    public static ObjectiveWeights preset(String name) {
        return switch (name) {
            case "margin_first" -> new ObjectiveWeights(0.8, 1.0, 1.6, 0.9, 1.3, 0.6, 0.7);
            case "speed_first" -> new ObjectiveWeights(0.9, 1.6, 0.8, 0.5, 1.0, 0.6, 0.5);
            default /* balanced */ -> new ObjectiveWeights(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
        };
    }
}
