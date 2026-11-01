import { describe, expect, it } from 'vitest';
import {
  checkNumber,
  fieldOfError,
  formOf,
  sameRules,
  saveOf,
  shownErrors,
  SUGGESTED,
  typeNumber,
  choose,
  valuesOf,
} from './rules-form';

describe('the Booking rules form (F04-S04)', () => {
  it('starts from the prototype’s suggested rules before the first save (AC-1)', () => {
    expect(formOf(null).values).toEqual(SUGGESTED);
    expect(saveOf(SUGGESTED)).toEqual({
      slotIntervalMinutes: 15,
      minimumNoticeMinutes: 60,
      bookingHorizonDays: 60,
      deposit: { kind: 'percentage', percent: 20, capMinor: 200_000 },
      freeCancellationHours: 24,
      lateCancellation: { kind: 'deposit' },
      noShow: { kind: 'deposit' },
      reschedule: { limit: 2, untilHours: 12 },
    });
    expect(formOf(null).texts).toMatchObject({ percent: '20', cap: '2000', amount: '1000' });
  });

  it('reads saved rules back exactly, and sends only the fields of each kind', () => {
    const saved = {
      slotIntervalMinutes: 30,
      minimumNoticeMinutes: 0,
      bookingHorizonDays: 180,
      deposit: { kind: 'amount' as const, amountMinor: 150_000 },
      freeCancellationHours: 48,
      lateCancellation: { kind: 'share' as const, percent: 40 },
      noShow: { kind: 'share' as const, percent: 90 },
      reschedule: { limit: null, untilHours: 24 },
    };
    expect(saveOf(valuesOf(saved))).toEqual(saved);
    const none = { ...saved, deposit: { kind: 'none' as const }, reschedule: { limit: 0, untilHours: null } };
    expect(saveOf(valuesOf(none))).toEqual({ ...none, lateCancellation: { kind: 'share', percent: 40 } });
  });

  it('checks every boundary as the prototype does, with rupees kept as minor units and no rounding (AC-3)', () => {
    for (const field of ['percent', 'latePercent', 'noShowPercent'] as const) {
      expect(checkNumber(field, '0')).toEqual({ error: 'percentRange' });
      expect(checkNumber(field, '1')).toEqual({ value: 1 });
      expect(checkNumber(field, '100')).toEqual({ value: 100 });
      expect(checkNumber(field, '101')).toEqual({ error: 'percentRange' });
      expect(checkNumber(field, '12.5')).toEqual({ error: 'percentRange' });
    }
    expect(checkNumber('cap', '499')).toEqual({ error: 'capMinimum' });
    expect(checkNumber('cap', '500')).toEqual({ value: 50_000 });
    expect(checkNumber('cap', '2,000')).toEqual({ value: 200_000 });
    expect(checkNumber('cap', '9007199254740992')).toEqual({ error: 'capMaximum' });
    expect(checkNumber('amount', '99')).toEqual({ error: 'amountRange' });
    expect(checkNumber('amount', '100')).toEqual({ value: 10_000 });
    expect(checkNumber('amount', '100000')).toEqual({ value: 10_000_000 });
    expect(checkNumber('amount', '100001')).toEqual({ error: 'amountRange' });
    expect(checkNumber('amount', 'Rs. 500')).toEqual({ error: 'amountRange' });
  });

  it('keeps the last valid value while a typed field is wrong, and holds the save back only for shown fields', () => {
    const typed = typeNumber(formOf(null), 'percent', '120');
    expect(typed.values.percent).toBe(20);
    expect(typed.texts.percent).toBe('120');
    expect(shownErrors(typed)).toEqual({ percent: 'percentRange' });
    expect(shownErrors(choose(typed, 'deposit', 'amount'))).toEqual({});
    const fixed = typeNumber(typed, 'percent', '35');
    expect(fixed.values.percent).toBe(35);
    expect(shownErrors(fixed)).toEqual({});
  });

  it('counts only what a save would send as a change', () => {
    const changedHidden = typeNumber(choose(formOf(null), 'deposit', 'none'), 'percent', '50');
    expect(sameRules(changedHidden.values, { ...SUGGESTED, deposit: 'none' })).toBe(true);
    expect(sameRules(SUGGESTED, { ...SUGGESTED, slotIntervalMinutes: 30 })).toBe(false);
  });

  it('places the API’s refusal of an amount or a share beside its field', () => {
    expect(fieldOfError('rules.deposit.capMinor')).toBe('cap');
    expect(fieldOfError('rules.noShow.percent')).toBe('noShowPercent');
    expect(fieldOfError('rules.slotIntervalMinutes')).toBeNull();
  });
});
