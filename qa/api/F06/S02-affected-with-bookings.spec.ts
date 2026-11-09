/**
 * F06-S02's case deferred to F07, run with F07's feature pass now that bookings exist: a new week lists the upcoming
 * bookings it leaves outside the person's working time, a date change lists those it affects, and every booking is kept.
 *
 * Expected results are F06-S02's AC-11 and its deferred entry's reason (stage.yaml): "with F07 the list must name real
 * bookings, each kept". The sheet's words for the list are F06's case 041. Bookings are made through F07's endpoints.
 * Registered under F06-S02 at F07's feature-verify, when the deferred entry moves to automation.
 */
import { bookingSalon, customer, holdInput } from '../../shared/booking';
import { plusDays, weekdayOf } from '../../shared/salon-hours';
import { Schedules, changeInput, weekOf } from '../../shared/team-schedule';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

test('TC-F06-S02-029 AC-11: saving a week that takes a booked day off lists those upcoming bookings, a date change lists the bookings it leaves outside, and every booking is kept', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [person] = s.members;
  const schedules = new Schedules(s.team);
  const d1 = plusDays(s.today, 3);
  const d2 = plusDays(s.today, 4);
  const [amaya, kavindi, nethmi] = [customer(), customer(), customer()];
  const a = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, d1, '10:00', { customer: amaya }))).id);
  const k = await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, d2, '15:00', { customer: kavindi }));
  const n = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, d2, '10:00', { customer: nethmi }))).id);
  const saved = await schedules.save(s.salon, s.owner.tokens, person.id, weekOf('09:00', '19:00', [weekdayOf(d1)]));
  expect(saved.affected.map(x => [x.date, x.time, x.customerName, x.serviceName]), 'the booked day off').toEqual([[d1, '10:00', amaya.name, s.service.name]]);
  const added = await schedules.add(s.salon, s.owner.tokens, person.id, changeInput(d2, { starts: '09:00', finishes: '13:00' }));
  expect(added.affected.map(x => [x.date, x.time, x.customerName]), 'the date change leaves the afternoon outside').toEqual([[d2, '15:00', kavindi.name]]);
  for (const [b, state] of [[a, 'confirmed'], [k, 'held'], [n, 'confirmed']] as const) expect((await s.bookings.get(s.salon, s.owner.tokens, b.id)).booking.state, 'kept').toBe(state);
});
