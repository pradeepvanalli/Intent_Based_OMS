package ai.intentoms.platform.api;

import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.pii.Tokenizer;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GV-06 / CCPA-GDPR: erase one customer, or crypto-shred the tenant's whole PII key. Both are platform-admin actions
 * ({@code privacy.erase}), written to the ledger (aggregate {@code privacy}) and the audit log. Neither the ledger
 * event nor the audit row carries the erased customer reference: they name its pseudonym.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class PrivacyController {
    private final Tokenizer tokenizer;
    private final EventStore events;
    private final AuditLog audit;
    private final AuthorizationService authz;

    public PrivacyController(Tokenizer tokenizer, EventStore events, AuditLog audit, AuthorizationService authz) {
        this.tokenizer = tokenizer;
        this.events = events;
        this.audit = audit;
        this.authz = authz;
    }

    /** Erases the customer's PII and everything derived from it; idempotent. */
    @PostMapping("/customers/{customerRef}/erase")
    @RequiresPermission(Permissions.PRIVACY_ERASE)
    public Map<String, Object> erase(@PathVariable String customerRef) {
        if (customerRef == null || customerRef.isBlank()) throw new ValidationException("customerRef is required");
        String by = authz.requirePrincipal().actorRef();
        Tokenizer.Erasure e = tokenizer.shredSubject(customerRef, by);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pseudonym", e.pseudonym());
        payload.put("firstTime", e.firstTime());
        payload.put("erasedAt", e.erasedAt().toString());
        append("customer:" + e.pseudonym(), "customer.erased", by, payload);
        audit.record("privacy.customer_erased", "customer", e.pseudonym(), null, payload);
        Map<String, Object> out = new LinkedHashMap<>(payload);
        out.put("erased", true);
        return out;
    }

    /** Rotates the tenant's PII key: every historical token becomes unlinkable and token-derived learning data goes. */
    @PostMapping("/pii/rotate-key")
    @RequiresPermission(Permissions.PRIVACY_ERASE)
    public Map<String, Object> rotateKey(@RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) throw new ValidationException("Rotating the PII key makes every existing token unlinkable; repeat with confirm=true");
        String by = authz.requirePrincipal().actorRef();
        String tenant = TenantContext.require();
        tokenizer.shred(tenant);
        Map<String, Object> payload = Map.of("tenant", tenant);
        append("pii-key", "pii.key_rotated", by, payload);
        audit.record("privacy.pii_key_rotated", "tenant", tenant, null, payload);
        return Map.of("rotated", true);
    }

    private void append(String aggregateId, String type, String by, Map<String, Object> payload) {
        Map<String, Object> withDigest = new LinkedHashMap<>(payload);
        withDigest.put("payloadDigest", Json.sha256(payload));
        events.append("privacy", aggregateId, new NewEvent(type, withDigest, Actor.parse(by)));
    }
}
