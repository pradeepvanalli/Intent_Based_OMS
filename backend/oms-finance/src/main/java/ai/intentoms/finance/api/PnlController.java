package ai.intentoms.finance.api;

import ai.intentoms.finance.pnl.OrderPnlCalculator;
import ai.intentoms.finance.pnl.Pnl;
import ai.intentoms.finance.pnl.PnlInput;
import ai.intentoms.platform.config.ConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Order P&L for the UI and what-if tooling (FR-FIN-04); reports margin-floor compliance (FR-SRC-04). */
@RestController
@RequestMapping("/api/v1/pnl")
public class PnlController {
    private final OrderPnlCalculator calculator;
    private final TenantConfigStore config;

    public PnlController(OrderPnlCalculator calculator, TenantConfigStore config) {
        this.calculator = calculator;
        this.config = config;
    }

    public record PnlResponse(Pnl pnl, double marginFloor, boolean meetsMarginFloor) {}

    @PostMapping("/compute")
    @RequiresPermission({Permissions.ORDER_READ, Permissions.ANALYTICS_READ})
    public PnlResponse compute(@RequestBody PnlInput in) {
        Pnl pnl = calculator.compute(in);
        double floor = config.getDouble(ConfigKeys.MARGIN_FLOOR, 0.0);
        return new PnlResponse(pnl, floor, pnl.meetsMarginFloor(floor));
    }
}
