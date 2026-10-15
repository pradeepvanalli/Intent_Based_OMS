package ai.intentoms.simulation.api;

import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.simulation.batch.BatchRequest;
import ai.intentoms.simulation.batch.BatchSimulationService;
import ai.intentoms.simulation.batch.SimulationJob;
import ai.intentoms.simulation.margin.MarginReplayOptions;
import ai.intentoms.simulation.margin.MarginReplayService;
import ai.intentoms.simulation.replay.ConsistencyReport;
import ai.intentoms.simulation.replay.OrderRebuilder;
import ai.intentoms.simulation.replay.ReplayOptions;
import ai.intentoms.simulation.replay.ReplayResult;
import ai.intentoms.simulation.replay.ReplayService;
import ai.intentoms.simulation.synthetic.SyntheticOrderGenerator;
import ai.intentoms.simulation.synthetic.SyntheticSpec;
import ai.intentoms.simulation.whatif.WhatIfService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import ai.intentoms.platform.json.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simulation, replay and what-if REST surface (FR-SIM-01..03, FR-SIM-05; ARCHITECTURE §3.11; UI contract item 13).
 * Mutations need {@code simulation.run}; reads also accept {@code analytics.read}.
 */
@RestController
@RequestMapping("/api/v1/simulations")
public class SimulationController {
    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final ReplayService replays;
    private final OrderRebuilder rebuilder;
    private final BatchSimulationService batches;
    private final WhatIfService whatIf;
    private final SyntheticOrderGenerator synthetic;
    private final OrderService orders;
    private final AuthorizationService authz;
    private final MarginReplayService marginReplay;

    public SimulationController(ReplayService replays, OrderRebuilder rebuilder, BatchSimulationService batches, WhatIfService whatIf, SyntheticOrderGenerator synthetic,
                                OrderService orders, AuthorizationService authz, MarginReplayService marginReplay) {
        this.replays = replays;
        this.rebuilder = rebuilder;
        this.batches = batches;
        this.whatIf = whatIf;
        this.synthetic = synthetic;
        this.orders = orders;
        this.authz = authz;
        this.marginReplay = marginReplay;
    }

    /** {@code {spec, capture}}: return the generated requests, or capture them through {@code OrderService.capture} (also needs order.create). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SyntheticRequest(SyntheticSpec spec, boolean capture) {}

    /** {@code {scenario: node_closure|carrier_outage|tariff_change, nodeId?, nodeIds?, carriers?, tariffDelta?, from, to, sampleLimit?}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WhatIfRequest(String scenario, String nodeId, Set<String> nodeIds, List<String> carriers, Double tariffDelta,
                                @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = LenientInstant.class) Instant from,
                                @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = LenientInstant.class) Instant to, Integer sampleLimit) {}

    @PostMapping("/replay/{orderId}")
    @RequiresPermission(Permissions.SIMULATION_RUN)
    public ReplayResult replay(@PathVariable String orderId, @RequestBody(required = false) ReplayOptions options) {
        return replays.replay(orderId, options == null ? ReplayOptions.current() : options);
    }

    @GetMapping("/rebuild/{orderId}")
    @RequiresPermission({Permissions.SIMULATION_RUN, Permissions.ANALYTICS_READ})
    public ConsistencyReport rebuild(@PathVariable String orderId) { return rebuilder.check(orderId); }

    @PostMapping("/batch")
    @RequiresPermission(Permissions.SIMULATION_RUN)
    public ResponseEntity<SimulationJob> batch(@RequestBody BatchRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(batches.submit(request));
    }

    @PostMapping("/what-if")
    @RequiresPermission(Permissions.SIMULATION_RUN)
    public ResponseEntity<SimulationJob> whatIf(@RequestBody WhatIfRequest r) {
        if (r.scenario() == null) throw new ValidationException("scenario is required (node_closure | carrier_outage | tariff_change)");
        SimulationJob job = switch (r.scenario()) {
            case WhatIfService.NODE_CLOSURE -> {
                Set<String> ids = r.nodeIds() == null || r.nodeIds().isEmpty() ? (r.nodeId() == null ? Set.of() : Set.of(r.nodeId())) : r.nodeIds();
                yield whatIf.nodeClosure(ids, r.from(), r.to(), r.sampleLimit());
            }
            case WhatIfService.CARRIER_OUTAGE -> whatIf.carrierOutage(r.carriers() == null ? List.of() : r.carriers(), r.from(), r.to(), r.sampleLimit());
            case WhatIfService.TARIFF_CHANGE -> {
                if (r.tariffDelta() == null) throw new ValidationException("tariffDelta is required for a tariff_change scenario");
                yield whatIf.tariffChange(r.tariffDelta(), r.from(), r.to(), r.sampleLimit());
            }
            default -> throw new ValidationException("Unknown scenario '" + r.scenario() + "'", Map.of("known", List.of(WhatIfService.NODE_CLOSURE, WhatIfService.CARRIER_OUTAGE, WhatIfService.TARIFF_CHANGE)));
        };
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    /** Jobs, newest first (plain array per the UI contract; {@code offset}/{@code limit} page it). */
    @GetMapping
    @RequiresPermission({Permissions.SIMULATION_RUN, Permissions.ANALYTICS_READ})
    public List<SimulationJob> list(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return batches.list(offset, limit).items();
    }

    @GetMapping("/{id}")
    @RequiresPermission({Permissions.SIMULATION_RUN, Permissions.ANALYTICS_READ})
    public SimulationJob get(@PathVariable String id) { return batches.get(id); }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(Permissions.SIMULATION_RUN)
    public SimulationJob cancel(@PathVariable String id) { return batches.cancel(id); }

    /**
     * Margin Replay: multipart {@code file} (CSV, Shopify "Orders export" or generic layout, ≤ 10 MB, ≤ 5 000 orders) plus
     * options either as a JSON string part {@code options} or as plain form fields ({@code format, assumeStock,
     * unknownSkuCogsRatio, baseline, useDecisions}). Returns a QUEUED job of kind {@code import}; poll {@code GET /{id}}.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(Permissions.SIMULATION_RUN)
    public ResponseEntity<SimulationJob> importOrders(@RequestParam("file") MultipartFile file, @RequestParam(value = "options", required = false) String options,
                                                      @RequestParam Map<String, String> params) throws java.io.IOException {
        if (file == null || file.isEmpty()) throw new ValidationException("A CSV file part named 'file' is required");
        MarginReplayOptions opts = options != null && !options.isBlank() ? Json.read(options, MarginReplayOptions.class) : MarginReplayOptions.fromParams(params);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(marginReplay.submit(file.getOriginalFilename(), file.getBytes(), opts));
    }

    /** Margin Replay over the bundled Shopify sample ({@code docs/examples/data/shopify-orders-sample.csv}); body = options (optional). */
    @PostMapping("/import/sample")
    @RequiresPermission(Permissions.SIMULATION_RUN)
    public ResponseEntity<SimulationJob> importSample(@RequestBody(required = false) MarginReplayOptions options) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(marginReplay.submitSample(options));
    }

    /** FR-SIM-05: generate (and optionally capture) synthetic orders. */
    @PostMapping("/synthetic")
    @RequiresPermission(Permissions.SIMULATION_RUN)
    public Map<String, Object> synthetic(@RequestBody SyntheticRequest body) {
        if (body == null || body.spec() == null) throw new ValidationException("spec is required");
        List<CaptureRequest> requests = synthetic.generate(body.spec());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", requests.size());
        out.put("seed", body.spec().seed());
        if (!body.capture()) {
            out.put("captured", 0);
            out.put("requests", requests);
            return out;
        }
        authz.require(Permissions.ORDER_CREATE);
        List<String> orderIds = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (CaptureRequest req : requests) {
            try {
                Order o = orders.capture(req, null);
                orderIds.add(o.id());
            } catch (RuntimeException e) {
                log.warn("Synthetic capture {} rejected: {}", req.channelOrderId(), e.getMessage());
                failures.add(Map.of("channelOrderId", req.channelOrderId(), "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        out.put("captured", orderIds.size());
        out.put("orderIds", orderIds);
        out.put("failed", failures.size());
        out.put("failures", failures);
        return out;
    }
}
