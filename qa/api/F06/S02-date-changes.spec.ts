/**
 * F06-S02 date changes from outside: adding one from today to a year ahead, different hours under the
 * week's rules or not working, the note, a second change on a date and two at once, the limit of 100
 * upcoming, removal, what a change leaves of the week and the week of it, and whose date "today" is.
 *
 * Expected results are the F06-S02 spec's (AC-7, AC-8, AC-10, AC-11's empty answer), decision 102 and the
 * lead's rulings for the build (`qa/shared/team-schedule.ts`). What the sheet says about each is in
 * qa/web/F06/S02-schedule-screens.spec.ts.
 *
 * What this cannot prove: what a date change does to a person's working windows is `TeamSchedule`'s, a Java
 * interface with no route until F07 (deferred case 028). Here AC-8 is proved on what `TeamSchedule` reads.
 *
 * AC-10's words ask for 23:30 in the salon's zone being the next day in UTC, which is true only west of
 * UTC. Colombo is UTC+05:30, where the same mistake shows the other way round: at 00:30 UTC is still on the
 * day before. So TC-F06-S02-020 tries a salon on each side of UTC, as neither alone catches a salon's date
 * taken from UTC at both ends of the allowed range.
 */
import { refused } from '../../shared/booking-rules';
import type { Identity, SignedStaff } from '../../shared/identity';
import { test, expect, afterNewestPlan, type Operator, type TestClock } from '../../shared/operator';
import { localDate, plusDays, plusYear } from '../../shared/salon-hours';
import { GAPS, Team, fireTogether, teamSalon } from '../../shared/team';
import {
  Schedules, brk, changeInput, comparableChange, expectWeek, offChange, refusedOnField, scheduleSnapshot, weekOf, SCHEDULE_LIMITS,
  type ChangeInput, type DateChange, type MemberSchedule,
} from '../../shared/team-schedule';
import type { BreakTime } from '../../shared/team';

test.describe.configure({ timeout: 120_000 });

const COLOMBO = 'Asia/Colombo';
const DATE = /^date$/;
const TIMES = /^(starts|finishes)$/;
const BREAKS = /^breaks($|[.[])/;

/** The salon's today with the clock standing at noon there, on a date after every plan, and a person. */
async function arranged(identity: Identity, operator: Operator, clock: TestClock) {
  const at = afterNewestPlan();
  await clock.set(at);
  const today = localDate(at, COLOMBO);
  const team = new Team(identity);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  return { today, team, schedules: new Schedules(team), salon, owner, created, person };
}

/** A sign-in on the moved clock, as an access token lasts five minutes. */
const signedInAgain = async (identity: Identity, owner: SignedStaff) => (await identity.login(owner.salon, owner.account)).tokens;

const dates = (schedule: MemberSchedule) => schedule.dateChanges.map(c => c.date);

// ---------------------------------------------------------------- AC-7 adding

test('TC-F06-S02-014 AC-7, AC-11: a change is saved from today to a year ahead, as different hours with breaks or as not working, with a note of up to 60 characters or none; each answers with no affected bookings, and the list is in date order', async ({ identity, operator, clock }) => {
  const { today, team, schedules, salon, owner, person } = await arranged(identity, operator, clock);
  expect((await schedules.read(salon, owner.tokens, person.id)).today, 'the schedule says the salon\'s today').toBe(today);
  const last = plusYear(today);
  const half: ChangeInput = changeInput(plusDays(today, 30), { starts: '10:15', finishes: '23:45', breaks: [brk('10:15', '10:45'), brk('13:00', '13:30'), brk('13:30', '14:00'), brk('20:00', '20:15'), brk('23:30', '23:45')], note: 'N'.repeat(SCHEDULE_LIMITS.noteLength) });
  const added: Record<string, DateChange> = {};
  await test.step('added out of date order', async () => {
    for (const input of [offChange(last, 'A year ahead'), half, changeInput(today, { starts: '00:00', finishes: '00:15' })]) {
      const answer = await schedules.add(salon, owner.tokens, person.id, input);
      expect(comparableChange(answer.change), `${String(input.date)}: holds what was sent`).toEqual(comparableChange(input));
      expect(answer.affected, 'no booking exists before F07, and the answer says so').toEqual([]);
      added[String(input.date)] = answer.change;
    }
  });
  expect(added[half.date as string].note, 'a note of exactly 60 characters is kept').toBe(half.note);
  expect(added[last].note).toBe('A year ahead');
  await test.step('read back, in date order', async () => {
    const read = await schedules.read(salon, owner.tokens, person.id);
    expect(dates(read), 'date order').toEqual([today, half.date, last]);
    expect(read.dateChanges.map(c => c.id)).toEqual([added[today].id, added[half.date as string].id, added[last].id]);
    expect(comparableChange(read.dateChanges[1]), 'its breaks and times').toEqual(comparableChange(half));
    expect(read.dateChanges[0].note ?? null, 'no note').toBeNull();
  });
  await test.step('not working keeps no times or breaks, as a weekday does', async () => {
    const date = plusDays(today, 40);
    const { change } = await schedules.add(salon, owner.tokens, person.id, changeInput(date, { working: false, starts: null, finishes: null, breaks: [brk('11:00', '12:00')] }));
    expect(comparableChange(change)).toEqual({ date, working: false, starts: null, finishes: null, breaks: [] });
  });
  await team.dispose();
});

test('TC-F06-S02-015 AC-7: a date past or beyond a year, a note of 61, and hours or breaks that break the week\'s rules are each a 400 on their field that saves nothing, including breaks that fill the day', async ({ identity, operator, clock }) => {
  const { today, team, schedules, salon, owner, created, person } = await arranged(identity, operator, clock);
  const free = plusDays(today, 10);
  const breaksOf = (...breaks: BreakTime[]) => changeInput(free, { starts: '09:00', finishes: '18:00', breaks });
  const six = [brk('09:00', '09:15'), brk('10:00', '10:15'), brk('11:00', '11:15'), brk('12:00', '12:15'), brk('13:00', '13:15'), brk('14:00', '14:15')];
  const rows: [string, RegExp, ChangeInput][] = [
    ['yesterday', DATE, changeInput(plusDays(today, -1))],
    ['a year and a day ahead', DATE, changeInput(plusDays(plusYear(today), 1))],
    ['not a date', DATE, changeInput('next tuesday')],
    ['a date that does not exist', DATE, changeInput('2099-02-30')],
    ['no date', DATE, changeInput(free, { date: null })],
    ['a note of 61 characters', /^note$/, changeInput(free, { note: 'N'.repeat(SCHEDULE_LIMITS.noteLength + 1) })],
    ['a finish equal to the start', TIMES, changeInput(free, { starts: '12:00', finishes: '12:00' })],
    ['a finish before the start', TIMES, changeInput(free, { starts: '14:00', finishes: '09:00' })],
    ['a start off the step', TIMES, changeInput(free, { starts: '09:10' })],
    ['a finish off the step', TIMES, changeInput(free, { finishes: '13:59' })],
    ['different hours with no times', TIMES, changeInput(free, { starts: null, finishes: null })],
    ['times sent on a day not worked', TIMES, changeInput(free, { working: false })],
    ['a break ending at its start', BREAKS, breaksOf(brk('13:00', '13:00'))],
    ['a break off the step', BREAKS, breaksOf(brk('13:05', '14:00'))],
    ['a break outside the hours', BREAKS, breaksOf(brk('17:30', '18:15'))],
    ['overlapping breaks', BREAKS, breaksOf(brk('12:00', '13:00'), brk('12:45', '14:00'))],
    ['a sixth break', BREAKS, breaksOf(...six)],
    ['one break covering the day', BREAKS, breaksOf(brk('09:00', '18:00'))],
    ['touching breaks filling the day', BREAKS, breaksOf(brk('09:00', '13:00'), brk('13:00', '18:00'))],
  ];
  for (const [label, field, input] of rows) {
    await test.step(label, async () => {
      const before = scheduleSnapshot(created.id);
      await refusedOnField(await schedules.addRaw(salon, owner.tokens, person.id, input), field, label);
      expect(scheduleSnapshot(created.id), `${label}: nothing saved`).toBe(before);
    });
  }
  await team.dispose();
});

test('TC-F06-S02-016 AC-7: a second change on a date is 409 conflict whatever kind it is, changing nothing; of two sent at once exactly one is kept, in both orders across a sweep of gaps, and it is the winner\'s', async ({ identity, operator, clock }) => {
  test.setTimeout(180_000);
  const { today, team, schedules, salon, owner, created, person } = await arranged(identity, operator, clock);
  await test.step('one after the other', async () => {
    const date = plusDays(today, 2);
    await schedules.add(salon, owner.tokens, person.id, changeInput(date));
    for (const [label, input] of [['different hours again', changeInput(date, { starts: '11:00' })], ['not working', offChange(date, 'Second')]] as const) {
      const before = scheduleSnapshot(created.id);
      await refused(await schedules.addRaw(salon, owner.tokens, person.id, input), 409, 'conflict', label);
      expect(scheduleSnapshot(created.id), `${label}: nothing changed`).toBe(before);
    }
  });
  const wins = [0, 0];
  let overlapped = 0;
  let offset = 3;
  for (const gap of GAPS) {
    for (const first of [0, 1]) {
      await test.step(`gap ${gap} ms, side ${first} sent first`, async () => {
        offset += 1;
        const date = plusDays(today, offset);
        const sides = [changeInput(date, { starts: '09:00', finishes: '12:00', note: 'Side 0' }), offChange(date, 'Side 1')];
        const order = first === 0 ? [0, 1] : [1, 0];
        const fired = await fireTogether(order.map(side => () => schedules.addRaw(salon, owner.tokens, person.id, sides[side])), gap);
        if (fired.overlapped) overlapped += 1;
        const { answers } = fired;
        const statuses = answers.map(a => a.status());
        expect(statuses.filter(s => s === 201).length, `exactly one was kept (${statuses})`).toBe(1);
        const winnerAt = statuses.indexOf(201);
        await refused(answers[1 - winnerAt], 409, 'conflict', 'the other is told');
        const winner = order[winnerAt];
        wins[winner] += 1;
        const kept = (await schedules.read(salon, owner.tokens, person.id)).dateChanges.filter(c => c.date === date);
        expect(kept.length, 'one change on the date').toBe(1);
        expect(comparableChange(kept[0]), 'the winner\'s').toEqual(comparableChange(sides[winner]));
        expect(kept[0].note).toBe(sides[winner].note);
      });
    }
  }
  expect(wins.every(n => n > 0), `each side won at least once (${wins}), so neither order went unfought`).toBe(true);
  expect(overlapped, 'the requests really were in flight together').toBeGreaterThan(0);
  await team.dispose();
});

test('TC-F06-S02-017 AC-7: a person has at most 100 upcoming date changes: the 100th is saved, the next is 409 limit_reached saving nothing, another person is not limited by it, and removing one or one passing makes room', async ({ identity, operator, clock }) => {
  test.setTimeout(240_000);
  const { today, team, schedules, salon, owner, created, person } = await arranged(identity, operator, clock);
  const first: DateChange[] = [];
  for (let n = 0; n < SCHEDULE_LIMITS.upcomingChanges; n++) first.push((await schedules.add(salon, owner.tokens, person.id, offChange(plusDays(today, n)))).change);
  expect((await schedules.read(salon, owner.tokens, person.id)).dateChanges.length, 'the 100th was saved').toBe(100);
  const next = plusDays(today, 200);
  await test.step('the 101st', async () => {
    const before = scheduleSnapshot(created.id);
    await refused(await schedules.addRaw(salon, owner.tokens, person.id, offChange(next)), 409, 'limit_reached', 'the 101st');
    expect(scheduleSnapshot(created.id), 'nothing saved').toBe(before);
  });
  await test.step('another person is not limited by it', async () => {
    const other = await team.add(salon, owner.tokens);
    await schedules.add(salon, owner.tokens, other.id, offChange(next));
  });
  await test.step('removing one makes room, for one', async () => {
    await schedules.remove(salon, owner.tokens, person.id, first[50].id);
    await schedules.add(salon, owner.tokens, person.id, offChange(next));
    await refused(await schedules.addRaw(salon, owner.tokens, person.id, offChange(plusDays(today, 201))), 409, 'limit_reached', 'full again');
  });
  await test.step('one that has passed is not upcoming, so it makes room', async () => {
    await clock.advance(24 * 60 * 60 * 1000);
    const tokens = await signedInAgain(identity, owner);
    await schedules.add(salon, tokens, person.id, offChange(plusDays(today, 201)));
    await refused(await schedules.addRaw(salon, tokens, person.id, offChange(plusDays(today, 202))), 409, 'limit_reached', 'full again');
  });
  await team.dispose();
});

test('TC-F06-S02-046 AC-7: the limit of 100 upcoming date changes holds under simultaneous requests: a burst of different dates stops at exactly 100, every other request is told 409 limit_reached, and the same holds after making room', async ({ identity, operator, clock }) => {
  test.setTimeout(240_000);
  const { today, team, schedules, salon, owner, person } = await arranged(identity, operator, clock);
  const kept: DateChange[] = [];
  for (let n = 0; n < 95; n++) kept.push((await schedules.add(salon, owner.tokens, person.id, offChange(plusDays(today, n)))).change);
  /** Sends one request per free date at once: exactly `room` are saved, and the list then holds exactly 100 with no date twice. */
  const burst = async (label: string, from: number, requests: number, room: number) => {
    const before = dates(await schedules.read(salon, owner.tokens, person.id));
    expect(before.length, `${label}: the room is what it should be`).toBe(SCHEDULE_LIMITS.upcomingChanges - room);
    const sent = Array.from({ length: requests }, (_, n) => plusDays(today, from + n));
    const { answers, overlapped } = await fireTogether(sent.map(date => () => schedules.addRaw(salon, owner.tokens, person.id, offChange(date))), 0);
    expect(overlapped, `${label}: every request was in flight before any was answered`).toBe(true);
    const statuses = answers.map(a => a.status());
    expect(statuses.filter(s => s === 201).length, `${label}: exactly the room left was taken (${statuses})`).toBe(room);
    const refusals = answers.filter(a => a.status() !== 201);
    for (const r of refusals) await refused(r, 409, 'limit_reached', `${label}: the others are told why`);
    const after = dates(await schedules.read(salon, owner.tokens, person.id));
    expect(after.length, `${label}: exactly 100 stored`).toBe(SCHEDULE_LIMITS.upcomingChanges);
    expect(new Set(after).size, `${label}: no date twice`).toBe(SCHEDULE_LIMITS.upcomingChanges);
    expect(after.filter(d => !before.includes(d)).sort(), `${label}: the ones stored are the ones answered 201`).toEqual(sent.filter((_, n) => statuses[n] === 201).sort());
  };
  await burst('five places, twelve requests', 150, 12, 5);
  await test.step('a place made by removing three, then six requests', async () => {
    for (const change of kept.slice(10, 13)) await schedules.remove(salon, owner.tokens, person.id, change.id);
    await burst('three places, six requests', 170, 6, 3);
  });
  await team.dispose();
});

test('TC-F06-S02-018 AC-7: Remove takes a change away and frees its date; removing one that is gone, unknown, another person\'s or one that has passed answers 404 and removes nothing; a passed change leaves the list', async ({ identity, operator, clock }) => {
  const { today, team, schedules, salon, owner, created, person } = await arranged(identity, operator, clock);
  const date = plusDays(today, 5);
  const { change } = await schedules.add(salon, owner.tokens, person.id, changeInput(date));
  await test.step('removed: gone, its date free', async () => {
    await schedules.remove(salon, owner.tokens, person.id, change.id);
    expect(dates(await schedules.read(salon, owner.tokens, person.id)), 'it left the list').toEqual([]);
    await refused(await schedules.removeRaw(salon, owner.tokens, person.id, change.id), 404, 'not_found', 'removed twice');
    await schedules.add(salon, owner.tokens, person.id, changeInput(date, { starts: '11:00' }));
  });
  await test.step('another person\'s change, through this person', async () => {
    const other = await team.add(salon, owner.tokens);
    const theirs = (await schedules.add(salon, owner.tokens, other.id, offChange(date))).change;
    const before = scheduleSnapshot(created.id);
    await refused(await schedules.removeRaw(salon, owner.tokens, person.id, theirs.id), 404, 'not_found', 'through the wrong person');
    expect(scheduleSnapshot(created.id), 'nothing removed').toBe(before);
    expect(dates(await schedules.read(salon, owner.tokens, other.id)), 'theirs stands').toEqual([date]);
  });
  const passing = (await schedules.add(salon, owner.tokens, person.id, offChange(today, 'Passing'))).change;
  expect(dates(await schedules.read(salon, owner.tokens, person.id)), 'today counts as listed').toEqual([today, date]);
  await test.step('a change that has passed', async () => {
    await clock.advance(24 * 60 * 60 * 1000);
    const tokens = await signedInAgain(identity, owner);
    const before = scheduleSnapshot(created.id);
    await refused(await schedules.removeRaw(salon, tokens, person.id, passing.id), 404, 'not_found', 'one that has passed');
    expect(scheduleSnapshot(created.id), 'nothing removed').toBe(before);
    expect(dates(await schedules.read(salon, tokens, person.id)), 'the list starts from the salon\'s today').toEqual([date]);
  });
  await team.dispose();
});

// ---------------------------------------------------------------- AC-8 a change and the week

test('TC-F06-S02-019 AC-8: a change holds its own hours and breaks and nothing of the week, whether it makes a working day not working, a day off working or replaces both times and breaks; the week is untouched by adding or removing one, and a weekly save leaves changes alone', async ({ identity, operator, clock }) => {
  const { today, team, schedules, salon, owner, person } = await arranged(identity, operator, clock);
  const weekly = weekOf('09:00', '18:00', ['sunday'], {
    monday: { breaks: [brk('13:00', '14:00')] }, tuesday: { breaks: [brk('12:00', '12:30'), brk('15:00', '15:30')] },
  });
  await schedules.save(salon, owner.tokens, person.id, weekly);
  const on = (day: number) => { // A date a set number of days from a Monday at least a week ahead, whatever today is.
    let monday = plusDays(today, 7);
    while (new Date(`${monday}T00:00:00Z`).getUTCDay() !== 1) monday = plusDays(monday, 1);
    return plusDays(monday, day);
  };
  const inputs = {
    workingToOff: offChange(on(0)),
    bothReplaced: changeInput(on(1), { starts: '11:00', finishes: '13:00', breaks: [brk('11:45', '12:00')] }),
    breaksGone: changeInput(on(2), { starts: '09:00', finishes: '18:00', breaks: [] }),
    offToWorking: changeInput(on(6), { starts: '10:00', finishes: '12:00' }),
  };
  const added: DateChange[] = [];
  const version = (await schedules.read(salon, owner.tokens, person.id)).version;
  await test.step('each kind holds only what was sent', async () => {
    for (const input of Object.values(inputs)) {
      const { change } = await schedules.add(salon, owner.tokens, person.id, input);
      added.push(change);
      expect(comparableChange(change), String(input.date)).toEqual(comparableChange(input));
    }
  });
  await test.step('the week is untouched by adding them', async () => {
    const read = await schedules.read(salon, owner.tokens, person.id);
    expectWeek(read.weekly, weekly, 'the week');
    expect(read.version, 'adding a change does not raise the schedule\'s version').toBe(version);
    expect(read.dateChanges.map(comparableChange), 'read back').toEqual(Object.values(inputs).map(comparableChange));
    expectWeek((await team.get(salon, owner.tokens, person.id)).weekly, weekly, 'the member\'s week');
  });
  await test.step('a weekly save leaves the changes alone', async () => {
    const before = await schedules.read(salon, owner.tokens, person.id);
    const other = weekOf('08:00', '12:00', ['monday', 'sunday']);
    await schedules.save(salon, owner.tokens, person.id, other, before.version);
    const read = await schedules.read(salon, owner.tokens, person.id);
    expectWeek(read.weekly, other, 'the new week');
    expect(read.dateChanges, 'the changes, whole').toEqual(before.dateChanges);
  });
  await test.step('removing one leaves the week and the others', async () => {
    const before = await schedules.read(salon, owner.tokens, person.id);
    await schedules.remove(salon, owner.tokens, person.id, added[0].id);
    const read = await schedules.read(salon, owner.tokens, person.id);
    expectWeek(read.weekly, before.weekly, 'the week');
    expect(read.version, 'nor does removing one').toBe(before.version);
    expect(read.dateChanges, 'the others').toEqual(before.dateChanges.filter(c => c.id !== added[0].id));
  });
  await team.dispose();
});

// ---------------------------------------------------------------- AC-10 whose date "today" is

test('TC-F06-S02-020 AC-10: "today" and the year ahead are the salon\'s date, on either side of UTC: 23:30 west of UTC with UTC already on the next day, and 00:30 in Colombo with UTC still on the day before; and a change passes when the salon\'s day ends', async ({ identity, operator, clock }) => {
  test.setTimeout(180_000);
  const base = plusDays(afterNewestPlan().slice(0, 10), 2);
  const sides = [
    { label: 'Colombo, UTC+05:30 all year', timeZone: COLOMBO, at: `${plusDays(base, -1)}T19:00:00Z`, localTime: '00:30', utcIs: plusDays(base, -1) },
    { label: 'Bogota, UTC-05:00 all year', timeZone: 'America/Bogota', at: `${plusDays(base, 1)}T04:30:00Z`, localTime: '23:30', utcIs: plusDays(base, 1) },
  ];
  const team = new Team(identity);
  const schedules = new Schedules(team);
  for (const side of sides) {
    await test.step(side.label, async () => {
      await clock.set(side.at);
      const now = await identity.now();
      expect(localDate(now, side.timeZone), `${side.label}: the salon's date is the day under test`).toBe(base);
      expect(new Intl.DateTimeFormat('en-GB', { timeZone: side.timeZone, hour: '2-digit', minute: '2-digit' }).format(new Date(now)), 'at the time the case is about').toBe(side.localTime);
      expect(now.slice(0, 10), 'UTC is on another date, or this proves nothing').toBe(side.utcIs);
      const { salon, owner, created } = await teamSalon(operator, identity, 20, { timeZone: side.timeZone });
      const person = await team.add(salon, owner.tokens);
      const today = await schedules.add(salon, owner.tokens, person.id, changeInput(base));
      expect(today.change.date, 'the salon\'s today is accepted').toBe(base);
      await schedules.add(salon, owner.tokens, person.id, offChange(plusYear(base)));
      const before = scheduleSnapshot(created.id);
      await refusedOnField(await schedules.addRaw(salon, owner.tokens, person.id, changeInput(plusDays(base, -1))), DATE, 'the salon\'s yesterday');
      await refusedOnField(await schedules.addRaw(salon, owner.tokens, person.id, changeInput(plusDays(plusYear(base), 1))), DATE, 'a year and a day from the salon\'s today');
      expect(scheduleSnapshot(created.id), 'nothing saved').toBe(before);
      const read = await schedules.read(salon, owner.tokens, person.id);
      expect(read.today, 'the schedule says the salon\'s today').toBe(base);
      expect(dates(read)[0], 'today\'s is listed').toBe(base);
      if (side.localTime === '23:30') {
        await clock.advance(31 * 60 * 1000);
        expect(localDate(await identity.now(), side.timeZone), '00:01 the next day at the salon').toBe(plusDays(base, 1));
        const tokens = await signedInAgain(identity, owner);
        await refused(await schedules.removeRaw(salon, tokens, person.id, today.change.id), 404, 'not_found', 'its day has ended at the salon');
        await refusedOnField(await schedules.addRaw(salon, tokens, person.id, changeInput(base)), DATE, 'that date is now past');
        expect(dates(await schedules.read(salon, tokens, person.id))[0], 'it left the list').toBe(plusYear(base));
      }
    });
  }
  await team.dispose();
});
