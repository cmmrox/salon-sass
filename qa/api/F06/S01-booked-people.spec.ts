/**
 * F06-S01's case deferred to F07, run with F07's feature pass now that bookings exist: a removed person's name kept on
 * their past booking, and another salon's person not bookable (AC-4, the deferred entry's reason in its stage.yaml).
 * Cases 011 and 012, which also name the sheet's words, are in qa/web/F06/S01-team-with-bookings.spec.ts. Bookings are
 * made through F07's endpoints (qa/shared/booking.ts). Registered under F06-S01 at F07's feature-verify, when the
 * deferred entry moves to automation.
 */
import { refused } from '../../shared/booking-rules';
import { again, bookingSalon, holdInput, utcOf } from '../../shared/booking';
import { plusDays } from '../../shared/salon-hours';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

test('TC-F06-S01-013 AC-4: a removed person\'s past booking keeps their name, and another salon\'s person cannot be booked', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const other = await bookingSalon(identity, operator, clock, { people: 1, keepClock: true });
  const [person] = s.members;
  const booking = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, s.today, '09:00'))).id);
  await clock.set(utcOf(s.today, '10:05'));
  const owner = await again(identity, s.owner);
  await s.team.remove(s.salon, owner.tokens, await s.team.get(s.salon, owner.tokens, person.id));
  const kept = await s.bookings.get(s.salon, owner.tokens, booking.id);
  expect(kept.booking.member, 'the past booking keeps who it was with').toEqual({ id: person.id, name: person.name });
  await refused(await s.bookings.placeRaw(s.salon, owner.tokens, holdInput(other.members[0].id, s.service.id, plusDays(s.today, 1), '10:00')), 404, 'not_found', 'another salon\'s person');
});
