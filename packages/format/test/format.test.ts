import { describe, expect, it } from 'vitest';
import {
  currencyMark,
  formatClock,
  formatCount,
  formatDay,
  formatMoney,
  formatPhoneNational,
  minorUnitsText,
  parseMinorUnits,
  todayInZone,
} from '../src/index';

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
  it('writes the cents even when they are zero when asked to, as the parts of a price are shown', () => {
    expect(formatMoney(38100, 'LKR', 'en-LK', { cents: 'always' })).toBe('Rs. 381.00');
    expect(formatMoney(38136, 'LKR', 'en-LK', { cents: 'always' })).toBe('Rs. 381.36');
    expect(formatMoney(19260000, 'LKR', 'en-LK', { cents: 'always' })).toBe('Rs. 192,600.00');
    expect(formatMoney(2000, 'USD', 'en-US', { cents: 'always' })).toBe('$20.00');
    expect(formatMoney(38100, 'LKR', 'en-LK', { cents: 'when-not-zero' })).toBe('Rs. 381');
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

describe('currency marks (F04-S04)', () => {
  it('marks rupees as formatMoney writes them and any other currency by its code', () => {
    expect(currencyMark('LKR')).toBe('Rs.');
    expect(currencyMark('USD')).toBe('USD');
  });
});

describe('counts and dates', () => {
  it('groups a count for the locale', () => {
    expect(formatCount(1000)).toBe('1,000');
    expect(formatCount(25)).toBe('25');
  });
  it("gives today's date in the zone, not the machine's", () => {
    const evening = new Date('2026-10-29T20:00:00Z');
    expect(todayInZone('Asia/Colombo', evening)).toBe('2026-10-30');
    expect(todayInZone('Europe/London', evening)).toBe('2026-10-29');
  });
});

describe("a salon's local times and dates (F04-S03)", () => {
  it('writes a local time on the 24-hour clock, with no time zone applied', () => {
    expect(formatClock('09:30')).toBe('09:30');
    expect(formatClock('18:00')).toBe('18:00');
  });
  it('writes a local date with its weekday, the same in every time zone', () => {
    const shown = formatDay('2026-10-25');
    expect(shown).toContain('Sun');
    expect(shown).toContain('25');
    expect(shown).toContain('2026');
    expect(formatDay('2026-12-31')).toContain('31');
  });
});

describe('phone numbers', () => {
  it('shows an E.164 number in its national format', () => {
    expect(formatPhoneNational('+94771234567')).toBe('077 123 4567');
    // libphonenumber's own national pattern for a Colombo landline (the API's library agrees).
    expect(formatPhoneNational('+94112504417')).toBe('0112 504 417');
    expect(formatPhoneNational('+442079460958')).toBe('020 7946 0958');
  });
  it('leaves anything that is not a valid E.164 number as it is', () => {
    expect(formatPhoneNational('077 123')).toBe('077 123');
    expect(formatPhoneNational('')).toBe('');
  });
});
