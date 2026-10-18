import { describe, expect, it } from 'vitest';
import { formatMoney, minorUnitsText, parseMinorUnits } from './index';

describe('money formatting', () => {
  it('writes rupees with cents only when they are not zero', () => {
    expect(formatMoney(250000, 'LKR')).toBe('Rs. 2,500');
    expect(formatMoney(250050, 'LKR')).toBe('Rs. 2,500.50');
    expect(formatMoney(5, 'LKR')).toBe('Rs. 0.05');
    expect(formatMoney(100000000, 'LKR')).toBe('Rs. 1,000,000');
  });
  it('writes other currencies in the locale format with the same cents rule', () => {
    expect(formatMoney(1999, 'USD', 'en-US')).toBe('$19.99');
    expect(formatMoney(2000, 'USD', 'en-US')).toBe('$20');
  });
  it('moves the decimal point of typed major units without floating point', () => {
    expect(parseMinorUnits('2500')).toBe(250000);
    expect(parseMinorUnits(' 2,500.5 ')).toBe(250050);
    expect(parseMinorUnits('0.07')).toBe(7);
    expect(parseMinorUnits('1.005')).toBe('1.005');
    expect(parseMinorUnits('-3')).toBe('-3');
    expect(parseMinorUnits('abc')).toBe('abc');
    expect(minorUnitsText(250050)).toBe('2500.50');
    expect(minorUnitsText(250000)).toBe('2500');
  });
});
