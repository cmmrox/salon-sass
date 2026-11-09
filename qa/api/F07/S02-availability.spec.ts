/**
 * F07-S02 the free times staff are offered for one professional: exactly the starts that fit every input BKG-01 names,
 * a change a moment before showing at once, the slot interval from midnight, today after now, the dates staff may ask
 * for, the reasons for an empty answer, the open days, who may ask, another salon's data, and the contract.
 *
 * Expected results are the F07-S02 spec's (AC-1 to AC-4, AC-6, AC-9, AC-10, AC-12) and decision 108 (c). Every expected
 * list of times is worked out by `qa/shared/availability.ts` from the arrangement the test made, never read from the
 * product. Names the build gives are in `PENDING_F07_S02`, confirmed against the S02 handoff and decision 110 (f: 409
 * rules_not_set without rules; g: today strictly after now; h: fully_booked also for a window too short). "No preference"
 * and holds without a professional are in
 * S02-no-preference.spec.ts; the rule settings in S02-rules.spec.ts.
 */
import { randomUUID } from 'node:crypto';

import { refused, rules } from '../../shared/booking-rules';
import { minus, range, startsFor, toClock, toMinutes, type Person } from '../../shared/availability';
import {
  AVAILABILITY, CUT, OPEN_DAYS, PENDING_F07_S02, again, bookingSalon, holdInput, localAt, performs, refusedOnField, refusedWithDetail, staffAt, variantService, type BookingSalon,
} from '../../shared/booking';
import { serviceInput } from '../../shared/catalogue';
import { Seen } from '../../shared/contract';
import { plusDays, plusYear, week, weekdayOf } from '../../shared/salon-hours';
import { Absences, blockInput, leaveInput } from '../../shared/team-leave';
import { Schedules, brk, weekOf } from '../../shared/team-schedule';
import { test, expect, supportJson, type Operator, type SupportStart } from '../../shared/operator';
import type { Tokens } from '../../shared/identity';

test.describe.configure({ timeout: 180_000 });

const LENGTH = CUT.minutes + CUT.buffer;
const times = (a: { times: { time: string }[] }) => a.times.map(t => t.time);
const ask = (s: BookingSalon, tokens: Tokens, date: string, memberId?: string, extra: Record<string, string> = {}) =>
  s.bookings.availability(s.salon, tokens, { serviceId: s.service.id, date, memberId, ...extra });

/** One professional on a working day: 08:00 to 20:00 (wider than the salon's 09:00 to 19:00) with a break at 13:00. */
async function busyDay(s: BookingSalon, date: string) {
  const [m] = s.members;
  await new Schedules(s.team).save(s.salon, s.owner.tokens, m.id, weekOf('08:00', '20:00', [], { [weekdayOf(date)]: { breaks: [brk('13:00', '14:00')] } }));
  await new Absences(s.team).addBlock(s.salon, s.owner.tokens, m.id, blockInput(date, { starts: '15:00', finishes: '16:00' }));
  const booking = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, '10:00'))).id);
  const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, '16:30'));
  const person = (busy: [string, string][]): Person => ({
    id: m.id, name: m.name,
    windows: minus([range('09:00', '19:00')], [range('13:00', '14:00'), range('15:00', '16:00')]),
    busy: busy.map(([a, b]) => range(a, b)),
  });
  return { m, booking, hold, person };
}

async function supportTokens(operator: Operator, s: BookingSalon): Promise<Tokens> {
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, s.created.id, { reason: 'The salon asked why a time is not offered' }), 'supportStart', 201);
  return operator.supportCredential(await operator.exchange(session.entryCode, s.salon.hostname));
}

// ---------------------------------------------------------------- AC-1, AC-2

test('TC-F07-S02-001 AC-1, AC-2: one professional\'s times are exactly the starts on the salon\'s interval where the whole booking, its buffer included, fits inside the salon\'s hours and their working time and overlaps no booking or hold of theirs, in time order', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const date = plusDays(s.today, 3);
  const { m, person } = await busyDay(s, date);
  const answer = await ask(s, s.owner.tokens, date, m.id);
  const expected = startsFor(person([['10:00', '11:00'], ['16:30', '17:30']]), LENGTH, 15).map(toClock);
  expect(expected.length, 'the arrangement leaves times to offer').toBeGreaterThan(5);
  expect(answer.date, 'the date asked for').toBe(date);
  expect(times(answer), 'exactly the starts that fit, in time order').toEqual(expected);
  expect(answer.reason, 'no reason when there are times').toBeNull();
  for (const t of answer.times) {
    expect(t.startsAt, `${t.time}: the instant in the salon's zone`).toBe(localAt(date, t.time));
    expect(t.member, `${t.time}: who would take it`).toEqual({ id: m.id, name: m.name });
  }
});

test('TC-F07-S02-002 AC-1: a booking, a hold, a release, a hold running out, a cancellation, leave and a block made a moment before are in the next answer', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const date = plusDays(s.today, 3);
  const { m, booking, hold, person } = await busyDay(s, date);
  const offered = async () => times(await ask(s, (await again(identity, s.owner)).tokens, date, m.id));
  const expect_ = (busy: [string, string][]) => startsFor(person(busy), LENGTH, 15).map(toClock);
  await test.step('a release', async () => {
    await s.bookings.release(s.salon, s.owner.tokens, hold.id);
    expect(await offered()).toEqual(expect_([['10:00', '11:00']]));
  });
  await test.step('a cancellation', async () => {
    await s.bookings.act(s.salon, s.owner.tokens, booking.id, 'cancel', { reason: 'customer_asked' });
    expect(await offered()).toEqual(expect_([]));
  });
  await test.step('a new hold', async () => {
    await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, '11:15'));
    expect(await offered()).toEqual(expect_([['11:15', '12:15']]));
  });
  await test.step('the hold running out', async () => {
    await clock.set(new Date(Date.parse(await identity.now()) + 11 * 60_000).toISOString());
    expect(await offered()).toEqual(expect_([]));
  });
  await test.step('a block', async () => {
    const owner = await again(identity, s.owner);
    await new Absences(s.team).addBlock(s.salon, owner.tokens, m.id, blockInput(date, { starts: '09:00', finishes: '10:00' }));
    expect(await offered()).toEqual(startsFor({ ...person([]), windows: minus(person([]).windows, [range('09:00', '10:00')]) }, LENGTH, 15).map(toClock));
  });
  await test.step('leave', async () => {
    const owner = await again(identity, s.owner);
    await new Absences(s.team).addLeave(s.salon, owner.tokens, m.id, leaveInput(date));
    const answer = await ask(s, owner.tokens, date, m.id);
    expect([times(answer), answer.reason], 'nothing, as they are not working').toEqual([[], 'not_working']);
  });
});

test('TC-F07-S02-003 AC-2: starts follow the salon\'s slot interval from midnight; today\'s start after now; staff are not bound by the customer notice or horizon; a date before today or more than a year ahead is a 400 on date', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1, startAt: '10:07' });
  const [m] = s.members;
  const free: Person = { id: m.id, name: m.name, windows: [range('09:00', '19:00')], busy: [] };
  await test.step('today: after now, inside the customer notice', async () => {
    expect(times(await ask(s, s.owner.tokens, s.today, m.id)), 'from 10:15, the first start after 10:07').toEqual(startsFor(free, LENGTH, 15, toMinutes('10:07')).map(toClock));
  });
  await test.step('beyond the customer horizon, and exactly a year ahead', async () => {
    for (const date of [plusDays(s.today, 200), plusYear(s.today)]) expect(times(await ask(s, s.owner.tokens, date, m.id)), date).toEqual(startsFor(free, LENGTH, 15).map(toClock));
  });
  await test.step('a 20-minute interval', async () => {
    await s.bookingRules.save(s.salon, s.owner.tokens, rules({ slotIntervalMinutes: 20, minimumNoticeMinutes: 60, bookingHorizonDays: 60 }));
    const answer = await ask(s, s.owner.tokens, plusDays(s.today, 1), m.id);
    expect(times(answer), ':00, :20 and :40').toEqual(startsFor(free, LENGTH, 20).map(toClock));
  });
  await test.step('dates staff may not ask for', async () => {
    for (const [label, date] of [['yesterday', plusDays(s.today, -1)], ['a day beyond a year', plusDays(plusYear(s.today), 1)], ['not a date', 'next-friday']]) {
      await refusedOnField(await s.bookings.availabilityRaw(s.salon, s.owner.tokens, { serviceId: s.service.id, date, memberId: m.id }), 'date', label);
    }
  });
});

// ---------------------------------------------------------------- AC-3 one professional

test('TC-F07-S02-004 AC-3, AC-10: a professional who does not perform the service is 409 slot_unavailable and a salon without rules 409 rules_not_set; a removed one, another salon\'s, and another salon\'s or an unknown service or variant answer 404 not_found as an unknown id', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 3 });
  const other = await bookingSalon(identity, operator, clock, { people: 1, keepClock: true });
  const otherColour = await variantService(other);
  const [, notCut, gone] = s.members;
  await performs(s, notCut, []);
  await s.team.remove(s.salon, s.owner.tokens, await s.team.get(s.salon, s.owner.tokens, gone.id));
  const date = plusDays(s.today, 2);
  const q = (query: Record<string, string>) => s.bookings.availabilityRaw(s.salon, s.owner.tokens, { serviceId: s.service.id, date, ...query });
  await refusedWithDetail(await q({ memberId: notCut.id }), 'slot_unavailable', 'does not perform it');
  const unknown = await refused(await q({ memberId: randomUUID() }), 404, 'not_found', 'an unknown professional');
  const strip = (body: string) => body.replace(/"(traceId|instance)":"[^"]*"/g, '');
  const rows: [string, Record<string, string>][] = [
    ['a removed professional', { memberId: gone.id }],
    ['another salon\'s professional', { memberId: other.members[0].id }],
    ['an unknown service', { serviceId: randomUUID() }],
    ['another salon\'s service', { serviceId: other.service.id }],
    ['another salon\'s variant', { serviceId: (await variantService(s, [s.members[0]])).id, variantId: otherColour.terms.variants[0].id }],
  ];
  for (const [label, query] of rows) {
    const { answer } = await refused(await q(query), 404, 'not_found', label);
    if (query.memberId) expect(strip(answer.body), `${label}: as an unknown id`).toBe(strip(unknown.answer.body));
  }
  const bare = await bookingSalon(identity, operator, clock, { people: 1, rulesSaved: false, keepClock: true });
  await refused(await bare.bookings.availabilityRaw(bare.salon, bare.owner.tokens, { serviceId: bare.service.id, date, memberId: bare.members[0].id }),
    409, 'rules_not_set', 'a salon that never saved its rules (decision 110 f)');
});

// ---------------------------------------------------------------- AC-4 empty answers

test('TC-F07-S02-005 AC-4: an empty answer says why: closed, nobody_performs, not_working for the chosen professional and for everyone, fully_booked', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const [m1, m2] = s.members;
  const date = plusDays(s.today, 5);
  const empty = async (label: string, reason: string, query: Record<string, string | undefined>) => {
    const answer = await s.bookings.availability(s.salon, s.owner.tokens, { serviceId: s.service.id, date, ...query });
    expect([answer.times, answer.reason], label).toEqual([[], reason]);
    expect(PENDING_F07_S02.reasons as readonly string[], `${reason} is one of the spec's`).toContain(reason);
  };
  await test.step('fully booked', async () => {
    for (let t = toMinutes('09:00'); t + LENGTH <= toMinutes('19:00'); t += LENGTH) {
      for (const m of [m1, m2]) await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, toClock(t)));
    }
    await empty('one professional', 'fully_booked', { memberId: m1.id });
    await empty('no preference', 'fully_booked', {});
  });
  await test.step('not working', async () => {
    const day = plusDays(date, 1);
    await new Absences(s.team).addLeave(s.salon, s.owner.tokens, m1.id, leaveInput(day));
    await empty('the chosen professional on leave', 'not_working', { memberId: m1.id, date: day });
    await new Absences(s.team).addLeave(s.salon, s.owner.tokens, m2.id, leaveInput(day));
    await empty('every eligible professional on leave', 'not_working', { date: day });
  });
  await test.step('closed', async () => {
    const day = plusDays(date, 2);
    await s.hours.add(s.salon, s.owner.tokens, day, 'Staff training day');
    await empty('a closure', 'closed', { memberId: m1.id, date: day });
    await empty('a closure, no preference', 'closed', { date: day });
  });
  await test.step('nobody performs it', async () => {
    const lonely = await s.catalogue.create(s.salon, s.owner.tokens, serviceInput(s.category.id, { name: 'Keratin treatment', durationMinutes: 120, bufferMinutes: 0 }));
    await empty('no one does it', 'nobody_performs', { serviceId: lonely.id, date: plusDays(date, 3) });
  });
});

// ---------------------------------------------------------------- AC-6 open days

test('TC-F07-S02-006 AC-6: the open days from today are the dates the branch is open, leaving out a closed weekday and a closure; a start before today and a range over 400 days are 400s', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const closedDay = weekdayOf(plusDays(s.today, 2));
  await s.hours.save(s.salon, s.owner.tokens, week('09:00', '19:00', [closedDay]));
  const closure = plusDays(s.today, 4);
  await s.hours.add(s.salon, s.owner.tokens, closure, 'Owner\'s wedding');
  const to = plusDays(s.today, 20);
  const all = Array.from({ length: 21 }, (_, n) => plusDays(s.today, n));
  const expected = all.filter(d => weekdayOf(d) !== closedDay && d !== closure);
  expect(await s.bookings.openDays(s.salon, s.owner.tokens, s.today, to), 'open dates, in order').toEqual(expected);
  expect((await s.bookings.openDays(s.salon, s.owner.tokens, s.today, plusDays(s.today, 399))).length, '400 dates may be asked for').toBeGreaterThan(300);
  await refusedOnField(await s.bookings.openDaysRaw(s.salon, s.owner.tokens, plusDays(s.today, -1), to), 'from', 'from yesterday');
  await refusedOnField(await s.bookings.openDaysRaw(s.salon, s.owner.tokens, s.today, plusDays(s.today, 400)), 'to', '401 dates');
  await refusedOnField(await s.bookings.openDaysRaw(s.salon, s.owner.tokens, to, s.today), 'to', 'to before from');
});

// ---------------------------------------------------------------- AC-9 roles

test('TC-F07-S02-007 AC-9: the Owner, a Manager and a Receptionist read free times and open days; a Professional, an Accountant and a support session get 403 permission_denied; signed out is 401', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const date = plusDays(s.today, 1);
  const free = { serviceId: s.service.id, date, memberId: s.members[0].id };
  for (const [who, tokens] of [['the Owner', s.owner.tokens], ['a Manager', (await staffAt(s, 'MANAGER')).tokens], ['a Receptionist', (await staffAt(s, 'RECEPTIONIST')).tokens]] as const) {
    expect((await s.bookings.availability(s.salon, tokens, free)).times.length, `${who}: free times`).toBeGreaterThan(0);
    expect((await s.bookings.openDays(s.salon, tokens, s.today, date)).length, `${who}: open days`).toBe(2);
  }
  const refusedRoles: [string, Tokens][] = [
    ['a Professional', (await staffAt(s, 'PROFESSIONAL', { linkTo: s.members[0] })).tokens],
    ['an Accountant', (await staffAt(s, 'ACCOUNTANT')).tokens],
    ['a support session', await supportTokens(operator, s)],
  ];
  for (const [who, tokens] of refusedRoles) {
    await refused(await s.bookings.availabilityRaw(s.salon, tokens, free), 403, 'permission_denied', `${who}: free times`);
    await refused(await s.bookings.openDaysRaw(s.salon, tokens, s.today, date), 403, 'permission_denied', `${who}: open days`);
  }
  await refused(await s.bookings.availabilityRaw(s.salon, undefined, free), 401, 'invalid_session', 'signed out: free times');
  await refused(await s.bookings.openDaysRaw(s.salon, undefined, s.today, date), 401, 'invalid_session', 'signed out: open days');
});

// ---------------------------------------------------------------- AC-10 isolation

test('TC-F07-S02-008 AC-10: another salon\'s bookings, holds, leave, blocks, closures and rules on the same date change nothing in one salon\'s free times', async ({ identity, operator, clock }) => {
  const a = await bookingSalon(identity, operator, clock, { people: 1 });
  const b = await bookingSalon(identity, operator, clock, { people: 1, keepClock: true, rules: { slotIntervalMinutes: 30 } });
  const date = plusDays(a.today, 3);
  const free: Person = { id: a.members[0].id, name: a.members[0].name, windows: [range('09:00', '19:00')], busy: [] };
  const expected = startsFor(free, LENGTH, 15).map(toClock);
  expect(times(await ask(a, a.owner.tokens, date, a.members[0].id)), 'before').toEqual(expected);
  const bm = b.members[0].id;
  await b.bookings.confirm(b.salon, b.owner.tokens, (await b.bookings.place(b.salon, b.owner.tokens, holdInput(bm, b.service.id, date, '10:00'))).id);
  await b.bookings.place(b.salon, b.owner.tokens, holdInput(bm, b.service.id, date, '14:00'));
  await new Absences(b.team).addBlock(b.salon, b.owner.tokens, bm, blockInput(date, { starts: '16:00', finishes: '17:00' }));
  await b.hours.add(b.salon, b.owner.tokens, plusDays(date, 1), 'Closed for repairs');
  expect((await b.bookings.availability(b.salon, b.owner.tokens, { serviceId: b.service.id, date, memberId: bm })).times.length, 'the other salon sees its own busy day').toBeLessThan(startsFor(free, LENGTH, 30).length);
  expect(times(await ask(a, a.owner.tokens, date, a.members[0].id)), 'after: unchanged').toEqual(expected);
  expect((await ask(a, a.owner.tokens, plusDays(date, 1))).reason, 'the other salon\'s closure does not close this one').toBeNull();
});

// ---------------------------------------------------------------- AC-12 contract

test('TC-F07-S02-009 AC-12: the free-times and open-days reads, and a hold with no professional, answer their successes and documented errors exactly as the committed contract declares', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const [m, notCut] = s.members;
  await performs(s, notCut, []);
  const accountant = await staffAt(s, 'ACCOUNTANT');
  const date = plusDays(s.today, 2);
  const seen = new Seen();
  // `null` is signed out: an undefined argument would take the Owner's credentials by default.
  const free = (query: Record<string, string>, tokens: Tokens | null = s.owner.tokens) =>
    s.bookings.availabilityRaw(s.salon, tokens ?? undefined, { serviceId: s.service.id, date, ...query });
  await seen.check(free({ memberId: m.id }), 'GET', AVAILABILITY, '200 one professional');
  await seen.check(free({}), 'GET', AVAILABILITY, '200 no preference');
  await seen.check(free({ date: 'soon' }), 'GET', AVAILABILITY, '400');
  await seen.check(free({}, null), 'GET', AVAILABILITY, '401');
  await seen.check(free({}, accountant.tokens), 'GET', AVAILABILITY, '403');
  await seen.check(free({ memberId: randomUUID() }), 'GET', AVAILABILITY, '404');
  await seen.check(free({ memberId: notCut.id }), 'GET', AVAILABILITY, '409');
  await seen.check(s.bookings.openDaysRaw(s.salon, s.owner.tokens, s.today, date), 'GET', OPEN_DAYS, '200');
  await seen.check(s.bookings.openDaysRaw(s.salon, s.owner.tokens, date, s.today), 'GET', OPEN_DAYS, '400');
  await seen.check(s.bookings.openDaysRaw(s.salon, undefined, s.today, date), 'GET', OPEN_DAYS, '401');
  await seen.check(s.bookings.openDaysRaw(s.salon, accountant.tokens, s.today, date), 'GET', OPEN_DAYS, '403');
  await seen.check(s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(null, s.service.id, date, '10:00')), 'POST', '/api/v1/bookings', '201 no preference');
  // Open days' documented 404 answers a host that is no salon's; with a salon's own host it cannot be reached.
  seen.expectAllSeen([['GET', AVAILABILITY], ['GET', OPEN_DAYS]], { [`GET ${OPEN_DAYS}`]: ['404'] });
});
