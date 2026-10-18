package ai.intentoms.learning.model;

/** Episode lifecycle (OC-04): open until a reward can be provisionally estimated, mature once every outcome term's
 * maturity window has elapsed and the reward is final. */
public enum EpisodeStatus { OPEN, PROVISIONAL, MATURE }
