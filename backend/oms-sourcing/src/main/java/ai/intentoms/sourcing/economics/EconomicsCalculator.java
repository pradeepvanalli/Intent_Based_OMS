package ai.intentoms.sourcing.economics;

import ai.intentoms.finance.landedcost.LandedCost;
import ai.intentoms.finance.landedcost.LandedCostService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.pnl.OrderPnlCalculator;
import ai.intentoms.finance.pnl.Pnl;
import ai.intentoms.finance.pnl.PnlInput;
import ai.intentoms.finance.pnl.PnlLine;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.sourcing.model.SimulationContext;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.rates.RateCard;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Computed economics of a package and a plan (FR-SRC-02): shipping from the rate card, labour (costProfile
 * {@code labour_per_unit} × units), packaging (per package), handling ({@code handling} × units), markdown risk
 * ({@code attributes.markdown_risk} × line value), landed cost through {@link LandedCostService} when the node's customs
 * origin differs from the destination country (FR-FIN-05; multiplied by the simulated tariff delta), and the split
 * penalty per package beyond the first. The plan P&L comes from {@link OrderPnlCalculator} over the allocated lines
 * (FR-FIN-04) with handling folded into labour and markdown risk into packaging, the two buckets {@code PnlInput} offers
 * for variable fulfilment cost.
 */
@Service
public class EconomicsCalculator {
    /** Quantity of one request line going into a package. */
    public record LineAlloc(SourcingRequest.RequestLine line, int qty) {
        public Money value() { return line.extendedPrice(qty); }
    }

    /** Cost components of one package. */
    public record PackageEconomics(Money shipping, Money labour, Money packaging, Money handling, Money landed, Money markdown, RateCard.Quote quote, LandedCost landedDetail) {
        public Money total() { return shipping.add(labour).add(packaging).add(handling).add(landed).add(markdown); }
        public double weightKg(List<LineAlloc> allocs) { double w = 0; for (LineAlloc a : allocs) w += a.line().weightKg() * a.qty(); return w; }
    }

    private final LandedCostService landedCost;
    private final OrderPnlCalculator pnl;
    private final TimeSource time;

    public EconomicsCalculator(LandedCostService landedCost, OrderPnlCalculator pnl, TimeSource time) {
        this.landedCost = landedCost;
        this.pnl = pnl;
        this.time = time;
    }

    /** Economics of shipping {@code allocs} from {@code node} to the request's destination over {@code distanceKm}. */
    public PackageEconomics packageEconomics(RateCard card, Node node, List<LineAlloc> allocs, SourcingRequest req, double distanceKm) {
        String ccy = req.currency();
        int units = 0;
        double weight = 0;
        Money markdown = Money.zero(ccy);
        for (LineAlloc a : allocs) {
            units += a.qty();
            weight += a.line().weightKg() * a.qty();
            if (a.line().markdownRisk() > 0) markdown = markdown.add(a.value().multiply(a.line().markdownRisk()));
        }
        RateCard.Quote quote = card.quote(req.isPickup() ? RateCard.PICKUP : req.shipMethod(), distanceKm, weight);
        Money shipping = Money.of(quote.cost().amount(), ccy);
        Money labour = Money.of(node.costNumber("labour_per_unit", 0.0) * units, ccy);
        Money packaging = Money.of(node.costNumber("packaging", 0.0), ccy);
        Money handling = Money.of(node.costNumber("handling", 0.0) * units, ccy);
        Money landed = Money.zero(ccy);
        LandedCost detail = null;
        if (!node.customsOrigin().equalsIgnoreCase(req.destination().country())) {
            double multiplier = SimulationContext.current().tariffMultiplier();
            for (LineAlloc a : allocs) {
                LandedCost lc = landedCost.estimate(node.customsOrigin(), req.destination().country(), a.line().hsCode(), a.value(), time.now());
                landed = landed.add(Money.of(lc.total().amount(), ccy).multiply(multiplier));
                if (detail == null) detail = lc;
            }
        }
        return new PackageEconomics(shipping, labour, packaging, handling, landed, markdown, quote, detail);
    }

    /** Split penalty: {@code perExtraPackage × max(0, packages − 1)}. */
    public static Money splitPenalty(double perExtraPackage, int packages, String currency) {
        return Money.of(perExtraPackage * Math.max(0, packages - 1), currency);
    }

    /** Order P&L for the allocated lines with this plan's costs; tenant defaults for payment fee and returns reserve. */
    public Pnl pnl(List<LineAlloc> allocated, Money shippingRevenue, List<PackageEconomics> packages, Money splitPenalty) {
        List<PnlLine> lines = new ArrayList<>(allocated.size());
        for (LineAlloc a : allocated) {
            SourcingRequest.RequestLine l = a.line();
            BigDecimal discount = l.qty() == 0 ? BigDecimal.ZERO : l.discount().amount().multiply(BigDecimal.valueOf(a.qty())).divide(BigDecimal.valueOf(l.qty()), 2, RoundingMode.HALF_EVEN);
            lines.add(new PnlLine(a.qty(), l.unitPrice().amount(), discount, l.cogs().amount()));
        }
        BigDecimal shipping = splitPenalty == null ? BigDecimal.ZERO : splitPenalty.amount();
        BigDecimal labour = BigDecimal.ZERO, packaging = BigDecimal.ZERO, landed = BigDecimal.ZERO;
        for (PackageEconomics p : packages) {
            shipping = shipping.add(p.shipping().amount());
            labour = labour.add(p.labour().amount()).add(p.handling().amount());
            packaging = packaging.add(p.packaging().amount()).add(p.markdown().amount());
            landed = landed.add(p.landed().amount());
        }
        return pnl.compute(PnlInput.of(lines, shippingRevenue == null ? BigDecimal.ZERO : shippingRevenue.amount(), shipping, labour, packaging, landed));
    }
}
