package ai.intentoms.order.service;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.order.config.OrderConfigKeys;
import ai.intentoms.order.model.IntentProfile;
import ai.intentoms.order.model.Note;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderType;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalises the buyer's fulfilment intent (FR-CAP-06) with the Jev Choice {@code fulfilment_intent}: explicit choices and
 * free-text notes go in as data (GR-08) and come back as a typed {@link IntentProfile}. Called by the order workflow after
 * capture, never during it (GR-07). When the decision does not act (escalate/confirm band, default layer, unclear) the
 * order falls back to the order type's channel default, else {@code tenant_config sourcing.channel_default_policy}.
 */
@Service
public class IntentService {
    private static final Logger log = LoggerFactory.getLogger(IntentService.class);
    public static final String DECISION = "fulfilment_intent";
    static final String FALLBACK_POLICY = "ship_complete";

    private final DecisionService decisions;
    private final OrderService orders;
    private final OrderTypeRegistry orderTypes;
    private final TenantConfigStore config;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public IntentService(DecisionService decisions, OrderService orders, OrderTypeRegistry orderTypes, TenantConfigStore config, AuthorizationService authz, Metrics metrics) {
        this.decisions = decisions;
        this.orders = orders;
        this.orderTypes = orderTypes;
        this.config = config;
        this.authz = authz;
        this.metrics = metrics;
    }

    /** Resolves and persists the intent; an explicit buyer choice is kept as is without a decision. */
    public IntentProfile inferIntent(Order order) {
        if (order.intent() != null && "explicit".equals(order.intent().source()) && !"unclear".equals(order.intent().policy())) return order.intent();
        Actor actor = Actors.current(authz);
        DecisionResult r = decisions.decide(DECISION, input(order), DecisionOptions.live("order", order.id(), actor));
        IntentProfile profile = toProfile(order, r);
        metrics.count("oms.order.intent", "source", profile.source(), "policy", profile.policy());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policy", profile.policy());
        payload.put("confidence", profile.confidence());
        payload.put("source", profile.source());
        payload.put("decisionRecordId", r.recordId());
        payload.put("band", r.band() == null ? null : r.band().name());
        payload.put("layer", r.layer() == null ? null : r.layer().name());
        payload.put("action", r.action());
        payload.put("splitOk", profile.splitOk());
        payload.put("isGift", profile.isGift());
        orders.update(order.id(), o -> o.withIntent(profile), new NewEvent("order.intent_resolved", payload, r.recordId() == null ? actor : Actor.decision(r.recordId())));
        log.info("Intent for {} -> {} ({}, confidence {}, record {})", order.id(), profile.policy(), profile.source(), profile.confidence(), r.recordId());
        return profile;
    }

    /** Decision input (MODULE_NOTES demo shape): {@code order{channel, notes, intent_choices, has_hazmat}, customer{tier, history}}. */
    static Map<String, Object> input(Order order) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", order.id());
        o.put("channel", order.channel());
        o.put("notes", order.notes() == null ? List.of() : order.notes().stream().map(Note::text).filter(t -> t != null && !t.isBlank()).toList());
        o.put("intent_choices", order.intent() == null || order.intent().explicitChoices() == null ? List.of() : order.intent().explicitChoices());
        o.put("has_hazmat", order.hasHazmat());
        o.put("order_type", order.orderType());
        o.put("line_count", order.lines().size());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tier", order.customer() == null || order.customer().tier() == null ? "standard" : order.customer().tier());
        c.put("history", order.customer() == null || order.customer().history() == null ? Map.of() : order.customer().history());
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("order", o);
        in.put("customer", c);
        return in;
    }

    IntentProfile toProfile(Order order, DecisionResult r) {
        List<String> choices = order.intent() == null ? List.of() : order.intent().explicitChoices();
        Boolean splitOk = noul(r.answers().get("split_ok"));
        Boolean isGift = noul(r.answers().get("is_gift"));
        String value = r.value() == null ? null : String.valueOf(r.value());
        if (r.acts() && value != null && DefaultOrderService.POLICIES.contains(value)) {
            return new IntentProfile(value, r.confidence(), "inferred", choices, r.recordId(), splitOk, isGift);
        }
        return new IntentProfile(channelDefault(order), r.confidence(), "channel_default", choices, r.recordId(), splitOk, isGift);
    }

    String channelDefault(Order order) {
        try {
            OrderType t = orderTypes.get(order.orderType());
            String p = t.channelDefault(order.channel());
            if (p != null) return p;
        } catch (NotFoundException ignored) { }
        Object p = config.getMap(OrderConfigKeys.CHANNEL_DEFAULT_POLICY).get(order.channel());
        return p == null ? FALLBACK_POLICY : String.valueOf(p);
    }

    private static Boolean noul(Answer a) {
        if (a == null) return null;
        if (a.value() instanceof Boolean b) return b;
        double d = a.asDouble();
        return Double.isNaN(d) ? null : d >= 0.5;
    }
}
