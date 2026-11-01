/**
 * What a tax-inclusive price includes, for the "What customers pay" preview as the form changes (F05-S03 AC-4, AC-5).
 * The rule is decision 095's, the API's own (`TaxParts`): for a price P in minor units, VAT rate v and service charge
 * s where they apply, `beforeVat = round(P / (1 + v))`, `vat = P - beforeVat`, `net = round(beforeVat / (1 + s))`,
 * `serviceCharge = beforeVat - net`, half to even. A shared table of cases holds both to it. Whole numbers only: a
 * price up to Rs. 500,000 times 100 stays far inside the safe integers.
 */
export type TaxParts = { netMinor: number; serviceChargeMinor: number; vatMinor: number };

/** `dividend / divisor` for a dividend not negative and a positive divisor, rounded half to even. */
function halfEven(dividend: number, divisor: number): number {
  const quotient = Math.floor(dividend / divisor);
  const twiceRemainder = 2 * (dividend - quotient * divisor);
  if (twiceRemainder > divisor || (twiceRemainder === divisor && quotient % 2 !== 0)) return quotient + 1;
  return quotient;
}

const removed = (amount: number, percent: number) => (percent === 0 ? amount : halfEven(amount * 100, 100 + percent));

/** The parts of a price under the rates that apply: 0 for a part that does not. */
export function taxParts(priceMinor: number, vatPercent: number, serviceChargePercent: number): TaxParts {
  const beforeVat = removed(priceMinor, vatPercent);
  const net = removed(beforeVat, serviceChargePercent);
  return { netMinor: net, serviceChargeMinor: beforeVat - net, vatMinor: priceMinor - beforeVat };
}
