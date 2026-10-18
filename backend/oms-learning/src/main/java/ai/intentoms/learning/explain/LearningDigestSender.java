package ai.intentoms.learning.explain;

import java.util.List;

/**
 * Delivers the weekly learning digest (XP-04) over a channel (email today). Implemented outside oms-learning by the
 * module that owns the provider; with no sender, or no recipients configured, the digest is stored and shown in the
 * Learning Console only.
 */
public interface LearningDigestSender {
    String channel();

    /** Sends to each recipient and returns those that were delivered. */
    List<String> send(List<String> recipients, String subject, String body);
}
