package ai.intentoms.finance.api;

import ai.intentoms.finance.landedcost.LandedCost;
import ai.intentoms.finance.landedcost.LandedCostService;
import ai.intentoms.finance.landedcost.TariffRate;
import ai.intentoms.finance.landedcost.TariffRateRepository;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Business-maintained tariff tables and landed-cost estimates (FR-FIN-05). */
@RestController
@RequestMapping("/api/v1/tariffs")
public class TariffController {
    private final TariffRateRepository tariffs;
    private final LandedCostService landedCost;
    private final AuditLog audit;
    private final TimeSource time;

    public TariffController(TariffRateRepository tariffs, LandedCostService landedCost, AuditLog audit, TimeSource time) {
        this.tariffs = tariffs;
        this.landedCost = landedCost;
        this.audit = audit;
        this.time = time;
    }

    @GetMapping
    @RequiresPermission({Permissions.CONFIG_ADMIN, Permissions.ORDER_READ})
    public Page<TariffRate> list(@RequestParam(required = false) String origin, @RequestParam(required = false) String dest,
                                 @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return tariffs.list(origin, dest, Math.max(0, offset), Math.min(Math.max(1, limit), 500));
    }

    @GetMapping("/{id}")
    @RequiresPermission({Permissions.CONFIG_ADMIN, Permissions.ORDER_READ})
    public TariffRate get(@PathVariable String id) { return tariffs.get(id); }

    /** Creates (no id) or replaces (with id) a tariff row. */
    @PutMapping
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public TariffRate upsert(@RequestBody TariffRate body, @RequestParam(required = false) String reason) {
        validate(body);
        TariffRate saved = tariffs.upsert(body);
        audit.record("tariff.upserted", "tariff_rate", saved.id(), reason, Map.of("origin", saved.originCountry(), "dest", saved.destCountry(),
            "hsPrefix", saved.hsCodePrefix(), "dutyRate", saved.dutyRate(), "effectiveFrom", saved.effectiveFrom().toString()));
        return saved;
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public TariffRate upsert(@PathVariable String id, @RequestBody TariffRate body, @RequestParam(required = false) String reason) {
        return upsert(new TariffRate(id, body.originCountry(), body.destCountry(), body.hsCodePrefix(), body.dutyRate(), body.brokerageFee(),
            body.surchargeRate(), body.effectiveFrom(), body.effectiveTo(), body.note()), reason);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public void delete(@PathVariable String id, @RequestParam(required = false) String reason) {
        if (tariffs.delete(id) == 0) throw new NotFoundException("Tariff rate " + id + " not found");
        audit.record("tariff.deleted", "tariff_rate", id, reason, Map.of());
    }

    /** Ad-hoc landed-cost estimate for the UI / sourcing what-if. */
    @GetMapping("/estimate")
    @RequiresPermission({Permissions.CONFIG_ADMIN, Permissions.ORDER_READ})
    public LandedCost estimate(@RequestParam String origin, @RequestParam String dest, @RequestParam(defaultValue = "") String hsCode,
                               @RequestParam BigDecimal value, @RequestParam(defaultValue = "USD") String currency,
                               @RequestParam(required = false) Instant at) {
        return landedCost.estimate(origin, dest, hsCode, new Money(value, currency), at == null ? time.now() : at);
    }

    private static void validate(TariffRate r) {
        if (r.originCountry() == null || r.originCountry().isBlank()) throw new ValidationException("originCountry is required ('*' for any)");
        if (r.destCountry() == null || r.destCountry().isBlank()) throw new ValidationException("destCountry is required ('*' for any)");
        if (r.effectiveFrom() == null) throw new ValidationException("effectiveFrom is required");
        if (r.effectiveTo() != null && !r.effectiveTo().isAfter(r.effectiveFrom())) throw new ValidationException("effectiveTo must be after effectiveFrom");
        if (r.dutyRate() == null || r.dutyRate().signum() < 0) throw new ValidationException("dutyRate must be >= 0");
        if (r.brokerageFee() == null || r.brokerageFee().signum() < 0) throw new ValidationException("brokerageFee must be >= 0");
        if (r.surchargeRate() == null || r.surchargeRate().signum() < 0) throw new ValidationException("surchargeRate must be >= 0");
    }
}
