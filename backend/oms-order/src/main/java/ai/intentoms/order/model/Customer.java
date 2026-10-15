package ai.intentoms.order.model;

import java.util.Map;

/** Customer facts used by decisions (tier, history). PII (name/email/phone) is tokenised before any model call. */
public record Customer(String ref, String name, String email, String phone, String tier, boolean ageVerified, Map<String, Object> history) {}
