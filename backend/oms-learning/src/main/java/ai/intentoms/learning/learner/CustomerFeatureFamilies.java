package ai.intentoms.learning.learner;

/**
 * Live-use families for customer facts (CI-05). Customer preference and segment facts reach a decision's input only
 * while {@link #PREFERENCES} is promoted for live use and the customer learners are on; until then they are recorded on
 * the decision as "available but not served".
 */
public final class CustomerFeatureFamilies {
    private CustomerFeatureFamilies() {}

    public static final String PREFERENCES = "customer.preferences";
}
