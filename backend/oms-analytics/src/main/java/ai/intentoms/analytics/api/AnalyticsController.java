package ai.intentoms.analytics.api;

import ai.intentoms.analytics.decision.DecisionAnalytics;
import ai.intentoms.analytics.decision.DecisionAnalyticsService;
import ai.intentoms.analytics.economics.AiEconomics;
import ai.intentoms.analytics.economics.AiEconomicsService;
import ai.intentoms.analytics.export.EventExportService;
import ai.intentoms.analytics.kpi.KpiDashboard;
import ai.intentoms.analytics.kpi.KpiRange;
import ai.intentoms.analytics.kpi.KpiService;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Analytics REST surface (UI contract item 14): {@code GET /api/v1/analytics/kpis} (FR-ANL-01; {@code analytics.read} or
 * {@code order.read}), {@code /analytics/decisions} and {@code /analytics/decisions/{name}} (FR-ANL-02), {@code /analytics/ai-cost}
 * (GR-17), {@code /analytics/ai-economics?from&to} (layer mix, automation, cost per decision/order, estimated hours saved) and the NDJSON CloudEvents export {@code /analytics/export/events} (FR-ANL-03), all {@code analytics.read}.
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    public static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private final KpiService kpis;
    private final DecisionAnalyticsService decisions;
    private final EventExportService export;
    private final AiEconomicsService economics;
    private final TimeSource time;

    public AnalyticsController(KpiService kpis, DecisionAnalyticsService decisions, EventExportService export, AiEconomicsService economics, TimeSource time) {
        this.kpis = kpis;
        this.decisions = decisions;
        this.export = export;
        this.economics = economics;
        this.time = time;
    }

    /** KPI dashboard; {@code from}/{@code to} are ISO dates (or instants), defaulting to the last 30 days ending today (UTC). */
    @GetMapping("/kpis")
    @RequiresPermission({Permissions.ANALYTICS_READ, Permissions.ORDER_READ})
    public KpiDashboard kpis(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate today = KpiRange.dayOf(time.now());
        LocalDate toDay = to == null || to.isBlank() ? today : parseDay(to, "to");
        LocalDate fromDay = from == null || from.isBlank() ? toDay.minusDays(29) : parseDay(from, "from");
        return kpis.dashboard(new KpiRange(fromDay, toDay));
    }

    @GetMapping("/decisions")
    @RequiresPermission(Permissions.ANALYTICS_READ)
    public List<DecisionAnalytics.OverviewRow> decisions(@RequestParam(defaultValue = "30") int days) { return decisions.overview(days); }

    @GetMapping("/decisions/{name}")
    @RequiresPermission(Permissions.ANALYTICS_READ)
    public DecisionAnalytics decision(@PathVariable String name, @RequestParam(defaultValue = "30") int days) { return decisions.forDecision(name, days); }

    @GetMapping("/ai-cost")
    @RequiresPermission(Permissions.ANALYTICS_READ)
    public DecisionAnalytics.AiCostReport aiCost(@RequestParam(defaultValue = "30") int days) { return decisions.aiCost(days); }

    /** AI economics (layers, automation, cost per 1k decisions / per order, latency, overrides, estimated hours saved); {@code from}/{@code to} as for {@code /kpis}. */
    @GetMapping("/ai-economics")
    @RequiresPermission(Permissions.ANALYTICS_READ)
    public AiEconomics aiEconomics(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate today = KpiRange.dayOf(time.now());
        LocalDate toDay = to == null || to.isBlank() ? today : parseDay(to, "to");
        LocalDate fromDay = from == null || from.isBlank() ? toDay.minusDays(29) : parseDay(from, "from");
        return economics.report(new KpiRange(fromDay, toDay));
    }

    /**
     * Streams the tenant's ledger events in {@code [from, to)} as NDJSON CloudEvents for warehouse loaders (FR-ANL-03).
     * {@code from}/{@code to} accept ISO instants or dates (default: last 24 hours); {@code types} is an optional comma-separated list of type prefixes.
     */
    @GetMapping(value = "/export/events", produces = "application/x-ndjson")
    @RequiresPermission(Permissions.ANALYTICS_READ)
    public ResponseEntity<StreamingResponseBody> exportEvents(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                                              @RequestParam(required = false) String types) {
        Instant now = time.now();
        Instant toInstant = to == null || to.isBlank() ? now : parseInstant(to, "to", true);
        Instant fromInstant = from == null || from.isBlank() ? toInstant.minus(Duration.ofHours(24)) : parseInstant(from, "from", false);
        if (!fromInstant.isBefore(toInstant)) throw new ValidationException("from must be before to", Map.of("from", fromInstant.toString(), "to", toInstant.toString()));
        List<String> prefixes = types == null || types.isBlank() ? List.of() : List.of(types.split(","));
        String tenant = TenantContext.require();   // the body runs on the servlet's async thread, outside the tenant filter
        StreamingResponseBody body = out -> TenantContext.runAs(tenant, () -> export.write(fromInstant, toInstant, prefixes, out));
        String filename = "events-" + tenant + "-" + fromInstant.toString().replace(":", "") + ".ndjson";
        return ResponseEntity.ok().contentType(NDJSON).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"").body(body);
    }

    private static LocalDate parseDay(String s, String field) {
        try {
            return s.length() > 10 ? Instant.parse(s).atZone(ZoneOffset.UTC).toLocalDate() : LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new ValidationException(field + " must be an ISO date or instant", Map.of(field, s));
        }
    }

    /** ISO instant, or an ISO date meaning start of that day (end of day when {@code endOfDay}). */
    private static Instant parseInstant(String s, String field, boolean endOfDay) {
        try {
            if (s.length() > 10) return Instant.parse(s);
            LocalDate d = LocalDate.parse(s);
            return (endOfDay ? d.plusDays(1) : d).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new ValidationException(field + " must be an ISO date or instant", Map.of(field, s));
        }
    }
}
