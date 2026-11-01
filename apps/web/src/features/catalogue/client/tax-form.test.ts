import { describe, expect, it } from 'vitest';
import { newVatRate, sameTax, taxFormOf, taxProblemsOf, taxRequestOf } from './tax-form';
import { productProblemsOf, productRequestOf } from './product-form';

const SAVED = {
  vat: { registered: true, registrationNumber: 'LK-1' },
  serviceCharge: { on: true, percent: 10, onProducts: true },
};
const CONFIGURED = {
  vatName: 'VAT',
  vatRatePercent: 18,
  registrationLabel: 'VAT registration number',
  serviceChargeName: 'Service charge',
  serviceChargeMaxPercent: 20,
};

describe('the tax form (F05-S03 AC-2, AC-3)', () => {
  it('starts both off with the prototype’s 10% suggested, and opens a saved version as it is', () => {
    expect(taxFormOf(null)).toEqual({
      vatRegistered: false,
      registrationNumber: '',
      serviceChargeOn: false,
      percent: '10',
      onProducts: false,
    });
    expect(sameTax(taxFormOf(SAVED), SAVED)).toBe(true);
  });

  it('checks only the parts that are on', () => {
    const form = { ...taxFormOf(null), vatRegistered: true, serviceChargeOn: true, percent: '0' };
    expect(taxProblemsOf(form, 20)).toEqual(['registrationNumber', 'percent']);
    expect(taxProblemsOf({ ...form, registrationNumber: 'A'.repeat(20), percent: '20' }, 20)).toEqual([]);
    expect(taxProblemsOf({ ...form, registrationNumber: 'A'.repeat(21), percent: '1.5' }, 20)).toEqual([
      'registrationNumber',
      'percent',
    ]);
    expect(taxProblemsOf({ ...form, vatRegistered: false, serviceChargeOn: false }, 20)).toEqual([]);
  });

  it('sends no number or rate for a part that is off, and products only with a service charge', () => {
    const typed = { ...taxFormOf(SAVED), vatRegistered: false, serviceChargeOn: false };
    expect(taxRequestOf(typed, 3)).toEqual({
      version: 3,
      vat: { registered: false },
      serviceCharge: { on: false, onProducts: false },
    });
    expect(sameTax({ ...taxFormOf(SAVED), registrationNumber: ' LK-1 ' }, SAVED)).toBe(true);
  });

  it('names the new VAT rate only while the current version charges VAT at another rate', () => {
    const settings = { version: 1, settings: SAVED, configured: { ...CONFIGURED, vatRatePercent: 15 } };
    const current = { versionId: 'v', vat: { name: 'VAT', ratePercent: 18 }, serviceCharge: null };
    expect(newVatRate(settings, current)).toBe(15);
    expect(newVatRate(settings, { ...current, vat: null })).toBeNull();
    expect(newVatRate({ ...settings, configured: CONFIGURED }, current)).toBeNull();
  });
});

describe('the product form (AC-8)', () => {
  it('checks the name and price as the prototype does and sends whole rupees as minor units', () => {
    const values = { name: ' Comb ', price: '1,500', chargesVat: false, soldAtCheckout: true };
    expect(productProblemsOf(values, ['Hair oil'])).toEqual([]);
    expect(productProblemsOf({ ...values, name: 'comb' }, ['COMB'])).toEqual(['taken']);
    expect(productProblemsOf({ ...values, name: 'C', price: '0' }, [])).toEqual(['name', 'price']);
    expect(productProblemsOf({ ...values, price: '15.50' }, [])).toEqual(['price']);
    expect(productRequestOf(values)).toEqual({
      name: 'Comb',
      priceMinor: 150_000,
      chargesVat: false,
      soldAtCheckout: true,
    });
  });
});
