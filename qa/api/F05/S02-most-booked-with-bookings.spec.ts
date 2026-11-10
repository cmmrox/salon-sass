/**
 * F05-S02's case deferred to F07, run with F07's feature pass now that bookings exist to count: "Most booked" ranks up to
 * six services by their bookings, most first, and leaves out a hidden and a deleted service however many bookings they
 * have.
 *
 * Expected results are F05-S02's AC-6 and its deferred entry's reason (stage.yaml), and F07-S01's handoff (note 11: it
 * counts confirmed, arrived, in-service and completed bookings), with the last 90 days ending at now (decision 092; the
 * owner's ruling for PR #26, BUG-F07-005, over note 11's "future included"), so every counted booking has taken place. The 90-day window's far edge would need the clock moved
 * past 90 days, which also moves the salon's billing into read-only (F03); the developer's integration test holds that edge
 * (F05-S02 spec), and this case says so rather than prove it. Registered under F05-S02 at F07's feature-verify.
 */
import { again, bookingSalon, holdInput, performs, plusMinutes, utcOf, type BookingSalon } from '../../shared/booking';
import { serviceInput, type Service } from '../../shared/catalogue';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 300_000 });

test('TC-F05-S02-015 AC-6: "Most booked" ranks the six services with the most bookings, most first, leaving out a hidden and a deleted service', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 3 });
  const make = async (name: string) => s.catalogue.create(s.salon, s.owner.tokens, serviceInput(s.category.id, { name, durationMinutes: 25, bufferMinutes: 5 }));
  const names = ['Blow-dry', 'Fringe trim', 'Beard shape', 'Scalp treatment', 'Kids cut', 'Braids', 'Updo'];
  const ranked: Service[] = [];
  for (const name of names) ranked.push(await make(name));
  const hidden = await make('Hair spa');
  const deleted = await make('Keratin rinse');
  for (const m of s.members) await performs(s, m, [...ranked, hidden, deleted].map(x => x.id));
  // Bookings, all today and all taken place when the list is read: Updo 1 ... Blow-dry 7 (the first two people, 09:00 to
  // 16:00), Keratin rinse 8 (the third, 09:00 to 13:00, then deleted), Hair spa 9 (the third, 13:00 to 17:30, then hidden).
  let slot = 0;
  const book = async (svc: Service, date: string, member = s.members[slot % 2]) => {
    const time = plusMinutes('09:00', 30 * Math.floor(slot++ / 2));
    await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(member.id, svc.id, date, time))).id);
  };
  for (const [n, svc] of ranked.entries()) for (let k = 0; k < 7 - n; k++) await book(svc, s.today);
  const third = async (svc: Service, from: string, count: number) => {
    for (let k = 0; k < count; k++) {
      const time = plusMinutes(from, 30 * k);
      await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[2].id, svc.id, s.today, time))).id);
    }
  };
  await third(deleted, '09:00', 8);
  await third(hidden, '13:00', 9);
  await s.catalogue.online(s.salon, s.owner.tokens, await s.catalogue.service(s.salon, s.owner.tokens, hidden.id), false);
  await clock.set(utcOf(s.today, '18:31'));
  const owner = await again(identity, s.owner);
  await s.catalogue.remove(s.salon, owner.tokens, await s.catalogue.service(s.salon, owner.tokens, deleted.id));
  const setting = await s.catalogue.setMode(s.salon, owner.tokens, 'most_booked');
  expect(setting.popular, 'the six most booked, most first; not hidden, not deleted').toEqual(ranked.slice(0, 6).map(x => x.id));
  expect((s as BookingSalon).service.id, 'the salon\'s own service has no bookings and is not ranked').not.toBe(setting.popular[0]);
});
