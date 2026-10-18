package ai.intentoms.learning.model;

/**
 * Known learned-fact keys and the {@link TypedValue} shape each one declares, so a learner can only write a value
 * its key allows (requirements doc §6.2's "bounded by FactKey's declared range"). New keys are added here, never
 * invented freehand by a learner.
 */
public enum FactKey {
    SPLIT_TOLERANCE(TypedValue.NumberValue.class),       // CI-01: 0..1, likelihood this subject accepts a split
    SPEED_VS_COST(TypedValue.NumberValue.class),          // CI-01: -1 (cost-sensitive) .. 1 (speed-sensitive)
    PICKUP_PREFERENCE(TypedValue.BoolValue.class),        // CI-01
    PREFERRED_CONTACT_CHANNEL(TypedValue.TextValue.class),// CI-01: email | sms | push
    RTO_RISK(TypedValue.NumberValue.class),                // FD-02: 0..1
    NODE_ON_TIME_RATE(TypedValue.NumberValue.class),       // FS-02: Beta-binomial posterior mean, 0..1
    NODE_REJECT_RATE(TypedValue.NumberValue.class),        // FS-02
    LANE_TRANSIT_DAYS_P50(TypedValue.NumberValue.class),   // FS-03
    LANE_TRANSIT_DAYS_P90(TypedValue.NumberValue.class),   // FS-03
    AGED_STOCK_WEIGHT(TypedValue.NumberValue.class),       // IU-03
    SAFETY_STOCK_UNITS(TypedValue.NumberValue.class),      // IU-01
    RESERVATION_TTL_MINUTES(TypedValue.NumberValue.class), // IU-02
    INBOUND_RELIABILITY(TypedValue.NumberValue.class),     // IU-05: supplier/ASN reliability, 0..1
    SOURCING_POLICY_PROPENSITY(TypedValue.ListValue.class),// FS-01: the bandit's current arm-selection weights
    LANE_ON_TIME_RATE(TypedValue.NumberValue.class),       // CX-01: subject=LANE ("<carrier>|<originNodeId>"), observed on-time rate
    CALIBRATION_ERROR(TypedValue.NumberValue.class),        // DF-02: subject=DECISION, latest measured calibration error
    CALIBRATION_CURVE(TypedValue.ListValue.class),          // DF-02: subject=DECISION, "<lower>-<upper>:<correction>" per confidence bin
    PROVIDER_COST_PER_CALL(TypedValue.NumberValue.class),   // DF-05: subject=DECISION, keyed per learner run against one provider at a time
    AGENT_REPUTATION(TypedValue.NumberValue.class);          // FD-05: subject=AGENT, 0..1 from mandate compliance, injection-screen hits and dispute rate

    private final Class<? extends TypedValue> shape;
    FactKey(Class<? extends TypedValue> shape) { this.shape = shape; }
    public Class<? extends TypedValue> shape() { return shape; }
}
