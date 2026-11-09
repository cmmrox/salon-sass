/**
 * F07-S01 the guards and lists other modules asked booking for: what counts as an upcoming booking when a service is
 * deleted, a person removed or a service taken from them; each of those racing a hold; the bookings a closure, an hours
 * change, a week, leave and a block affect; and "Most booked" counting real bookings.
 *
 * Expected results are the F07-S01 spec's AC-13 and its handoff (note 11: upcoming means held and not run out, pending
 * payment, confirmed, arrived or in service, whose end has not passed; the affected lists hold held, pending payment,
 * confirmed, arrived and in service; "Most booked" counts confirmed, arrived, in service and completed). How each earlier
 * feature words its refusal and its lists on screen is that feature's deferred case (TC-F04-S03-031, TC-F05-S01-020,
 * TC-F05-S02-015, TC-F06-S01-011, -012, TC-F06-S02-029, TC-F06-S03-031), run with F07's feature pass.
 * A race sweeps the gaps in both orders and asserts that each side won at least once and that requests really overlapped.
 */
import type { APIResponse } from '@playwright/test';

import { refused } from '../../shared/booking-rules';
import {
  again, bookingSalon, customer, holdInput, liveRowsOf, performs, utcOf, type BookingSalon, type TeamMember,
} from '../../shared/booking';
import { serviceInput, type Service } from '../../shared/catalogue';
import { json } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import { DAYS, plusDays, week, weekdayOf } from '../../shared/salon-hours';
import { fireTogether, memberInput, updateOf } from '../../shared/team';
import { Absences, blockAffectedPath, leaveAffectedPath, leaveInput } from '../../shared/team-leave';
import { Schedules, weekOf } from '../../shared/team-schedule';
import { test, expect, sqlLiteral } from '../../shared/operator';

test.describe.configure({ timeout: 300_000 });

const GAPS = [0, 1, 3, 8, 21];

/** A second service the salon's members perform. */
async function anotherService(s: BookingSalon, name: string, performers: TeamMember[]): Promise<Service> {
  const service = await s.catalogue.create(s.salon, s.owner.tokens, serviceInput(s.category.id, { name, durationMinutes: 30, bufferMinutes: 0 }));
  for (const m of performers) {
    const current = await s.team.get(s.salon, s.owner.tokens, m.id);
    await performs(s, m, [...current.services.serviceIds, service.id]);
  }
  return service;
}
const without = (s: BookingSalon, m: TeamMember, serviceId: string) =>
  s.team.get(s.salon, s.owner.tokens, m.id).then(current =>
    s.team.saveRaw(s.salon, s.owner.tokens, m.id, updateOf(current, { services: { categoryIds: [], serviceIds: current.services.serviceIds.filter(id => id !== serviceId) } })));

test('TC-F07-S01-032 AC-13: a hold or a confirmed booking still to come stops the service\'s delete, the person\'s removal and taking the service from them with 409 in_use; once only cancelled, no-show, past and run-out ones are left, each succeeds', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const [m1, m2] = s.members;
  const colour = await anotherService(s, 'Root touch-up', [m1, m2]);
  const later = plusDays(s.today, 2);
  const blocked = async (label: string) => {
    await test.step(label, async () => {
      const service = await s.catalogue.service(s.salon, s.owner.tokens, colour.id);
      await refused(await s.catalogue.deleteRaw(s.salon, s.owner.tokens, colour.id, service.version), 409, 'in_use', `${label}: delete the service`);
      const member = await s.team.get(s.salon, s.owner.tokens, m1.id);
      await refused(await s.team.removeRaw(s.salon, s.owner.tokens, m1.id, member.version), 409, 'in_use', `${label}: remove the person`);
      await refused(await without(s, m1, colour.id), 409, 'in_use', `${label}: take the service from them`);
      expect((await s.team.get(s.salon, s.owner.tokens, m1.id)).services.serviceIds, `${label}: still performs it`).toContain(colour.id);
    });
  };
  const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m1.id, colour.id, later, '10:00'));
  await blocked('a hold');
  await s.bookings.release(s.salon, s.owner.tokens, hold.id);
  const booking = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(m1.id, colour.id, later, '12:00'))).id);
  await blocked('a confirmed booking');
  await s.bookings.act(s.salon, s.owner.tokens, booking.id, 'cancel', { reason: 'customer_asked' });

  // What is left: a cancellation (above), a no-show, a booking that has ended and a hold that ran out.
  const past = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(m1.id, colour.id, s.today, '09:00'))).id);
  const noShow = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(m2.id, colour.id, s.today, '09:00'))).id);
  await s.bookings.place(s.salon, s.owner.tokens, holdInput(m2.id, colour.id, plusDays(s.today, 3), '10:00'));
  await clock.set(utcOf(s.today, '09:05'));
  await s.bookings.act(s.salon, (await again(identity, s.owner)).tokens, noShow.id, 'no-show');
  await clock.set(utcOf(s.today, '09:31'));
  const owner = await again(identity, s.owner);
  s.owner.tokens = owner.tokens;
  expect(Date.parse(past.endsAt), 'the 09:00 booking has ended').toBeLessThanOrEqual(Date.parse(await identity.now()));
  const saved = await without(s, m1, colour.id);
  expect(saved.status(), `take the service from them (${(await saved.text()).slice(0, 200)})`).toBe(200);
  const service = await s.catalogue.service(s.salon, owner.tokens, colour.id);
  const deleted = await s.catalogue.deleteRaw(s.salon, owner.tokens, colour.id, service.version);
  expect(deleted.status(), `delete the service (${(await deleted.text()).slice(0, 200)})`).toBe(204);
  for (const m of [m1, m2]) await s.team.remove(s.salon, owner.tokens, await s.team.get(s.salon, owner.tokens, m.id));
});

test('TC-F07-S01-033 AC-13: a service\'s delete, a person\'s removal and taking a service from them, each sent with a hold for it at once, never both succeed', async ({ identity, operator, clock }) => {
  test.setTimeout(600_000);
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const date = plusDays(s.today, 5);
  const races: { name: string; round: (n: number) => Promise<{ guard: () => Promise<APIResponse>; hold: () => Promise<APIResponse>; consistent: (guardWon: boolean, holdWon: boolean) => Promise<void> }> }[] = [
    { name: 'delete the service', round: async (n) => {
      const service = await anotherService(s, `Hair spa ${n}`, [s.members[0]]);
      const version = (await s.catalogue.service(s.salon, s.owner.tokens, service.id)).version;
      return {
        guard: () => s.catalogue.deleteRaw(s.salon, s.owner.tokens, service.id, version),
        hold: () => s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(s.members[0].id, service.id, date, '10:00')),
        consistent: async (guardWon) => {
          const live = Number(identitySql(`SELECT count(*) FROM booking_appointment WHERE tenant_id = ${sqlLiteral(s.created.id)} AND service_id = ${sqlLiteral(service.id)} AND state = 'held'`));
          if (guardWon) expect(live, 'no hold for a deleted service').toBe(0);
          else expect((await s.catalogue.serviceRaw(s.salon, s.owner.tokens, service.id)).status(), 'the service is kept').toBe(200);
          if (!guardWon) await s.bookings.release(s.salon, s.owner.tokens, (await s.bookings.day(s.salon, s.owner.tokens, date)).find(b => b.service.id === service.id)!.id);
        } };
    } },
    { name: 'remove the person', round: async () => {
      const person = await s.team.add(s.salon, s.owner.tokens, memberInput({ services: { categoryIds: [], serviceIds: [s.service.id] } }));
      return {
        guard: () => s.team.removeRaw(s.salon, s.owner.tokens, person.id, person.version),
        hold: () => s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, date, '10:00')),
        consistent: async (guardWon) => {
          const live = liveRowsOf(s.created.id, person.id).length;
          if (guardWon) expect(live, 'no hold for a removed person').toBe(0);
          else expect((await s.team.getRaw(s.salon, s.owner.tokens, person.id)).status(), 'the person is kept').toBe(200);
        } };
    } },
    { name: 'take the service from them', round: async () => {
      const person = await s.team.add(s.salon, s.owner.tokens, memberInput({ services: { categoryIds: [], serviceIds: [s.service.id] } }));
      return {
        guard: () => s.team.saveRaw(s.salon, s.owner.tokens, person.id, updateOf(person, { services: { categoryIds: [], serviceIds: [] } })),
        hold: () => s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, date, '10:00')),
        consistent: async (guardWon) => {
          const live = liveRowsOf(s.created.id, person.id).length;
          const performsIt = (await s.team.get(s.salon, s.owner.tokens, person.id)).services.serviceIds.includes(s.service.id);
          if (guardWon) expect([live, performsIt], 'no hold for a service they no longer perform').toEqual([0, false]);
          else expect([live, performsIt], 'the hold, and they still perform it').toEqual([1, true]);
        } };
    } },
  ];
  for (const race of races) {
    await test.step(race.name, async () => {
      const wins = { guard: 0, hold: 0 };
      let n = 0;
      for (const gap of GAPS) {
        for (const guardFirst of [true, false]) {
          const { guard, hold, consistent } = await race.round(n++);
          const sends = guardFirst ? [guard, hold] : [hold, guard];
          const { answers, overlapped } = await fireTogether(sends, gap);
          if (gap === 0) expect(overlapped, `${race.name}: sent together`).toBe(true);
          const [g, h] = guardFirst ? answers : [answers[1], answers[0]];
          const guardWon = g.status() < 300;
          const holdWon = h.status() === 201;
          expect(guardWon && holdWon, `${race.name}, gap ${gap}, ${guardFirst ? 'guard' : 'hold'} first: never both (${g.status()}, ${h.status()})`).toBe(false);
          expect(guardWon || holdWon, `${race.name}, gap ${gap}: one of them succeeds (${g.status()} ${(await g.text()).slice(0, 120)}, ${h.status()} ${(await h.text()).slice(0, 120)})`).toBe(true);
          if (guardWon) wins.guard++; else wins.hold++;
          await consistent(guardWon, holdWon);
        }
      }
      test.info().annotations.push({ type: race.name, description: `guard won ${wins.guard}, hold won ${wins.hold}` });
      expect(wins.guard, `${race.name}: the guard won at least once`).toBeGreaterThan(0);
      expect(wins.hold, `${race.name}: the hold won at least once`).toBeGreaterThan(0);
    });
  }
});

test('TC-F07-S01-034 AC-13: a closure, an hours change, a person\'s week, leave and a block list exactly the live bookings they affect (holds and bookings, not cancelled or ended ones, not one that only touches), and every booking is kept', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const [m1, m2] = s.members;
  const d = plusDays(s.today, 4);
  const [amaya, kavindi, nethmi, other] = [customer(), customer(), customer(), customer()];
  const place = (m: TeamMember, time: string, who = customer()) => s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, d, time, { customer: who }));
  const confirmedA = await s.bookings.confirm(s.salon, s.owner.tokens, (await place(m1, '10:00', amaya)).id);
  const heldK = await place(m1, '12:00', kavindi);
  const confirmedN = await s.bookings.confirm(s.salon, s.owner.tokens, (await place(m2, '11:00', nethmi)).id);
  const cancelled = await s.bookings.confirm(s.salon, s.owner.tokens, (await place(m1, '14:00', other)).id);
  await s.bookings.act(s.salon, s.owner.tokens, cancelled.id, 'cancel', { reason: 'customer_asked' });
  await s.bookings.release(s.salon, s.owner.tokens, (await place(m1, '16:00', other)).id);
  const names = (list: { customerName: string }[]) => list.map(x => x.customerName).sort();
  const live = (...who: { name: string }[]) => who.map(w => w.name).sort();

  await test.step('a closure (BookingsOnDate)', async () => {
    const impact = await s.hours.impact(s.salon, s.owner.tokens, d);
    expect(names(impact.bookings), 'the day\'s live bookings').toEqual(live(amaya, kavindi, nethmi));
    expect(impact.bookings.find(b => b.customerName === amaya.name), 'its time and service').toMatchObject({ time: '10:00', serviceName: s.service.name });
  });
  await test.step('leave (MemberBookingsAffected)', async () => {
    const r = await s.team.call(s.salon, 'GET', leaveAffectedPath(m1.id, d, d), s.owner.tokens);
    expect(names((await json<{ affected: { customerName: string }[] }>(r, 'TeamPeriodImpact')).affected), 'the person\'s live bookings that day').toEqual(live(amaya, kavindi));
  });
  await test.step('a block: overlapping counts, touching does not', async () => {
    const overlap = await json<{ affected: { customerName: string }[] }>(await s.team.call(s.salon, 'GET', blockAffectedPath(m1.id, d, '10:30', '11:00'), s.owner.tokens), 'TeamPeriodImpact');
    expect(names(overlap.affected), 'overlapping').toEqual(live(amaya));
    const touching = await json<{ affected: unknown[] }>(await s.team.call(s.salon, 'GET', blockAffectedPath(m1.id, d, '11:00', '12:00'), s.owner.tokens), 'TeamPeriodImpact');
    expect(touching.affected, 'between two bookings, touching both').toEqual([]);
  });
  await test.step('the person\'s week', async () => {
    const saved = await new Schedules(s.team).save(s.salon, s.owner.tokens, m1.id, weekOf('09:00', '19:00', [weekdayOf(d)]));
    expect(names(saved.affected), 'a day off leaves their live bookings outside').toEqual(live(amaya, kavindi));
  });
  await test.step('the salon\'s hours', async () => {
    const saved = await s.hours.save(s.salon, s.owner.tokens, week('09:00', '19:00', [weekdayOf(d)]));
    expect(names(saved.outsideHours ?? []), 'a closed weekday leaves the day\'s live bookings outside').toEqual(expect.arrayContaining(live(amaya, kavindi, nethmi)));
    expect(names(saved.outsideHours ?? []), 'and nothing ended').not.toContain(other.name);
  });
  await test.step('adding leave counts them to resolve', async () => {
    const added = await new Absences(s.team).addLeave(s.salon, s.owner.tokens, m2.id, leaveInput(d));
    expect(names(added.affected as { customerName: string }[]), 'the affected list').toEqual(live(nethmi));
    expect(added.leave.bookingsToResolve, 'one to resolve').toBe(1);
  });
  for (const b of [confirmedA, confirmedN]) expect((await s.bookings.get(s.salon, s.owner.tokens, b.id)).booking.state, 'every booking is kept').toBe('confirmed');
  expect((await s.bookings.get(s.salon, s.owner.tokens, heldK.id)).booking.state, 'the hold too').toBe('held');
  expect(DAYS).toContain(weekdayOf(d));
});

test('TC-F07-S01-035 AC-13: "Most booked" ranks services by their real bookings, counting confirmed, arrived and in-service ones and not holds or cancellations', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const [m1, m2] = s.members;
  const x = await anotherService(s, 'Blow-dry', [m1, m2]);
  const y = await anotherService(s, 'Head massage', [m1, m2]);
  const book = (m: TeamMember, service: Service, time: string) => s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, service.id, s.today, time));
  // Blow-dry: three bookings (confirmed, arrived, in service). Head massage: one confirmed, three cancelled, three holds.
  const xs = [await book(m1, x, '09:00'), await book(m2, x, '09:00'), await book(m1, x, '10:00')];
  for (const b of xs) await s.bookings.confirm(s.salon, s.owner.tokens, b.id);
  await s.bookings.act(s.salon, s.owner.tokens, xs[1].id, 'arrive');
  await s.bookings.act(s.salon, s.owner.tokens, xs[2].id, 'arrive');
  await s.bookings.act(s.salon, s.owner.tokens, xs[2].id, 'start');
  await s.bookings.confirm(s.salon, s.owner.tokens, (await book(m2, y, '10:00')).id);
  for (const time of ['11:00', '12:00', '13:00']) {
    const b = await s.bookings.confirm(s.salon, s.owner.tokens, (await book(m1, y, time)).id);
    await s.bookings.act(s.salon, s.owner.tokens, b.id, 'cancel', { reason: 'customer_asked' });
  }
  for (const time of ['14:00', '15:00', '16:00']) await book(m2, y, time);
  const setting = await s.catalogue.setMode(s.salon, s.owner.tokens, 'most_booked');
  expect(setting.popular.slice(0, 2), 'Blow-dry, with three bookings, before Head massage with one').toEqual([x.id, y.id]);
});
