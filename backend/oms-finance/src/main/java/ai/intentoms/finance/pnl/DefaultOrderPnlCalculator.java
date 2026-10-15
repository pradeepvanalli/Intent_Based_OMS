package ai.intentoms.finance.pnl;

import ai.intentoms.finance.config.FinanceConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure {@link OrderPnlCalculator} (FR-FIN-04). Tenant defaults fill in any null rate on the input; the arithmetic
 * itself lives in the static {@link #calculate} so it can be unit-tested without a Spring context. Scale 2,
 * HALF_EVEN, matching {@link ai.intentoms.finance.model.Money}.
 */
@Service
public class DefaultOrderPnlCalculator implements OrderPnlCalculator {
    public static final BigDecimal DEFAULT_PAYMENT_FEE_RATE = new BigDecimal("0.029");
    public static final BigDecimal DEFAULT_PAYMENT_FEE_FIXED = new BigDecimal("0.30");
    public static final BigDecimal DEFAULT_RETURNS_RESERVE_RATE = new BigDecimal("0.05");
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private final TenantConfigStore config;

    public DefaultOrderPnlCalculator(TenantConfigStore config) { this.config = config; }

    @Override
    public Pnl compute(PnlInput in) {
        BigDecimal feeRate = in.paymentFeeRate() != null ? in.paymentFeeRate() : config.getDecimal(FinanceConfigKeys.PAYMENT_FEE_RATE, DEFAULT_PAYMENT_FEE_RATE);
        BigDecimal feeFixed = in.paymentFeeFixed() != null ? in.paymentFeeFixed() : config.getDecimal(FinanceConfigKeys.PAYMENT_FEE_FIXED, DEFAULT_PAYMENT_FEE_FIXED);
        BigDecimal reserveRate = in.returnsReserveRate() != null ? in.returnsReserveRate() : config.getDecimal(FinanceConfigKeys.RETURNS_RESERVE_RATE, DEFAULT_RETURNS_RESERVE_RATE);
        return calculate(in, feeRate, feeFixed, reserveRate);
    }

    /**
     * The P&L arithmetic with every rate resolved.
     *
     * @param feeRate     PSP variable fee fraction applied to net revenue
     * @param feeFixed    PSP fixed fee per order (charged only when net revenue is positive)
     * @param reserveRate returns reserve fraction applied to net revenue
     */
    public static Pnl calculate(PnlInput in, BigDecimal feeRate, BigDecimal feeFixed, BigDecimal reserveRate) {
        BigDecimal merchandise = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        for (PnlLine l : in.lines()) {
            merchandise = merchandise.add(l.gross());
            discounts = discounts.add(l.discount());
            cogs = cogs.add(l.totalCogs());
        }
        BigDecimal revenue = scale(merchandise.add(nz(in.shippingRevenue())));
        discounts = scale(discounts);
        cogs = scale(cogs);
        BigDecimal net = revenue.subtract(discounts);
        BigDecimal shipping = scale(nz(in.shippingCost()));
        BigDecimal labour = scale(nz(in.labourCost()));
        BigDecimal packaging = scale(nz(in.packagingCost()));
        BigDecimal landed = scale(nz(in.landedCost()));
        BigDecimal fees = net.signum() > 0 ? scale(net.multiply(nz(feeRate)).add(nz(feeFixed))) : scale(BigDecimal.ZERO);
        BigDecimal reserve = net.signum() > 0 ? scale(net.multiply(nz(reserveRate))) : scale(BigDecimal.ZERO);
        BigDecimal cm = net.subtract(cogs).subtract(shipping).subtract(labour).subtract(packaging).subtract(fees).subtract(reserve).subtract(landed);
        double ratio = net.signum() == 0 ? 0.0 : cm.divide(net, 4, ROUNDING).doubleValue();
        return new Pnl(revenue, discounts, cogs, shipping, labour, packaging, fees, reserve, landed, scale(cm), ratio);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static BigDecimal scale(BigDecimal v) { return v.setScale(SCALE, ROUNDING); }
}
