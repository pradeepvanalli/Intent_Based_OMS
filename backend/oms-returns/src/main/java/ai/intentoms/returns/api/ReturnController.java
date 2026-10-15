package ai.intentoms.returns.api;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.returns.model.*;
import ai.intentoms.returns.service.ReturnMessages;
import ai.intentoms.returns.service.ReturnService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Returns API (ARCHITECTURE §3.9, UI contract item 11). Agents (principal type AGENT) and store associates create
 * returns through the same endpoint; the agent id is taken from the principal when the body omits it (FR-RET-01,
 * FR-AGT-01). Mutations return the full {@link ReturnRecord} so the UI can re-render without a second call.
 */
@RestController
@RequestMapping("/api/v1/returns")
public class ReturnController {
    private final ReturnService returns;
    private final ReturnMessages messages;
    private final AuthorizationService authz;

    public ReturnController(ReturnService returns, ReturnMessages messages, AuthorizationService authz) {
        this.returns = returns;
        this.messages = messages;
        this.authz = authz;
    }

    public record Reason(String reason) {}
    public record Deny(Boolean approved, String reason) {}
    public record Review(String refundTiming, String reason) {}
    public record Receive(String nodeId) {}
    public record Inspect(String condition, String note) {}
    public record SetDisposition(String disposition, String reason) {}

    @PostMapping
    @RequiresPermission(Permissions.RETURN_CREATE)
    public ResponseEntity<ReturnRecord> create(@RequestBody ReturnRequest body) {
        if (body == null) throw new ValidationException("request body is required");
        ReturnRequest req = body;
        Principal p = authz.requirePrincipal();
        if (p.isAgent() && (body.agentId() == null || body.agentId().isBlank())) {
            req = new ReturnRequest(body.orderId(), body.lines(), body.channel() == null || body.channel().isBlank() ? ReturnChannel.AGENT : body.channel(), p.id(), body.mandateId(),
                body.receiptPresent(), body.storeNodeId(), body.customerReasonText());
        }
        ReturnRma rma = returns.create(req, actor());
        return ResponseEntity.status(HttpStatus.CREATED).body(returns.get(rma.rmaId()));
    }

    /** Buy-online-return-in-store: create + decide + receive at the store in one call (FR-RET-01). */
    @PostMapping("/in-store")
    @RequiresPermission(Permissions.RETURN_CREATE)
    public ResponseEntity<ReturnRecord> inStore(@RequestBody InStoreReturnRequest body) {
        ReturnRma rma = returns.createInStore(body, actor());
        return ResponseEntity.status(HttpStatus.CREATED).body(returns.get(rma.rmaId()));
    }

    @GetMapping
    @RequiresPermission(Permissions.ORDER_READ)
    public Page<ReturnSummary> list(@RequestParam(required = false) String status, @RequestParam(required = false) String orderId,
                                    @RequestParam(required = false) String customerRef, @RequestParam(required = false) String riskLevel,
                                    @RequestParam(required = false) String channel,
                                    @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return returns.list(new ReturnQuery(status, orderId, customerRef, riskLevel, channel, offset, limit));
    }

    @GetMapping("/{rma}")
    @RequiresPermission(Permissions.ORDER_READ)
    public ReturnRecord get(@PathVariable String rma) { return returns.get(rma); }

    /** Summaries for one order (used by the order view's {@code returns[]}). */
    @GetMapping("/for-order/{orderId}")
    @RequiresPermission(Permissions.ORDER_READ)
    public List<ReturnSummary> forOrder(@PathVariable String orderId) { return returns.forOrder(orderId); }

    @PostMapping("/{rma}/decide")
    @RequiresPermission(Permissions.RETURN_DECIDE)
    public ReturnRecord decide(@PathVariable String rma) {
        returns.decideEligibility(rma, actor());
        return returns.get(rma);
    }

    @PostMapping("/{rma}/scan")
    @RequiresPermission({Permissions.RETURN_DECIDE, Permissions.RETURN_CREATE})
    public ReturnRecord scan(@PathVariable String rma) {
        returns.recordScan(rma, actor());
        return returns.get(rma);
    }

    @PostMapping("/{rma}/receive")
    @RequiresPermission({Permissions.RETURN_DECIDE, Permissions.RETURN_CREATE})
    public ReturnRecord receive(@PathVariable String rma, @RequestBody Receive body) {
        if (body == null || body.nodeId() == null || body.nodeId().isBlank()) throw new ValidationException("nodeId is required");
        returns.receive(rma, body.nodeId(), actor());
        return returns.get(rma);
    }

    @PostMapping("/{rma}/inspect")
    @RequiresPermission({Permissions.RETURN_DECIDE, Permissions.RETURN_CREATE})
    public ReturnRecord inspect(@PathVariable String rma, @RequestBody Inspect body) {
        if (body == null) throw new ValidationException("condition is required");
        returns.inspect(rma, body.condition(), body.note(), actor());
        return returns.get(rma);
    }

    @PostMapping("/{rma}/refund")
    @RequiresPermission(Permissions.REFUND_ISSUE)
    public ReturnRecord refund(@PathVariable String rma) {
        returns.refund(rma, actor());
        return returns.get(rma);
    }

    @PostMapping("/{rma}/approve-refund")
    @RequiresPermission(Permissions.REFUND_APPROVE)
    public ReturnRecord approveRefund(@PathVariable String rma) {
        returns.approveRefund(rma, actor());
        return returns.get(rma);
    }

    @PostMapping("/{rma}/disposition")
    @RequiresPermission(Permissions.RETURN_DECIDE)
    public ReturnRecord disposition(@PathVariable String rma, @RequestBody(required = false) SetDisposition body) {
        if (body != null && body.disposition() != null && !body.disposition().isBlank()) returns.applyDisposition(rma, body.disposition(), body.reason(), actor());
        else returns.disposition(rma, actor());
        return returns.get(rma);
    }

    @PostMapping("/{rma}/reject")
    @RequiresPermission(Permissions.RETURN_DECIDE)
    public ReturnRecord reject(@PathVariable String rma, @RequestBody Reason body) {
        returns.reject(rma, body == null ? null : body.reason(), actor());
        return returns.get(rma);
    }

    /** Human decision on a T3 denial recommendation: {@code approved=true} confirms the denial, {@code false} overrides it. */
    @PostMapping("/{rma}/deny")
    @RequiresPermission(Permissions.RETURN_DECIDE)
    public ReturnRecord deny(@PathVariable String rma, @RequestBody Deny body) {
        if (body == null || body.approved() == null) throw new ValidationException("approved (true|false) is required");
        returns.applyDenial(rma, body.approved(), body.reason(), actor());
        return returns.get(rma);
    }

    /** Human choice of refund timing for an escalated (T2) risk decision. */
    @PostMapping("/{rma}/review")
    @RequiresPermission(Permissions.RETURN_DECIDE)
    public ReturnRecord review(@PathVariable String rma, @RequestBody Review body) {
        if (body == null) throw new ValidationException("refundTiming and reason are required");
        returns.applyReview(rma, body.refundTiming(), body.reason(), actor());
        return returns.get(rma);
    }

    @PostMapping("/{rma}/cancel")
    @RequiresPermission({Permissions.RETURN_DECIDE, Permissions.RETURN_CREATE})
    public ReturnRecord cancel(@PathVariable String rma, @RequestBody(required = false) Reason body) {
        returns.cancel(rma, body == null ? null : body.reason(), actor());
        return returns.get(rma);
    }

    /** GR-15: customer-safe text with typed slots only; never risk data. */
    @GetMapping("/{rma}/customer-message")
    @RequiresPermission(Permissions.ORDER_READ)
    public Map<String, Object> customerMessage(@PathVariable String rma) { return messages.customerMessage(returns.getRma(rma)); }

    private Actor actor() { return Actor.parse(authz.actorRef()); }
}
