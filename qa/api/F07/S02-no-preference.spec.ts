/**
 * F07-S02 "No preference": each time offered once with the person the salon's strategy picks among those free then
 * (fewest or most minutes booked that day, ties in the Team page's order, eligible meaning not removed and performing
 * the service, shown online or not), and a hold placed with no professional assigned at that moment, falling to the
 * next free person when the first is taken, and racing for three free people.
 *
 * Expected results are the F07-S02 spec's AC-3 and AC-5 and decision 108 (a). Every expected answer is worked out by
 * `qa/shared/availability.ts` from the arrangement.  an
 * assignment is recorded in `booking_appointment.assigned`, returned by no endpoint (decision 110 e), so it is read from
 * the database. "Booked that day" counts active and buffer minutes of live bookings and unexpired holds (decision 110 i).
 */
import { refused, rules, type RulesInput } from '../../shared/booking-rules';
import { minus, noPreference, range, type Person } from '../../shared/availability';
import {
  CUT, PENDING_F07_S01, PENDING_F07_S02, bookingSalon, holdInput, liveRowsOf, memberInputFor, performs, storedBooking, type BookingSalon, type TeamMember,
} from '../../shared/booking';
import { identitySql } from '../../shared/identity-storage';
import { plusDays } from '../../shared/salon-hours';
import { fireTogether } from '../../shared/team';
import { Absences, blockInput } from '../../shared/team-leave';
import { test, expect, sqlLiteral } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

const LENGTH = CUT.minutes + CUT.buffer;
type Strategy = typeof PENDING_F07_S02.strategies[number];

/**
 * Six people in the Team page's order: Amaya with two bookings (120 minutes), Buddhika with a hold (60), Chathura and
 * Dinithi with none (Dinithi not shown online), both blocked 15:00 to 16:00; Erandi who does not do the service and
 * Gayan who was removed. Returns the four eligible as the oracle sees them, in the Team page's order.
 */
async function busyTeam(s: BookingSalon, date: string) {
  const add = (overrides: Record<string, unknown> = {}) => s.team.add(s.salon, s.owner.tokens, memberInputFor(s.service.id, overrides));
  const [amaya, buddhika] = s.members;
  const chathura = await add();
  const dinithi = await add({ shownOnline: false });
  const erandi = await add();
  await performs(s, erandi, []);
  const gayan = await add();
  await s.team.remove(s.salon, s.owner.tokens, await s.team.get(s.salon, s.owner.tokens, gayan.id));
  for (const time of ['09:00', '10:00']) await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(amaya.id, s.service.id, date, time))).id);
  await s.bookings.place(s.salon, s.owner.tokens, holdInput(buddhika.id, s.service.id, date, '11:00'));
  const absences = new Absences(s.team);
  for (const m of [chathura, dinithi]) await absences.addBlock(s.salon, s.owner.tokens, m.id, blockInput(date, { starts: '15:00', finishes: '16:00' }));
  const open = [range('09:00', '19:00')];
  const people: Record<string, Person> = {
    [amaya.id]: { id: amaya.id, name: amaya.name, windows: open, busy: [range('09:00', '10:00'), range('10:00', '11:00')] },
    [buddhika.id]: { id: buddhika.id, name: buddhika.name, windows: open, busy: [range('11:00', '12:00')] },
    [chathura.id]: { id: chathura.id, name: chathura.name, windows: minus(open, [range('15:00', '16:00')]), busy: [] },
    [dinithi.id]: { id: dinithi.id, name: dinithi.name, windows: minus(open, [range('15:00', '16:00')]), busy: [] },
  };
  const order = (await s.team.list(s.salon, s.owner.tokens)).map(m => m.id).filter(id => people[id]);
  expect(order.length, 'the four eligible people are on the Team page').toBe(4);
  return { eligible: order.map(id => people[id]), excluded: [erandi.id, gayan.id], amaya, buddhika, chathura, dinithi };
}

/** The salon's strategy; saving the one it already has would be an identical save, which F04 refuses. */
async function withStrategy(s: BookingSalon, strategy: Strategy) {
  const current = await s.bookingRules.settings(s.salon, s.owner.tokens);
  if ((current.rules as { noPreference?: string } | null)?.noPreference === strategy) return;
  await s.bookingRules.save(s.salon, s.owner.tokens, { ...rules({ minimumNoticeMinutes: 60, bookingHorizonDays: 60 }), noPreference: strategy } as RulesInput);
}

for (const [n, strategy] of [[10, 'least_booked'], [11, 'most_booked']] as const) {
  test(`TC-F07-S02-0${n} AC-3: with "${strategy}" each time is offered once, with the eligible person free then who has the ${strategy === 'least_booked' ? 'fewest' : 'most'} minutes booked that day, ties in the Team page's order; someone not shown online counts, someone who does not do the service or was removed never`, async ({ identity, operator, clock }) => {
    const s = await bookingSalon(identity, operator, clock, { people: 2 });
    await withStrategy(s, strategy);
    const date = plusDays(s.today, 3);
    const { eligible, excluded } = await busyTeam(s, date);
    const answer = await s.bookings.availability(s.salon, s.owner.tokens, { serviceId: s.service.id, date });
    const expected = noPreference(eligible, LENGTH, 15, strategy);
    expect(new Set(expected.map(e => e.memberId)).size, 'the arrangement makes the strategy choose between people').toBeGreaterThan(1);
    expect(answer.times.map(t => ({ time: t.time, memberId: t.member.id })), 'each time once, with the strategy\'s person').toEqual(expected);
    for (const id of excluded) expect(answer.times.some(t => t.member.id === id), 'never someone not eligible').toBe(false);
    expect(answer.reason).toBeNull();
  });
}

test('TC-F07-S02-012 AC-5: a hold with no professional goes to the person the strategy picks at that moment, to the next free one when that person was taken since the search, records that it was assigned, and is 409 slot_taken once nobody is free', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 3 });
  const date = plusDays(s.today, 2);
  const [first] = s.members;
  // Busy minutes that day: the first person 60, the others none; least_booked picks among the others in team order.
  await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(first.id, s.service.id, date, '09:00'))).id);
  const searched = (await s.bookings.availability(s.salon, s.owner.tokens, { serviceId: s.service.id, date })).times.find(t => t.time === '12:00')!;
  expect(searched, 'twelve o\'clock is offered').toBeTruthy();
  expect(searched.member.id, 'not the busier person').not.toBe(first.id);
  // Someone holds the offered person at that time before the desk does.
  await s.bookings.place(s.salon, s.owner.tokens, holdInput(searched.member.id, s.service.id, date, '12:00'));
  const assigned = await s.bookings.place(s.salon, s.owner.tokens, holdInput(null, s.service.id, date, '12:00'));
  const remaining = s.members.filter(m => m.id !== searched.member.id);
  const nextByStrategy = remaining.find(m => m.id !== first.id)!;
  expect(assigned.member.id, 'the next free person by the strategy').toBe(nextByStrategy.id);
  expect(storedBooking(s.created.id, assigned.id) as unknown as Record<string, unknown>, 'recorded as assigned').toMatchObject({ [PENDING_F07_S02.assignedField]: true });
  // memberId left out is "No preference" too (decision 110 a).
  const { memberId: _left, ...withoutMember } = holdInput(null, s.service.id, date, '12:00');
  const last = await s.bookings.place(s.salon, s.owner.tokens, withoutMember);
  expect(last.member.id, 'then the last free one').toBe(first.id);
  expect(storedBooking(s.created.id, last.id) as unknown as Record<string, unknown>, 'left out: assigned too').toMatchObject({ [PENDING_F07_S02.assignedField]: true });
  await refused(await s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(null, s.service.id, date, '12:00')), 409, 'slot_taken', 'nobody is free');
});

test('TC-F07-S02-013 AC-5: of ten simultaneous holds with no professional for one time with three eligible free people, three are held by three different people and seven are 409 slot_taken', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 3 });
  const date = plusDays(s.today, 4);
  const { answers, overlapped } = await fireTogether(Array.from({ length: 10 }, () => () => s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(null, s.service.id, date, '10:00'))), 0);
  expect(overlapped, 'sent together').toBe(true);
  const won = answers.filter(a => a.status() === 201);
  expect(answers.map(a => a.status()).sort(), 'three held').toEqual([...Array(3).fill(201), ...Array(7).fill(409)]);
  for (const lost of answers.filter(a => a.status() !== 201)) await refused(lost, 409, 'slot_taken', 'a loser');
  const people = (await Promise.all(won.map(a => a.json()))).map(b => b.member.id).sort();
  expect(people, 'three different people').toEqual(s.members.map((m: TeamMember) => m.id).sort());
  for (const m of s.members) expect(liveRowsOf(s.created.id, m.id).length, `${m.name}: one live row`).toBe(1);
  expect(Number(identitySql(`SELECT count(*) FROM ${PENDING_F07_S01.tables.booking} WHERE tenant_id = ${sqlLiteral(s.created.id)}`)), 'nothing else stored').toBe(3);
});
