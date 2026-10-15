package ai.intentoms.agent.ucp;

import ai.intentoms.agent.AgentOrderService;
import ai.intentoms.agent.AgentToolException;
import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.agent.auth.AgentAuthFilter;
import ai.intentoms.agent.mandate.Mandate;
import ai.intentoms.agent.mandate.MandateService;
import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.agent.screen.AgentTextSanitizer;
import ai.intentoms.agent.screen.InjectionScreen;
import ai.intentoms.agent.screen.ScreenResult;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.error.UnauthorizedException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.returns.model.ReturnChannel;
import ai.intentoms.returns.model.ReturnLineRequest;
import ai.intentoms.returns.model.ReturnRequest;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.service.ReturnService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UCP order capability (FR-AGT-02, spec version {@value UcpVersions#DEFAULT}): order retrieval with per-line fulfilment,
 * tracking and returns; order placement under a mandate; return creation; the customer-safe event ledger. Version is
 * negotiated through {@code UCP-Version} and echoed on every response. Authentication comes from the agent chain (signed
 * headers, API key or JWT); tier-0 agents only see orders they placed (FR-AGT-03).
 */
@RestController
@RequestMapping("/ucp/v1/orders")
public class UcpController {
    private final OrderService orders;
    private final AgentOrderService placer;
    private final MandateService mandates;
    private final AgentRegistry registry;
    private final ReturnService returns;
    private final InjectionScreen screen;
    private final EventStore events;
    private final AuthorizationService authz;

    public UcpController(OrderService orders, AgentOrderService placer, MandateService mandates, AgentRegistry registry, ReturnService returns, InjectionScreen screen, EventStore events, AuthorizationService authz) {
        this.orders = orders;
        this.placer = placer;
        this.mandates = mandates;
        this.registry = registry;
        this.returns = returns;
        this.screen = screen;
        this.events = events;
        this.authz = authz;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UcpReturnRequest(@JsonProperty("line_items") List<UcpReturnLine> lineItems, String reason, @JsonProperty("receipt_present") Boolean receiptPresent) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UcpReturnLine(@JsonProperty("line_id") String lineId, int quantity, @JsonProperty("reason_code") String reasonCode) {}

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id, @RequestHeader(value = UcpVersions.HEADER, required = false) String versionHeader) {
        String version = UcpVersions.negotiate(versionHeader);
        Principal p = require(Permissions.ORDER_READ);
        Order o = orders.get(id);
        assertOwner(p, o);
        return ResponseEntity.ok().header(UcpVersions.HEADER, version).body(UcpViews.order(version, orders.view(id)));
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<Map<String, Object>> events(@PathVariable String id, @RequestHeader(value = UcpVersions.HEADER, required = false) String versionHeader) {
        String version = UcpVersions.negotiate(versionHeader);
        Principal p = require(Permissions.ORDER_READ);
        Order o = orders.get(id);
        assertOwner(p, o);
        return ResponseEntity.ok().header(UcpVersions.HEADER, version).body(UcpViews.events(version, id, events.load("order", id)));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> place(@RequestBody UcpOrderRequest body, @RequestHeader(value = UcpVersions.HEADER, required = false) String versionHeader,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest request) {
        String version = UcpVersions.negotiate(versionHeader);
        Principal p = require(Permissions.ORDER_CREATE);
        AgentIdentity agent = identity(p, request);
        if (body == null) throw new ValidationException("request body is required");
        if (body.mandate() == null) throw new ValidationException("mandate {id} or an inline mandate is required");
        String mandateId = body.mandate().id();
        if (body.mandate().inline()) mandateId = mandates.create(body.inlineMandate(agent.id())).id();
        try {
            AgentOrderService.PlaceResult r = placer.place(new AgentOrderService.PlaceRequest(agent, "ucp", body.toCapture(), mandateId,
                body.buyer() == null ? null : body.buyer().ref(), body.instructions(), idempotencyKey, "order_create", Map.of("buyerAcknowledgement", body.buyerAcknowledgement() == null ? "" : body.buyerAcknowledgement())));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ucp_version", version);
            if (r instanceof AgentOrderService.PendingConfirmation pc) {
                out.put("status", "pending_confirmation");
                out.put("task_id", pc.taskId());
                out.put("mandate_id", mandateId);
                return ResponseEntity.accepted().header(UcpVersions.HEADER, version).body(out);
            }
            AgentOrderService.Placed placed = (AgentOrderService.Placed) r;
            out.put("order_id", placed.order().id());
            out.put("client_order_id", placed.order().channelOrderId());
            out.put("status", placed.order().status());
            out.put("mandate_id", placed.order().provenance().mandateId());
            out.put("on_hold", placed.holdId() != null);
            out.put("order", UcpViews.order(version, orders.view(placed.order().id())));
            return ResponseEntity.status(placed.replayed() ? HttpStatus.OK : HttpStatus.CREATED).header(UcpVersions.HEADER, version).body(out);
        } catch (AgentToolException e) {
            throw toHttp(e);
        }
    }

    @PostMapping("/{id}/returns")
    public ResponseEntity<Map<String, Object>> createReturn(@PathVariable String id, @RequestBody UcpReturnRequest body, @RequestHeader(value = UcpVersions.HEADER, required = false) String versionHeader) {
        String version = UcpVersions.negotiate(versionHeader);
        Principal p = require(Permissions.RETURN_CREATE);
        Order o = orders.get(id);
        assertOwner(p, o);
        if (body == null || body.lineItems() == null || body.lineItems().isEmpty()) throw new ValidationException("line_items is required");
        String reason = AgentTextSanitizer.sanitize(body.reason());
        ScreenResult sr = reason == null || reason.isBlank() ? ScreenResult.clean("ucp_return_reason") : screen.screen("ucp_return_reason", reason, "order", id);
        List<ReturnLineRequest> lines = new ArrayList<>();
        for (UcpReturnLine l : body.lineItems()) lines.add(new ReturnLineRequest(l.lineId(), l.quantity(), l.reasonCode(), null));
        Actor actor = Actor.parse(p.actorRef());
        ReturnRma rma = returns.create(new ReturnRequest(id, lines, ReturnChannel.UCP, p.isAgent() ? p.id() : null, o.provenance() == null ? null : o.provenance().mandateId(),
            !Boolean.FALSE.equals(body.receiptPresent()), null, reason), actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ucp_version", version);
        out.put("order_id", id);
        out.put("return_id", rma.rmaId());
        out.put("status", rma.status());
        out.put("injection_flagged", sr.flagged());
        out.put("return", UcpViews.snake(CustomerSafeOrderView.returnRecord(returns.get(rma.rmaId()))));
        return ResponseEntity.status(HttpStatus.CREATED).header(UcpVersions.HEADER, version).body(out);
    }

    private Principal require(String permission) {
        Principal p = authz.currentPrincipal().orElseThrow(() -> new UnauthorizedException("Authenticate with signed agent headers, an API key or a bearer token"));
        if (!p.has(permission)) throw new ForbiddenException("Missing permission " + permission);
        return p;
    }

    private AgentIdentity identity(Principal p, HttpServletRequest request) {
        Object attr = request.getAttribute(AgentAuthFilter.ATTR_IDENTITY);
        if (attr instanceof AgentIdentity a) return a;
        if (p.isAgent()) return registry.get(p.id());
        throw new ForbiddenException("UCP order placement requires a registered agent identity (people place orders through /api/v1/orders)");
    }

    private static void assertOwner(Principal p, Order o) {
        if (p.isAgent() && p.trustTier() <= 0 && (o.provenance() == null || !p.id().equals(o.provenance().agentId())))
            throw new ForbiddenException("Order " + o.id() + " was not placed by agent " + p.id());
    }

    static OmsException toHttp(AgentToolException e) {
        HttpStatus status = switch (e.code()) {
            case AgentToolException.LIMIT_EXCEEDED, AgentToolException.MANDATE_INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case AgentToolException.NOT_OWNER, AgentToolException.FORBIDDEN, AgentToolException.TOOL_NOT_ALLOWED -> HttpStatus.FORBIDDEN;
            case AgentToolException.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case AgentToolException.CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return new OmsException(status, e.reason(), e.getMessage(), e.data(), e);
    }
}
