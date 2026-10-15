package ai.intentoms.finance.tax;

import ai.intentoms.finance.model.Money;

/**
 * One taxable line.
 *
 * @param lineId  order line id; key of the per-line result maps
 * @param sku     item identifier (sent as itemCode to external engines)
 * @param amount  extended taxable amount for the line (price × qty − discount)
 * @param taxCode product tax code; {@code EXEMPT} / {@code NT} lines are never taxed by the flat engine
 */
public record TaxLine(String lineId, String sku, Money amount, String taxCode) {}
