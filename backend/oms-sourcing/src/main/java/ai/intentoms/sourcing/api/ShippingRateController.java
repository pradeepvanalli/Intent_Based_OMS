package ai.intentoms.sourcing.api;

import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.sourcing.rates.RateCard;
import ai.intentoms.sourcing.rates.RateCardRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The tenant's carrier rate card (FR-SRC-02): read it, quote it, replace it. */
@RestController
@RequestMapping("/api/v1/shipping-rates")
public class ShippingRateController {
    private final RateCardRepository rates;

    public ShippingRateController(RateCardRepository rates) { this.rates = rates; }

    @GetMapping
    @RequiresPermission({Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public Map<String, Object> get() {
        RateCard card = rates.current();
        Map<String, Object> m = new LinkedHashMap<>(card.source());
        m.put("services", card.source().get("services"));
        m.put("serviceCodes", card.services());
        m.put("onTimeByTransitDays", card.onTimeTable());
        m.put("splitPenaltyUsd", card.splitPenaltyUsd());
        return m;
    }

    @GetMapping("/quote")
    @RequiresPermission({Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public RateCard.Quote quote(@RequestParam String service, @RequestParam double distanceKm, @RequestParam(defaultValue = "0.5") double weightKg) {
        return rates.current().quote(service, distanceKm, weightKg);
    }

    @PutMapping
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Map<String, Object> replace(@RequestBody Map<String, Object> definition) {
        rates.save(definition);
        return get();
    }
}
