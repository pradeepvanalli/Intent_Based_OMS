package ai.intentoms.order.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.pnl.OrderPnlCalculator;
import ai.intentoms.finance.pnl.Pnl;
import ai.intentoms.finance.pnl.PnlInput;
import ai.intentoms.finance.pnl.PnlLine;
import ai.intentoms.finance.tax.TaxDestination;
import ai.intentoms.finance.tax.TaxEngine;
import ai.intentoms.finance.tax.TaxLine;
import ai.intentoms.finance.tax.TaxRequest;
import ai.intentoms.finance.tax.TaxResult;
import ai.intentoms.order.config.OrderConfigKeys;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.Charge;
import ai.intentoms.order.model.MarginSnapshot;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.Totals;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tax, totals and margin for an order (FR-FIN-02, FR-FIN-04): used at capture and re-run after every modification so
 * totals, line tax and the {@link MarginSnapshot} every decision sees stay current (design principle 7). Lines whose
 * status is {@code cancelled} are excluded. Shipping cost, labour and packaging are unknown at capture (0) and refined
 * by sourcing through {@code OrderService.recomputeMargin}. When every line is cancelled the header charges (shipping,
 * fees, duties, discounts) are dropped too, so a fully cancelled order totals zero.
 */
@Component
public class OrderPricing {
    public record Priced(List<OrderLine> lines, Totals totals, TaxResult tax) {}

    private final TaxEngine taxEngine;
    private final OrderPnlCalculator pnl;
    private final TenantConfigStore config;
    private final TimeSource time;

    public OrderPricing(TaxEngine taxEngine, OrderPnlCalculator pnl, TenantConfigStore config, TimeSource time) {
        this.taxEngine = taxEngine;
        this.pnl = pnl;
        this.config = config;
        this.time = time;
    }

    /** Quotes tax per line, writes it onto the lines and sums totals (merchandise, discounts, shipping, tax, duties, fees, grand total). */
    public Priced price(List<OrderLine> lines, List<Charge> charges, Optional<Address> shipTo, String currency, String customerRef) {
        List<TaxLine> taxLines = new ArrayList<>();
        for (OrderLine l : lines) if (active(l)) {
            Object code = l.attributes() == null ? null : l.attributes().get("tax_code");
            taxLines.add(new TaxLine(l.lineId(), l.sku(), l.extendedPrice(), code == null ? null : code.toString()));
        }
        Money shipping = sum(charges, currency, "shipping", "handling", "gift_wrap");
        TaxDestination dest = shipTo.map(a -> new TaxDestination(a.state(), a.zip(), a.country() == null ? "US" : a.country(), a.line1(), a.city())).orElse(new TaxDestination(null, null, "US"));
        TaxResult tax = taxEngine.calculate(new TaxRequest(taxLines, shipping, dest, customerRef));
        List<OrderLine> priced = new ArrayList<>(lines.size());
        Money merchandise = Money.zero(currency), discounts = Money.zero(currency);
        for (OrderLine l : lines) {
            if (!active(l)) { priced.add(l.withTax(Money.zero(currency))); continue; }
            Money lineTax = tax.perLine().getOrDefault(l.lineId(), Money.zero(currency));
            priced.add(l.withTax(lineTax));
            merchandise = merchandise.add(l.unitPrice().multiply(l.qty()));
            if (l.discount() != null) discounts = discounts.add(l.discount());
        }
        boolean anyActive = lines.stream().anyMatch(OrderPricing::active);
        Money headerDiscounts = anyActive ? sum(charges, currency, "discount").abs() : Money.zero(currency);
        discounts = discounts.add(headerDiscounts);
        Money duties = anyActive ? sum(charges, currency, "duty") : Money.zero(currency);
        Money fees = anyActive ? sum(charges, currency, "fee") : Money.zero(currency);
        if (!anyActive) shipping = Money.zero(currency);
        Money grand = merchandise.subtract(discounts).add(shipping).add(tax.total()).add(duties).add(fees);
        if (grand.isNegative()) grand = Money.zero(currency);
        return new Priced(priced, new Totals(merchandise, discounts, shipping, tax.total(), duties, fees, grand), tax);
    }

    /** Contribution margin snapshot against the tenant floor; costs default to 0 when not yet known. */
    public MarginSnapshot margin(List<OrderLine> lines, Totals totals, BigDecimal shippingCost, BigDecimal labourCost, BigDecimal packagingCost, MarginSnapshot previous) {
        List<PnlLine> pnlLines = new ArrayList<>();
        for (OrderLine l : lines) if (active(l)) pnlLines.add(new PnlLine(l.qty(), l.unitPrice().amount(), l.discount() == null ? null : l.discount().amount(), l.cogs().amount()));
        BigDecimal shippingRevenue = totals == null || totals.shipping() == null ? BigDecimal.ZERO : totals.shipping().amount();
        BigDecimal landed = totals == null || totals.duties() == null ? BigDecimal.ZERO : totals.duties().amount();
        Pnl p = pnl.compute(PnlInput.of(pnlLines, shippingRevenue, shippingCost, labourCost, packagingCost, landed));
        double floor = config.getDouble(OrderConfigKeys.MARGIN_FLOOR, 0.0);
        boolean approval = previous != null && previous.approvalPresent();
        return new MarginSnapshot(p.revenue(), p.contributionMargin(), p.marginRatio(), floor, !p.meetsMarginFloor(floor), approval,
            previous == null ? null : previous.approvalTaskId(), time.now());
    }

    /** Re-prices an order after its lines/addresses/charges changed, keeping any margin approval. */
    public Order reprice(Order o, BigDecimal shippingCost, BigDecimal labourCost, BigDecimal packagingCost) {
        Priced p = price(o.lines(), o.charges(), o.shipTo(), o.currency(), o.customerRef());
        MarginSnapshot m = margin(p.lines(), p.totals(), shippingCost, labourCost, packagingCost, o.margin());
        return o.withLines(p.lines()).withTotals(p.totals()).withMargin(m);
    }

    static boolean active(OrderLine l) { return !"cancelled".equals(l.status()); }

    private static Money sum(List<Charge> charges, String currency, String... types) {
        Money m = Money.zero(currency);
        if (charges == null) return m;
        for (Charge c : charges) for (String t : types) if (t.equals(c.type()) && c.amount() != null && c.amount().currency().equals(currency)) m = m.add(c.amount());
        return m;
    }
}
