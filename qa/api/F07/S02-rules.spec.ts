/**
 * F07-S02 the two new booking rules: how long a hold lasts (5, 10, 15, 20 or 30 minutes) and where "No preference"
 * bookings go (`least_booked` or `most_booked`): their defaults, a save making a new version with its history, holds
 * taking their expiry from the current version while a hold already placed keeps its own, refused values, a save that
 * leaves them out keeping the current ones, and the public read showing the hold length but never the strategy.
 *
 * Expected results are the F07-S02 spec's AC-7, decision 089 and decision 110 (b, c, d): both fields may be left out of a
 * save, which keeps the current version's values, a value sent is checked strictly, and the public rules read gains
 * `holdMinutes` only (schema PublicBookingRuleTerms).
 */
import { BookingRules, fieldErrors, rules, type RulesInput } from '../../shared/booking-rules';
import { PENDING_F07_S02, bookingSalon, holdInput, minutesBetween } from '../../shared/booking';
import { plusDays } from '../../shared/salon-hours';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

type Stored = { holdMinutes?: number; noPreference?: string };
const base = () => rules({ minimumNoticeMinutes: 60, bookingHorizonDays: 60 });

test('TC-F07-S02-014 AC-7: a salon that never chose reads a 10-minute hold and "least booked"; saving 20 minutes and "most booked" makes a new version with both in its history; the next hold lasts 20 minutes and one placed before keeps its 10', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const before = await s.bookingRules.settings(s.salon, s.owner.tokens);
  expect(before.rules as Stored, 'the defaults').toMatchObject({ holdMinutes: 10, noPreference: 'least_booked' });
  const date = plusDays(s.today, 2);
  const early = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, '10:00'));
  expect(minutesBetween(early.createdAt, early.holdExpiresAt!), 'ten minutes').toBe(10);

  const saved = await s.bookingRules.save(s.salon, s.owner.tokens, { ...base(), holdMinutes: 20, noPreference: 'most_booked' } as RulesInput);
  expect(saved.version, 'a new version').toBe(before.version + 1);
  expect(saved.rules as Stored, 'saved').toMatchObject({ holdMinutes: 20, noPreference: 'most_booked' });
  const history = await s.bookingRules.history(s.salon, s.owner.tokens);
  expect(history.items[0].changedFields as string[], 'the history names both').toEqual(expect.arrayContaining(['holdMinutes', 'noPreference']));

  const later = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[1].id, s.service.id, date, '10:00'));
  expect(minutesBetween(later.createdAt, later.holdExpiresAt!), 'twenty minutes by the current version').toBe(20);
  expect((await s.bookings.get(s.salon, s.owner.tokens, early.id)).booking.holdExpiresAt, 'the earlier hold keeps its own expiry').toBe(early.holdExpiresAt);
});

test('TC-F07-S02-015 AC-7: every allowed hold length and strategy saves; any other value is a 400 on its field saving nothing; a save that leaves them out keeps the current values; the public read shows the hold length and never the strategy', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const book = new BookingRules(identity);
  for (const holdMinutes of PENDING_F07_S02.holdMinutes) {
    for (const noPreference of PENDING_F07_S02.strategies) {
      const saved = await book.save(s.salon, s.owner.tokens, { ...base(), holdMinutes, noPreference } as RulesInput);
      expect(saved.rules as Stored, `${holdMinutes} minutes, ${noPreference}`).toMatchObject({ holdMinutes, noPreference });
    }
  }
  const current = await book.settings(s.salon, s.owner.tokens);
  const rows: [string, string, Record<string, unknown>][] = [
    ['7 minutes', 'holdMinutes', { holdMinutes: 7 }],
    ['0 minutes', 'holdMinutes', { holdMinutes: 0 }],
    ['60 minutes', 'holdMinutes', { holdMinutes: 60 }],
    ['ten as text', 'holdMinutes', { holdMinutes: '10' }],
    ['a strategy that is not one', 'noPreference', { noPreference: 'round_robin' }],
    ['a strategy in capitals', 'noPreference', { noPreference: 'LEAST_BOOKED' }],
  ];
  for (const [label, field, change] of rows) {
    const { errors } = await fieldErrors(await book.saveRaw(s.salon, s.owner.tokens, current.version, { ...base(), ...change }), label);
    // The save's body is {version, rules}, so the error names rules.<field> (S02 handoff).
    expect(errors.map(e => e.field), `${label}: on ${field}`).toEqual([`rules.${field}`]);
  }
  expect((await book.settings(s.salon, s.owner.tokens)).version, 'nothing saved').toBe(current.version);

  const kept = await book.save(s.salon, s.owner.tokens, { ...base(), freeCancellationHours: 48 });
  expect(kept.rules as Stored, 'left out, they keep the current values').toMatchObject({ holdMinutes: 30, noPreference: 'most_booked' });
  const published = await book.publicRaw(s.salon);
  expect(published.status, 'the public read').toBe(200);
  expect((published.body as unknown as { rules: Stored }).rules.holdMinutes, 'shows how long a hold lasts').toBe(30);
  expect(published.text, 'never the strategy').not.toContain('noPreference');
  expect(published.text, 'nor its value').not.toContain('most_booked');
});
