package ai.intentoms.orchestration.seed;

import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.decision.definition.GoldenSetLoader;
import ai.intentoms.finance.compliance.ComplianceRuleLoader;
import ai.intentoms.finance.compliance.DefaultComplianceService;
import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.monitoring.exception.ExceptionRequest;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.monitoring.exception.ExceptionTaxonomyLoader;
import ai.intentoms.monitoring.playbook.PlaybookLoader;
import ai.intentoms.monitoring.sla.BreachRiskScorer;
import ai.intentoms.monitoring.sla.SlaLoader;
import ai.intentoms.orchestration.integration.InboundEventService;
import ai.intentoms.orchestration.workflow.ReturnWorkflow;
import ai.intentoms.order.capture.CatalogLoader;
import ai.intentoms.order.capture.CatalogService;
import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.order.service.OrderTypeLoader;
import ai.intentoms.platform.bootstrap.TenantBootstrap;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.returns.model.ReturnChannel;
import ai.intentoms.returns.model.ReturnLineRequest;
import ai.intentoms.returns.model.ReturnRequest;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.service.ReturnService;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.sourcing.node.NodeLoader;
import ai.intentoms.sourcing.rates.RateCardLoader;
import ai.intentoms.statemachine.service.StateModelLoader;
import ai.intentoms.workflow.engine.WorkflowWorker;
import ai.intentoms.workflow.model.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Seeds the demo tenants on start-up (ARCHITECTURE §3.13; {@code oms.demo.seed=true}). For every tenant id in
 * {@code oms.demo.tenants} (env {@code OMS_DEMO_TENANTS}, comma list; falls back to {@code oms.demo.tenant}) whose folder
 * {@code config/tenants/<id>} exists: the tenant row (name / vertical / description from the top of
 * {@code tenant-config.yaml}, see {@link TenantMetadata}), the local dev users (admin, ops, cs, risk, finance, strategy;
 * password = user name), then inside that tenant's context the configuration (tenant_config, state models, decisions +
 * golden sets, compliance rules, order types, catalogue, nodes, rate card, exception taxonomy, playbooks, SLAs), the
 * inventory ({@code inventory.yaml} when present, else deterministic positions) and — only when the tenant has no orders
 * yet — the sample orders of {@code sample-orders.yaml} ({@link SampleOrdersFile}; the {@code demo} folder falls back to
 * the Java {@link DemoOrders}) driven through the workflows with WMS / carrier events, their returns and exceptions, and a
 * breach-risk sweep. One tenant failing never stops the others; a folder that does not exist is skipped with a warning.
 * Idempotent: every loader upserts, config keys are only written when absent, inventory only where no position exists,
 * users only when absent and orders are never re-created. Agents are seeded by the agent module. Tests call
 * {@link #seedConfig()} / {@link #seedInventory()} / {@link #seedOrders()} on their own tenant (default folder) or the
 * {@code Path} variants for a specific tenant folder.
 */
@Component
@org.springframework.core.annotation.Order(100)
public class DemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    private static final Actor ACTOR = Actor.system("demo-seed");
    static final List<String> VENDOR_SKUS = List.of("TEE-BLK-M", "TEE-WHT-L", "CAP-NVY", "SOCK-3PK", "BAG-TOTE");
    /** Local dev users created on every seeded tenant: user id → role (password equals the user id). */
    static final Map<String, String> DEV_USERS = new LinkedHashMap<>();
    static {
        DEV_USERS.put("admin", "admin");
        DEV_USERS.put("ops", "ops_analyst");
        DEV_USERS.put("cs", "cs_rep");
        DEV_USERS.put("risk", "risk_analyst");
        DEV_USERS.put("finance", "finance_controller");
        DEV_USERS.put("strategy", "strategy_lead");
    }
    /** Tenant folder names that fall back to the Java {@link DemoOrders} when no {@code sample-orders.yaml} exists. */
    static final String DEMO_FOLDER = "demo";

    private final boolean enabled;
    private final List<String> tenantIds;
    private final String configDir;
    private final TenantBootstrap tenants;
    private final TenantConfigStore config;
    private final StateModelLoader stateModels;
    private final DecisionDefinitionLoader decisions;
    private final GoldenSetLoader goldenSets;
    private final ComplianceRuleLoader complianceRules;
    private final DefaultComplianceService compliance;
    private final OrderTypeLoader orderTypes;
    private final CatalogLoader catalogLoader;
    private final CatalogService catalog;
    private final NodeLoader nodes;
    private final NodeRegistry nodeRegistry;
    private final RateCardLoader rateCards;
    private final ExceptionTaxonomyLoader taxonomy;
    private final PlaybookLoader playbooks;
    private final SlaLoader slas;
    private final InventoryService inventory;
    private final OrderService orders;
    private final WorkflowWorker worker;
    private final WorkflowEngine engine;
    private final InboundEventService inbound;
    private final ReturnService returns;
    private final ExceptionService exceptions;
    private final BreachRiskScorer riskScorer;
    private final JdbcClient jdbc;

    public DemoSeeder(@Value("${oms.demo.seed:false}") boolean enabled, @Value("${oms.demo.tenants:}") String tenantsCsv, @Value("${oms.demo.tenant:demo}") String tenant,
                      @Value("${oms.config-dir:../../config}") String configDir,
                      TenantBootstrap tenants, TenantConfigStore config, StateModelLoader stateModels, DecisionDefinitionLoader decisions, GoldenSetLoader goldenSets,
                      ComplianceRuleLoader complianceRules, DefaultComplianceService compliance, OrderTypeLoader orderTypes, CatalogLoader catalogLoader, CatalogService catalog,
                      NodeLoader nodes, NodeRegistry nodeRegistry, RateCardLoader rateCards, ExceptionTaxonomyLoader taxonomy, PlaybookLoader playbooks, SlaLoader slas,
                      InventoryService inventory, OrderService orders, WorkflowWorker worker, WorkflowEngine engine, InboundEventService inbound, ReturnService returns,
                      ExceptionService exceptions, BreachRiskScorer riskScorer, JdbcClient jdbc) {
        this.enabled = enabled;
        this.tenantIds = tenantList(tenantsCsv, tenant);
        this.configDir = configDir;
        this.tenants = tenants;
        this.config = config;
        this.stateModels = stateModels;
        this.decisions = decisions;
        this.goldenSets = goldenSets;
        this.complianceRules = complianceRules;
        this.compliance = compliance;
        this.orderTypes = orderTypes;
        this.catalogLoader = catalogLoader;
        this.catalog = catalog;
        this.nodes = nodes;
        this.nodeRegistry = nodeRegistry;
        this.rateCards = rateCards;
        this.taxonomy = taxonomy;
        this.playbooks = playbooks;
        this.slas = slas;
        this.inventory = inventory;
        this.orders = orders;
        this.worker = worker;
        this.engine = engine;
        this.inbound = inbound;
        this.returns = returns;
        this.exceptions = exceptions;
        this.riskScorer = riskScorer;
        this.jdbc = jdbc;
    }

    /** {@code oms.demo.tenants} (comma list) when set, else {@code oms.demo.tenant}; blank entries dropped, order kept. */
    static List<String> tenantList(String csv, String single) {
        List<String> out = new ArrayList<>();
        if (csv != null) for (String t : csv.split(",")) if (!t.isBlank() && !out.contains(t.trim())) out.add(t.trim());
        if (out.isEmpty() && single != null && !single.isBlank()) out.add(single.trim());
        return List.copyOf(out);
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(100)
    public void onReady() {
        if (!enabled) { log.info("Demo seed disabled (oms.demo.seed=false)"); return; }
        try { run(); } catch (RuntimeException e) { log.error("Demo seed failed: {}", e.toString(), e); }
        if (publisher != null) publisher.publishEvent(new DemoSeedCompletedEvent(tenantIds));
    }

    private org.springframework.context.ApplicationEventPublisher publisher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPublisher(org.springframework.context.ApplicationEventPublisher publisher) { this.publisher = publisher; }

    /** The configured tenant ids (first one is the default folder for the no-argument seed methods). */
    public List<String> tenantIds() { return tenantIds; }

    /** Metadata of every configured tenant whose folder exists — what the login page lists. */
    public List<TenantMetadata> tenants() {
        List<TenantMetadata> out = new ArrayList<>();
        for (String id : tenantIds) tenantDir(id).ifPresent(dir -> out.add(TenantMetadata.read(dir, id)));
        return out;
    }

    /** Full seed of every configured tenant; a failing tenant is logged and reported, the others still run. */
    public Map<String, Object> run() {
        Map<String, Object> all = new LinkedHashMap<>();
        for (String id : tenantIds) {
            try { all.put(id, seedTenant(id)); }
            catch (RuntimeException e) {
                log.error("Demo seed failed for tenant {}: {}", id, e.toString(), e);
                all.put(id, Map.of("failed", String.valueOf(e.getMessage())));
            }
        }
        log.info("Demo seed summary: {}", Json.write(all));
        return all;
    }

    /** Seeds one tenant from {@code config/tenants/<tenantId>}: tenant row, dev users, config, inventory, orders. */
    public Map<String, Object> seedTenant(String tenantId) {
        Optional<Path> dir = tenantDir(tenantId);
        if (dir.isEmpty()) {
            log.warn("Demo seed skipped for tenant {}: no config directory with tenants/{} found (oms.config-dir={})", tenantId, tenantId, configDir);
            return Map.of("skipped", "config_dir_missing");
        }
        TenantMetadata meta = TenantMetadata.read(dir.get(), tenantId);
        tenants.ensureTenant(tenantId, meta.name());
        int users = 0;
        for (var u : DEV_USERS.entrySet()) if (tenants.ensureUser(tenantId, u.getKey(), u.getKey(), List.of(u.getValue()))) users++;
        final int created = users;
        return asTenant(tenantId, () -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", meta.name());
            if (meta.vertical() != null) summary.put("vertical", meta.vertical());
            summary.put("usersCreated", created);
            summary.put("config", seedConfig(dir.get()));
            summary.put("inventory", seedInventory(dir.get()));
            summary.put("orders", seedOrders(dir.get()));
            log.info("Demo seed summary for tenant {} ({}): {}", tenantId, meta.name(), Json.write(summary));
            return summary;
        });
    }

    /** {@code config/tenants/<tenantId>} when it exists. */
    public Optional<Path> tenantDir(String tenantId) {
        return ConfigDirResolver.resolve(configDir, tenantId).map(p -> p.resolve("tenants").resolve(tenantId));
    }

    /** The first configured tenant's folder: what the no-argument seed methods (used by tests on their own tenant) load. */
    private Path defaultDir() {
        String id = tenantIds.isEmpty() ? DEMO_FOLDER : tenantIds.get(0);
        return tenantDir(id).orElseThrow(() -> new IllegalStateException("No config directory with tenants/" + id + " (oms.config-dir=" + configDir + ")"));
    }

    /** Loads every config artefact of the default tenant folder into the current tenant (idempotent). */
    public Map<String, Object> seedConfig() { return seedConfig(defaultDir()); }

    public Map<String, Object> seedConfig(Path dir) {
        Map<String, Object> out = new LinkedHashMap<>();
        int written = 0;
        Map<String, Object> tenantConfig = Json.yamlMap(read(dir.resolve("tenant-config.yaml")));
        for (var e : tenantConfig.entrySet()) {
            if (TenantMetadata.KEYS.contains(e.getKey())) continue; // name / vertical / description describe the tenant, they are not runtime settings
            if (config.get(e.getKey()).isEmpty()) { config.set(e.getKey(), e.getValue() instanceof String s ? Json.write(s) : e.getValue()); written++; }
        }
        Map<String, Object> constraints = orderTypeConstraints(Json.yamlMap(read(dir.resolve("order-types.yaml"))));
        if (config.get("sourcing.order_type_constraints").isEmpty()) { config.set("sourcing.order_type_constraints", constraints); written++; }
        out.put("tenantConfigKeysWritten", written);
        out.put("stateModels", stateModels.loadDirectory(dir.resolve("state-models")).size());
        out.put("decisions", decisions.loadDirectory(dir.resolve("decisions")).size());
        out.put("goldenCases", goldenSets.loadDirectory(dir.resolve("golden-sets")));
        out.put("complianceRules", complianceRules.loadFile(dir.resolve("rules").resolve("compliance.yaml")).size());
        compliance.invalidate();
        out.put("orderTypes", orderTypes.loadFile(dir.resolve("order-types.yaml")).size());
        out.put("catalogItems", catalogLoader.loadFile(dir.resolve("catalog.yaml")).size());
        out.put("nodes", nodes.loadFile(dir.resolve("nodes.yaml")).size());
        rateCards.loadFile(dir.resolve("shipping-rates.yaml"));
        out.put("exceptionTypes", taxonomy.loadFile(dir.resolve("exception-taxonomy.yaml")).size());
        out.put("playbooks", playbooks.loadFile(dir.resolve("playbooks.yaml")).size());
        out.put("slas", slas.loadFile(dir.resolve("sla.yaml")).size());
        log.info("Demo config seeded for tenant {}: {}", TenantContext.require(), out);
        return out;
    }

    /** {@code sourcing.order_type_constraints}: order type → its {@code sourcing:} block from order-types.yaml. */
    static Map<String, Object> orderTypeConstraints(Map<String, Object> orderTypesYaml) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (orderTypesYaml.get("orderTypes") instanceof List<?> types) {
            for (Object t : types) if (t instanceof Map<?, ?> m && m.get("sourcing") instanceof Map<?, ?> s && m.get("name") != null) out.put(String.valueOf(m.get("name")), s);
        }
        return out;
    }

    /** Inventory for the current tenant from the default folder: {@code inventory.yaml} when present, else generated. */
    public Map<String, Object> seedInventory() { return seedInventory(defaultDir()); }

    /**
     * Inventory for the current tenant: the positions of {@code <dir>/inventory.yaml} ({@code POST /api/v1/inventory/feed}
     * shape: {@code source}, {@code positions[]{sku, nodeId, supplyType, qty, ...}}) when the file exists, otherwise
     * deterministic positions for every catalogue sku (DCs / 3PL 40–400, stores 0–25, the vendor 500 for five basics).
     * Only (sku, node) pairs without a stored position are written so a restart never resets live stock.
     */
    public Map<String, Object> seedInventory(Path dir) {
        Path file = dir.resolve("inventory.yaml");
        List<InventoryPosition> wanted;
        String source;
        if (Files.isRegularFile(file)) {
            InventoryFeed feed = Json.yaml(read(file), InventoryFeed.class);
            wanted = feed == null || feed.positions() == null ? List.of() : feed.positions();
            source = feed == null || feed.source() == null || feed.source().isBlank() ? "demo-seed" : feed.source();
        } else {
            wanted = generatedInventory();
            source = "demo-seed";
        }
        Map<String, List<Atp>> existing = new LinkedHashMap<>();
        List<InventoryPosition> positions = new ArrayList<>();
        for (InventoryPosition p : wanted) {
            if (p == null || p.sku() == null || p.nodeId() == null) continue;
            List<Atp> have = existing.computeIfAbsent(p.sku(), inventory::atp);
            if (have.stream().noneMatch(a -> a.nodeId().equals(p.nodeId()))) positions.add(p);
        }
        if (!positions.isEmpty()) inventory.upsert(positions, source);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", Files.isRegularFile(file) ? "inventory.yaml" : "generated");
        out.put("skus", wanted.stream().map(InventoryPosition::sku).distinct().count());
        out.put("nodes", wanted.stream().map(InventoryPosition::nodeId).distinct().count());
        out.put("positionsWritten", positions.size());
        log.info("Demo inventory ({}): {} positions written", out.get("source"), positions.size());
        return out;
    }

    /** {@code inventory.yaml} / {@code POST /api/v1/inventory/feed} body. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record InventoryFeed(String source, List<InventoryPosition> positions) {}

    private List<InventoryPosition> generatedInventory() {
        List<CatalogItem> items = catalog.all();
        List<Node> all = nodeRegistry.all();
        List<InventoryPosition> positions = new ArrayList<>();
        int skuIdx = 0;
        for (CatalogItem item : items) {
            if (item.flag("digital")) { skuIdx++; continue; }
            int nodeIdx = 0;
            for (Node n : all) {
                int qty = quantity(item.sku(), skuIdx, n, nodeIdx++);
                if (qty >= 0) positions.add(InventoryPosition.onHand(item.sku(), n.id(), qty));
            }
            skuIdx++;
        }
        return positions;
    }

    /** Quantity for a sku at a node; negative = no position. */
    static int quantity(String sku, int skuIdx, Node n, int nodeIdx) {
        return switch (n.type()) {
            case Node.TYPE_DC, Node.TYPE_3PL -> 40 + ((skuIdx * 37 + nodeIdx * 53) % 361);
            case Node.TYPE_STORE -> (skuIdx * 7 + nodeIdx * 5) % 26;
            case Node.TYPE_VENDOR -> VENDOR_SKUS.contains(sku) ? 500 : -1;
            default -> -1;
        };
    }

    /** Sample orders of the default folder for the current tenant; a no-op once the tenant has orders. */
    public Map<String, Object> seedOrders() { return seedOrders(defaultDir()); }

    /**
     * Sample orders, fulfilment events, returns, exceptions and a risk sweep for the current tenant from
     * {@code <dir>/sample-orders.yaml} (the {@code demo} folder falls back to {@link DemoOrders}); a no-op once the tenant
     * has orders. A file that fails validation or references unknown skus / nodes / order types / exception types throws
     * with every problem named by order key and field.
     */
    public Map<String, Object> seedOrders(Path dir) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (orders.search(OrderQuery.all(0, 1)).total() > 0) { out.put("skipped", "tenant already has orders"); return out; }
        Optional<SampleOrdersFile.SampleSet> loaded = loadSampleSet(dir);
        if (loaded.isEmpty()) { out.put("skipped", "no " + SampleOrdersFile.FILE_NAME); return out; }
        SampleOrdersFile.SampleSet set = loaded.get();
        out.put("source", SampleOrdersFile.locate(dir).isPresent() ? SampleOrdersFile.FILE_NAME : "DemoOrders");
        if (config.get("returns.flagged_customers").isEmpty()) config.set("returns.flagged_customers", List.of("cust-fraud-7"));
        List<DemoOrders.Sample> samples = set.orders();
        Map<String, Order> captured = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        for (DemoOrders.Sample s : samples) {
            try { captured.put(s.key(), orders.capture(s.request(), "demo-seed:" + s.request().channelOrderId())); }
            catch (RuntimeException e) { failed.add(s.key() + ": " + e.getMessage()); log.warn("Demo order {} not captured: {}", s.key(), e.getMessage()); }
        }
        runWorkflows(6);
        int ts = 0;
        for (DemoOrders.Sample s : samples) {
            Order o = captured.get(s.key());
            if (o == null) continue;
            try {
                Order fresh = orders.get(o.id());
                if (s.storeReject()) {
                    FulfilmentGroup g = firstActiveGroup(fresh);
                    if (g != null) { inbound.wms(g.nodeId(), wmsBody("seed-reject-" + s.key(), "store_rejected", fresh.id(), g.id(), null)); runWorkflows(3); fresh = orders.get(o.id()); }
                }
                if (s.bopisPickup()) {
                    FulfilmentGroup g = firstActiveGroup(fresh);
                    if (g != null) {
                        inbound.wms(g.nodeId(), wmsBody("seed-staged-" + s.key(), "staged_for_pickup", fresh.id(), g.id(), null));
                        runWorkflows(2);
                        inbound.wms(g.nodeId(), wmsBody("seed-pickedup-" + s.key(), "picked_up", fresh.id(), g.id(), null));
                        runWorkflows(2);
                    }
                }
                if (s.ship()) {
                    // partialShip: only the first line of the first group leaves; otherwise every active group ships (and is delivered)
                    List<FulfilmentGroup> groups = s.partialShip() ? List.of(firstActiveGroup(fresh)) : activeGroups(fresh);
                    int seq = 0;
                    for (FulfilmentGroup g : groups) {
                        if (g == null) continue;
                        String tracking = "1Z" + String.format("%06d", 100 + ts++);
                        String suffix = seq++ == 0 ? "" : "-" + seq;
                        Map<String, Object> body = wmsBody("seed-ship-" + s.key() + suffix, "shipped", fresh.id(), g.id(), tracking);
                        if (s.partialShip() && !g.allocations().isEmpty()) body.put("lines", List.of(Map.of("lineId", g.allocations().get(0).lineId(), "qty", g.allocations().get(0).qty())));
                        inbound.wms(g.nodeId(), body);
                        runWorkflows(2);
                        if (s.deliver()) {
                            inbound.carrier(Map.of("eventId", "seed-deliver-" + s.key() + suffix, "type", "delivered", "orderId", fresh.id(), "tracking", tracking));
                            runWorkflows(2);
                        }
                    }
                    if (s.deliver() && s.deliveredDaysAgo() > 0) backdateDelivered(fresh.id(), null, s.deliveredDaysAgo());
                }
            } catch (RuntimeException e) {
                failed.add(s.key() + " events: " + e.getMessage());
                log.warn("Demo events for {} failed: {}", s.key(), e.getMessage());
            }
        }
        out.put("captured", captured.size());
        out.put("failed", failed);
        out.put("returns", seedReturns(captured, set.returns()));
        out.put("exceptions", seedExceptions(captured, set.exceptions()));
        try { out.put("riskScored", riskScorer.sweepOnce()); } catch (RuntimeException e) { out.put("riskScored", "failed: " + e.getMessage()); }
        out.put("statusCounts", statusCounts());
        log.info("Demo orders seeded: {}", Json.write(out));
        return out;
    }

    /**
     * The sample set for a tenant folder: {@code sample-orders.yaml} parsed, validated and cross-checked against the
     * catalogue, nodes, order types and exception taxonomy of the folder; the {@code demo} folder falls back to
     * {@link DemoOrders} when the file is absent; any other folder without the file yields empty.
     */
    Optional<SampleOrdersFile.SampleSet> loadSampleSet(Path dir) {
        Optional<Path> file = SampleOrdersFile.locate(dir);
        if (file.isEmpty()) return DEMO_FOLDER.equals(dir.getFileName().toString()) ? Optional.of(DemoOrders.sampleSet()) : Optional.empty();
        SampleOrdersFile parsed = SampleOrdersFile.load(file.get());
        Set<String> skus = catalog.all().stream().map(CatalogItem::sku).collect(Collectors.toSet());
        Map<String, Node> nodes = nodeRegistry.all().stream().collect(Collectors.toMap(Node::id, n -> n, (a, b) -> a, LinkedHashMap::new));
        Set<String> types = orderTypeConstraintsNames(Json.yamlMap(read(dir.resolve("order-types.yaml"))));
        Set<String> exceptionTypes = exceptionTypeNames(Json.yamlMap(read(dir.resolve("exception-taxonomy.yaml"))));
        List<String> problems = parsed.crossCheck(skus, nodes.keySet(), types, exceptionTypes);
        if (!problems.isEmpty()) throw new SampleOrdersFile.SampleOrdersException(problems);
        return Optional.of(parsed.toSampleSet(id -> Optional.ofNullable(nodes.get(id)).map(n -> new SampleOrdersFile.AddressSpec(n.name(), n.id(), null, n.name(), n.state(), n.zip(), n.country(), "commercial"))));
    }

    static Set<String> orderTypeConstraintsNames(Map<String, Object> orderTypesYaml) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (orderTypesYaml.get("orderTypes") instanceof List<?> types) for (Object t : types) if (t instanceof Map<?, ?> m && m.get("name") != null) out.add(String.valueOf(m.get("name")));
        return out;
    }

    static Set<String> exceptionTypeNames(Map<String, Object> taxonomyYaml) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (taxonomyYaml.get("exceptionTypes") instanceof List<?> types) for (Object t : types) if (t instanceof Map<?, ?> m && m.get("type") != null) out.add(String.valueOf(m.get("type")));
        return out;
    }

    private List<String> seedReturns(Map<String, Order> captured, List<SampleOrdersFile.ReturnSpec> specs) {
        List<String> rmas = new ArrayList<>();
        for (SampleOrdersFile.ReturnSpec r : specs) rmas.add(createReturn(captured.get(r.order()), r));
        runWorkflows(3);
        rmas.removeIf(r -> r == null);
        return rmas;
    }

    private String createReturn(Order order, SampleOrdersFile.ReturnSpec r) {
        if (order == null) return null;
        try {
            Order fresh = orders.get(order.id());
            if (!"delivered".equals(fresh.status())) { log.info("Demo return skipped: order {} is {}", fresh.id(), fresh.status()); return null; }
            if (r.backdateDays() > 0) backdateDelivered(fresh.id(), r.lineId(), r.backdateDays());
            ReturnRma rma = returns.create(new ReturnRequest(fresh.id(), List.of(new ReturnLineRequest(r.lineId(), r.quantity(), r.reason(), r.text())), ReturnChannel.WEB, null, null, r.receipt(), null, null), ACTOR);
            engine.start(ReturnWorkflow.TYPE, rma.rmaId(), Map.of("rmaId", rma.rmaId(), "orderId", fresh.id()));
            return rma.rmaId();
        } catch (RuntimeException e) {
            log.warn("Demo return on {} failed: {}", order.id(), e.getMessage());
            return null;
        }
    }

    /** Demo-only: pretend the delivered units of one line (or every line when {@code lineId} is null) entered {@code delivered} {@code days} ago. */
    private void backdateDelivered(String orderId, String lineId, int days) {
        Timestamp since = Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS));
        if (lineId == null) {
            jdbc.sql("update entity_state_qty set since = :s where tenant_id = :t and entity_type = 'order_line' and entity_id like :p and state = 'delivered'")
                .param("s", since).param("t", TenantContext.require()).param("p", orderId + ":%").update();
        } else {
            jdbc.sql("update entity_state_qty set since = :s where tenant_id = :t and entity_type = 'order_line' and entity_id = :id and state = 'delivered'")
                .param("s", since).param("t", TenantContext.require()).param("id", orderId + ":" + lineId).update();
        }
    }

    private List<String> seedExceptions(Map<String, Order> captured, List<SampleOrdersFile.ExceptionSpec> specs) {
        List<String> ids = new ArrayList<>();
        for (SampleOrdersFile.ExceptionSpec e : specs) {
            Order o = captured.get(e.order());
            if (o == null) continue;
            try {
                ids.add(exceptions.raise(new ExceptionRequest(e.source(), e.effectiveCode(), e.text(), o.id(), null, null, Map.of("seed", true)), e.type(), null, e.playbook()).id());
            } catch (RuntimeException ex) { log.warn("Demo exception on {} failed: {}", o.id(), ex.getMessage()); }
        }
        return ids;
    }

    private Map<String, Long> statusCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (var row : jdbc.sql("select status, count(*) as n from orders where tenant_id = :t group by status").param("t", TenantContext.require()).query().listOfRows())
            counts.put(String.valueOf(row.get("status")), ((Number) row.get("n")).longValue());
        return counts;
    }

    /** Runs due workflow steps up to {@code passes} times, pausing briefly so backed-off retries get their turn. */
    public void runWorkflows(int passes) {
        for (int i = 0; i < passes; i++) {
            int n = worker.runOnce();
            if (n == 0 && i > 0) break;
            if (i < passes - 1) sleep(150);
        }
    }

    private static Map<String, Object> wmsBody(String eventId, String type, String orderId, String groupId, String tracking) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", eventId);
        m.put("type", type);
        m.put("orderId", orderId);
        m.put("fulfilmentGroupId", groupId);
        if (tracking != null) { m.put("carrier", "UPS"); m.put("service", "ground"); m.put("tracking", tracking); }
        return m;
    }

    private static List<FulfilmentGroup> activeGroups(Order o) {
        return o.fulfilmentGroups().stream().filter(g -> "released".equalsIgnoreCase(g.status()) || "planned".equalsIgnoreCase(g.status())).toList();
    }

    private static FulfilmentGroup firstActiveGroup(Order o) {
        return o.fulfilmentGroups().stream().filter(g -> "released".equalsIgnoreCase(g.status()) || "planned".equalsIgnoreCase(g.status())).findFirst().orElse(null);
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private static String read(Path p) { try { return Files.readString(p); } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read " + p, e); } }

    private static <T> T asTenant(String tenant, Supplier<T> body) {
        Authentication prev = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(Principal.system(tenant)));
        try { return TenantContext.supplyAs(tenant, body); }
        finally { if (prev == null) SecurityContextHolder.clearContext(); else SecurityContextHolder.getContext().setAuthentication(prev); }
    }
}
