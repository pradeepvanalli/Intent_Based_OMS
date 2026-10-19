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
    AGENT_REPUTATION(TypedValue.NumberValue.class),          // FD-05: subject=AGENT, 0..1 from mandate compliance, injection-screen hits and dispute rate
    // ---- fraud and abuse (FD-*)
    DISPUTE_RATE(TypedValue.NumberValue.class),              // FD-05: subject=AGENT or CUSTOMER, chargebacks / settled payments, 0..1
    FALSE_POSITIVE_RATE(TypedValue.NumberValue.class),       // FD-03: subject=DECISION, blocked orders later shown good / all blocked
    FEATURE_DRIFT_PSI(TypedValue.NumberValue.class),         // FD-07: subject=DECISION, population stability index of the input features this week vs baseline
    ADDRESS_REUSE_COUNT(TypedValue.NumberValue.class),       // FD-04: subject=ADDRESS (token), distinct customers using the address in 30 days
    // ---- fulfilment and promise (FS-*, CX-*)
    NODE_BACKLOG_BREACH_CURVE(TypedValue.ListValue.class),   // FS-05: subject=NODE, "<backlogUnits>:<breachProbability>" points
    NODE_REJECT_PENALTY(TypedValue.NumberValue.class),       // FS-06: subject=NODE, decaying penalty replacing sourcing.reject_penalty
    LANE_CALIBRATION_OFFSET(TypedValue.NumberValue.class),   // CX-01: subject=LANE, observed on-time rate minus the probability before lane calibration
    LANE_CALIBRATION_ERROR(TypedValue.NumberValue.class),    // CX-01: subject=LANE, residual after calibration: observed rate minus the probability orders were given
    PROMISE_BUFFER_DAYS(TypedValue.NumberValue.class),       // CX-02: subject=SEGMENT, buffer that maximises conversion x on-time
    WARN_EARLY_THRESHOLD(TypedValue.NumberValue.class),      // CX-03: subject=DECISION (sla_breach_risk), risk score that triggers a proactive notice
    APPEASEMENT_LEVEL(TypedValue.NumberValue.class),         // CX-04: subject=SEGMENT, credit amount that best retains within budget
    // ---- inventory (IU-*)
    STOCK_AGE_DAYS(TypedValue.NumberValue.class),            // IU-03: subject=SKU_NODE ("<sku>|<node>"), days since oldest unit was received
    BACKORDER_CONVERSION_RATE(TypedValue.NumberValue.class), // IU-06: subject=SKU, backorders fulfilled / backorders taken
    TRANSFER_RECOMMENDATION(TypedValue.ListValue.class),     // IU-04: subject=SKU, "<from>><to>:<units>" suggestions (recommend only)
    // ---- customer intelligence (CI-*)
    SEGMENT_MEMBERSHIP(TypedValue.TextValue.class),          // CI-02: subject=CUSTOMER, discovered segment id
    // ---- decision fabric (DF-*)
    PROVIDER_ACCURACY(TypedValue.NumberValue.class);         // DF-05: subject=DECISION, accuracy of one provider on labelled records

    private final Class<? extends TypedValue> shape;
    FactKey(Class<? extends TypedValue> shape) { this.shape = shape; }
    public Class<? extends TypedValue> shape() { return shape; }
}
