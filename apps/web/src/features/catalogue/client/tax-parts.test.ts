import { describe, expect, it } from 'vitest';
import shared from '../../../../../api/src/test/resources/catalogue/tax-parts-cases.json';
import { taxParts } from './tax-parts';

type Case = {
  note: string;
  priceMinor: number;
  vatPercent: number;
  serviceChargePercent: number;
  netMinor: number;
  serviceChargeMinor: number;
  vatMinor: number;
};

// The table the API's TaxPartsTest is held to, so that the preview and the API work out the same parts.
const table = shared as { cases: Case[] };

describe('the preview works out the parts as the API does (F05-S03 AC-4)', () => {
  it('gives every shared case', () => {
    expect(table.cases.length).toBeGreaterThan(100);
    for (const each of table.cases) {
      expect(taxParts(each.priceMinor, each.vatPercent, each.serviceChargePercent), each.note).toEqual({
        netMinor: each.netMinor,
        serviceChargeMinor: each.serviceChargeMinor,
        vatMinor: each.vatMinor,
      });
    }
  });

  it('gives the prototype’s figures, and parts that add up for every rupee to Rs. 10,000', () => {
    expect(taxParts(250000, 18, 10)).toEqual({ netMinor: 192604, serviceChargeMinor: 19260, vatMinor: 38136 });
    expect(taxParts(320000, 18, 0)).toEqual({ netMinor: 271186, serviceChargeMinor: 0, vatMinor: 48814 });
    for (let rupees = 1; rupees <= 10000; rupees++) {
      const price = rupees * 100;
      const parts = taxParts(price, 18, 20);
      expect(parts.netMinor + parts.serviceChargeMinor + parts.vatMinor).toBe(price);
    }
  });
});
