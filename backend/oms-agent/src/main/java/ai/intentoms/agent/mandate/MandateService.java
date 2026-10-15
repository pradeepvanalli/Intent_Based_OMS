package ai.intentoms.agent.mandate;

import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.agent.screen.AgentTextSanitizer;
import ai.intentoms.agent.screen.InjectionScreen;
import ai.intentoms.agent.screen.ScreenResult;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mandates: the buyer's delegation an agent orders under (FR-CAP-07, FR-FIN-06, AP2-style). Issuance verifies the
 * signature (public key when the agent registered one, else HMAC with the agent secret), screens the instruction text for
 * injected instructions (GR-08) and stores the result as evidence. {@link #verify} is the gate every agent order passes:
 * the mandate must exist, belong to the agent, be active and unexpired, cover the order total in the same currency and,
 * when a buyer reference is supplied, match it. Mandates are single-use: {@link #consume} marks them consumed.
 * Events on aggregate {@code agent/<agentId>}: {@code mandate.created|consumed|revoked}, {@code agent.injection_flagged}.
 */
@Service
public class MandateService {
    public static final String REASON_NOT_FOUND = "mandate_not_found";
    public static final String REASON_AGENT_MISMATCH = "mandate_agent_mismatch";
    public static final String REASON_NOT_ACTIVE = "mandate_not_active";
    public static final String REASON_EXPIRED = "mandate_expired";
    public static final String REASON_SPEND_LIMIT = "mandate_spend_limit_exceeded";
    public static final String REASON_CURRENCY = "mandate_currency_mismatch";
    public static final String REASON_BUYER = "mandate_buyer_mismatch";
    public static final String REASON_SIGNATURE = "mandate_signature_invalid";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final MandateRepository repo;
    private final AgentRegistry registry;
    private final InjectionScreen screen;
    private final EventStore events;
    private final TimeSource time;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public MandateService(MandateRepository repo, AgentRegistry registry, InjectionScreen screen, EventStore events, TimeSource time, AuthorizationService authz, Metrics metrics) {
        this.repo = repo;
        this.registry = registry;
        this.screen = screen;
        this.events = events;
        this.time = time;
        this.authz = authz;
        this.metrics = metrics;
    }

    @Transactional
    public Mandate create(MandateCreate req) {
        if (req == null) throw new ValidationException("request body is required");
        if (req.agentId() == null || req.agentId().isBlank()) throw new ValidationException("agentId is required");
        if (req.spendLimit() == null || req.spendLimit().isNegative()) throw new ValidationException("spendLimit (non-negative Money) is required");
        AgentIdentity agent = registry.find(req.agentId()).orElseThrow(() -> new NotFoundException("Agent " + req.agentId() + " not found"));
        Instant now = time.now();
        Instant expires = req.expiresAt() != null ? req.expiresAt() : now.plus(req.ttl() == null ? DEFAULT_TTL : req.ttl());
        if (!expires.isAfter(now)) throw new ValidationException("expiresAt must be in the future");
        String id = req.id() == null || req.id().isBlank() ? Ids.prefixed("mnd") : req.id().trim();
        if (repo.find(id).isPresent()) throw new ConflictException("Mandate " + id + " already exists", Map.of("mandateId", id));
        MandateScope scope;
        try { scope = MandateScope.parse(req.scope()); } catch (IllegalArgumentException e) { throw new ValidationException("scope: " + e.getMessage()); }
        String instruction = AgentTextSanitizer.sanitize(req.instructionText());

        Boolean signatureValid = verifySignature(agent, req.signature(), req.buyerRef(), scope, req.spendLimit(), instruction, expires);
        ScreenResult sr = screen.screen("mandate_instruction", instruction, "mandate", id);

        Mandate m = new Mandate(id, agent.id(), blank(req.buyerRef()), scope, req.spendLimit(), instruction, blank(req.signature()), signatureValid, blank(req.paymentToken()),
            sr.probability(), sr.decisionRecordId(), now, expires, Mandate.ACTIVE, List.of(), now, now);
        repo.insert(m);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mandateId", id);
        payload.put("agentId", agent.id());
        payload.put("buyerRef", m.buyerRef());
        payload.put("spendLimit", Map.of("amount", m.spendLimit().amount(), "currency", m.spendLimit().currency()));
        payload.put("scope", scope.labels());
        payload.put("expiresAt", expires.toString());
        payload.put("signatureValid", signatureValid);
        payload.put("paymentTokenPresent", m.paymentTokenPresent());
        payload.put("injectionRisk", sr.probability());
        append(agent.id(), "mandate.created", payload, id);
        if (sr.flagged()) {
            append(agent.id(), "agent.injection_flagged", Map.of("mandateId", id, "source", sr.source(), "probability", sr.probability(), "recordId", String.valueOf(sr.decisionRecordId())), id);
        }
        metrics.count("oms.agent.mandate", "outcome", "created", "signed", String.valueOf(signatureValid != null));
        return m;
    }

    public Mandate get(String mandateId) { return find(mandateId).orElseThrow(() -> new NotFoundException("Mandate " + mandateId + " not found")); }

    public Optional<Mandate> find(String mandateId) { return repo.find(mandateId).map(this::effective); }

    public List<Mandate> listForAgent(String agentId) { return repo.forAgent(agentId).stream().map(this::effective).toList(); }

    public List<Mandate> list(int limit) { return repo.list(limit).stream().map(this::effective).toList(); }

    public MandateCheck verify(String mandateId, String agentId, Money orderTotal) { return verify(mandateId, agentId, orderTotal, null); }

    /** The order gate (FR-CAP-07): see class Javadoc for the rules. Never throws on a missing mandate. */
    public MandateCheck verify(String mandateId, String agentId, Money orderTotal, String buyerRef) {
        if (mandateId == null || mandateId.isBlank()) return new MandateCheck(false, List.of(REASON_NOT_FOUND), null);
        Optional<Mandate> m = repo.find(mandateId);
        if (m.isEmpty()) return new MandateCheck(false, List.of(REASON_NOT_FOUND), null);
        return check(m.get(), agentId, orderTotal, buyerRef, time.now());
    }

    /** Pure rule set behind {@link #verify}; package-visible for unit tests. */
    public static MandateCheck check(Mandate m, String agentId, Money orderTotal, String buyerRef, Instant now) {
        List<String> reasons = new ArrayList<>();
        if (agentId == null || !agentId.equals(m.agentId())) reasons.add(REASON_AGENT_MISMATCH);
        if (m.expired(now)) reasons.add(REASON_EXPIRED);
        else if (!Mandate.ACTIVE.equals(m.status())) reasons.add(REASON_NOT_ACTIVE);
        if (orderTotal != null) {
            if (!orderTotal.currency().equals(m.spendLimit().currency())) reasons.add(REASON_CURRENCY);
            else if (orderTotal.compareTo(m.spendLimit()) > 0) reasons.add(REASON_SPEND_LIMIT);
        }
        if (buyerRef != null && !buyerRef.isBlank() && m.buyerRef() != null && !m.buyerRef().isBlank() && !buyerRef.equals(m.buyerRef())) reasons.add(REASON_BUYER);
        if (Boolean.FALSE.equals(m.signatureValid())) reasons.add(REASON_SIGNATURE);
        return reasons.isEmpty() ? MandateCheck.ok(m) : new MandateCheck(false, reasons, m);
    }

    @Transactional
    public Mandate consume(String mandateId, String orderId) {
        Mandate m = get(mandateId);
        List<String> used = new ArrayList<>(m.usedOrderIds());
        if (!used.contains(orderId)) used.add(orderId);
        repo.updateStatus(mandateId, Mandate.CONSUMED, used);
        append(m.agentId(), "mandate.consumed", Map.of("mandateId", mandateId, "orderId", orderId), mandateId);
        metrics.count("oms.agent.mandate", "outcome", "consumed", "signed", String.valueOf(m.signatureValid() != null));
        return m.withStatus(Mandate.CONSUMED, used);
    }

    @Transactional
    public Mandate revoke(String mandateId, String reason) {
        Mandate m = get(mandateId);
        if (Mandate.REVOKED.equals(m.status())) return m;
        repo.updateStatus(mandateId, Mandate.REVOKED, m.usedOrderIds());
        append(m.agentId(), "mandate.revoked", Map.of("mandateId", mandateId, "reason", reason == null ? "" : reason), mandateId);
        metrics.count("oms.agent.mandate", "outcome", "revoked", "signed", String.valueOf(m.signatureValid() != null));
        return m.withStatus(Mandate.REVOKED, m.usedOrderIds());
    }

    /** Signature check: null when unsigned; public key when registered, else HMAC with the agent secret. */
    private Boolean verifySignature(AgentIdentity agent, String signature, String buyerRef, MandateScope scope, Money limit, String instruction, Instant expires) {
        if (signature == null || signature.isBlank()) return null;
        String canonical = MandateSigner.canonicalJson(buyerRef, scope, limit, instruction, expires);
        Optional<String> pk = registry.publicKey(agent.id());
        if (pk.isPresent()) {
            try { return MandateSigner.publicKeyVerify(MandateSigner.parsePublicKey(pk.get()), canonical, signature); }
            catch (IllegalArgumentException e) { return false; }
        }
        return MandateSigner.hmacVerify(registry.hmacSecret(agent.id()), canonical, signature);
    }

    private Mandate effective(Mandate m) {
        String s = m.effectiveStatus(time.now());
        return s.equals(m.status()) ? m : m.withStatus(s, m.usedOrderIds());
    }

    private void append(String agentId, String type, Map<String, Object> payload, String mandateId) {
        Actor actor = authz.currentPrincipal().map(p -> Actor.parse(p.actorRef())).orElse(Actor.system("mandate-service"));
        events.append(AgentRegistry.AGGREGATE, agentId, new NewEvent(type, new LinkedHashMap<>(payload), actor, null, mandateId, Map.of("mandateId", mandateId)));
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
