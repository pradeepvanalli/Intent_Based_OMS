package ai.intentoms.finance.api;

import ai.intentoms.finance.tax.TaxEngine;
import ai.intentoms.finance.tax.TaxRequest;
import ai.intentoms.finance.tax.TaxResult;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tax quotes through the configured {@link TaxEngine} (FR-FIN-02). */
@RestController
@RequestMapping("/api/v1/tax")
public class TaxController {
    private final TaxEngine engine;

    public TaxController(TaxEngine engine) { this.engine = engine; }

    @PostMapping("/quote")
    @RequiresPermission(Permissions.ORDER_READ)
    public TaxResult quote(@RequestBody TaxRequest req) {
        if (req.lines() == null) throw new ValidationException("lines are required");
        if (req.destination() == null) throw new ValidationException("destination is required");
        return engine.calculate(req);
    }
}
