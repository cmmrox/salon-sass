/**
 * F06-S02 a person's week from outside: a new person's copy of the salon's opening hours, which is
 * never linked to them afterwards; saving a whole week with its boundaries; breaks at their edges and
 * the rule that a working day has time left; hours beyond the salon's; versions; and the two things
 * that can happen at once, two saves of one version and a save beside a profile edit.
 *
 * Expected results are the F06-S02 spec's (acceptance criteria, "API and data changes", "Risks and test
 * focus"), decision 102 and the lead's rulings for the build (`qa/shared/team-schedule.ts`). What the
 * screen says about each is in qa/web/F06/S02-schedule-screens.spec.ts. Names the build gives are in
 * `PENDING_F06_S02`.
 *
 * What this cannot prove: F07 will trust `TeamSchedule`'s windows (AC-9), a Java interface with no route
 * until F07. From outside these cases prove only what it reads: that a stored day holds what was sent,
 * and that no stored working day can leave zero time. The windows themselves are the developer's
 * integration tests' until F07 (deferred case 028). Sent in the same tick a race can hide one order
 * (F06-S01's lesson), so a race sweeps `GAPS`, in both orders, and asserts each side won or that the
 * requests really overlapped.
 */
import { refused } from '../../shared/booking-rules';
import { Hours, week } from '../../shared/salon-hours';
import { test, expect } from '../../shared/operator';
import { GAPS, Team, fireTogether, memberInput, teamSalon, updateOf } from '../../shared/team';
import {
  Schedules, brk, copyOf, expectWeek, memberBeforeThisStage, noWorkingDays, offDay, refusedOnDay, refusedOnField, scheduleSnapshot, weekOf, workingDay,
} from '../../shared/team-schedule';
import type { DayName } from '../../shared/salon-hours';
import type { BreakTime, WorkDay } from '../../shared/team';

test.describe.configure({ timeout: 120_000 });

const worked = (status: number) => status >= 200 && status < 300;

/** A week differing from any other round's, so that no save of a race is a save that changes nothing. */
const roundWeek = (round: number, side: number) => {
  const minutes = String((side * 15) % 60).padStart(2, '0');
  return weekOf(`${String(7 + (round % 3)).padStart(2, '0')}:${minutes}`, `${String(12 + round).padStart(2, '0')}:${minutes}`, side === 0 ? ['sunday'] : ['saturday']);
};

// ---------------------------------------------------------------- AC-1 a new person's week

test('TC-F06-S02-001 AC-1: a person added after the salon saved its hours starts on a copy of them, each open weekday working with those times and no break and each closed weekday not working, in the list, the member and the schedule alike', async ({ identity, operator }) => {
  const team = new Team(identity);
  const hours = new Hours(identity);
  const schedules = new Schedules(team);
  const { salon, owner } = await teamSalon(operator, identity);
  const opening = week('08:30', '18:15', ['wednesday', 'sunday'], { saturday: ['10:00', '16:45'] });
  await hours.save(salon, owner.tokens, opening);
  const person = await team.add(salon, owner.tokens);
  const expected = copyOf(opening);
  expect(expected.filter(d => d.working).length, 'there are working days to compare').toBe(5);
  expectWeek((await team.get(salon, owner.tokens, person.id)).weekly, expected, 'the member');
  expectWeek((await team.list(salon, owner.tokens)).find(m => m.id === person.id)!.weekly, expected, 'the list');
  const schedule = await schedules.read(salon, owner.tokens, person.id);
  expectWeek(schedule.weekly, expected, 'the schedule');
  expect(schedule.dateChanges, 'no date changes').toEqual([]);
  await team.dispose();
});

test('TC-F06-S02-002 AC-1: a salon that has never saved opening hours gives a new person a week with no working days, though its form suggests a week', async ({ identity, operator }) => {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const { salon, owner } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, noWorkingDays(), 'no working days');
  expectWeek((await team.get(salon, owner.tokens, person.id)).weekly, noWorkingDays(), 'the member says the same');
  await team.dispose();
});

test('TC-F06-S02-003 AC-1: a week is copied once: changing the salon\'s hours afterwards changes nobody\'s week or version, and only a person added after that starts on the new hours', async ({ identity, operator }) => {
  const team = new Team(identity);
  const hours = new Hours(identity);
  const schedules = new Schedules(team);
  const { salon, owner } = await teamSalon(operator, identity);
  const before = await team.add(salon, owner.tokens, memberInput({ name: 'Before Any Hours' }));
  const first = week('09:00', '18:00', ['sunday']);
  await hours.save(salon, owner.tokens, first);
  const onFirst = await team.add(salon, owner.tokens, memberInput({ name: 'On The First Hours' }));
  const edited = await team.add(salon, owner.tokens, memberInput({ name: 'Own Hours' }));
  const own = weekOf('07:00', '12:00', ['friday'], { monday: { breaks: [brk('10:00', '10:30')] } });
  await schedules.save(salon, owner.tokens, edited.id, own);
  const versions = await Promise.all([before, onFirst, edited].map(async m => (await schedules.read(salon, owner.tokens, m.id)).version));
  const second = week('11:00', '20:00', ['monday'], { sunday: ['12:00', '16:00'] });
  await hours.save(salon, owner.tokens, second);
  expectWeek((await schedules.read(salon, owner.tokens, before.id)).weekly, noWorkingDays(), 'added before any hours: still none');
  expectWeek((await schedules.read(salon, owner.tokens, onFirst.id)).weekly, copyOf(first), 'added on the first hours: still those');
  expectWeek((await schedules.read(salon, owner.tokens, edited.id)).weekly, own, 'changed by hand: still that');
  const after = await Promise.all([before, onFirst, edited].map(async m => (await schedules.read(salon, owner.tokens, m.id)).version));
  expect(after, 'no schedule version moved').toEqual(versions);
  const newcomer = await team.add(salon, owner.tokens, memberInput({ name: 'On The Second Hours' }));
  expectWeek((await schedules.read(salon, owner.tokens, newcomer.id)).weekly, copyOf(second), 'added now: the new hours');
  await team.dispose();
});

test('TC-F06-S02-004 AC-1: a person added before this stage reads as having no working days, and can be given a week', async ({ identity, operator }) => {
  const team = new Team(identity);
  const hours = new Hours(identity);
  const schedules = new Schedules(team);
  const { salon, owner, created } = await teamSalon(operator, identity);
  await hours.save(salon, owner.tokens, week());
  const donor = await team.add(salon, owner.tokens);
  const earlierId = memberBeforeThisStage(created.id, donor.id, 'Added Before This Stage');
  expectWeek((await team.get(salon, owner.tokens, earlierId)).weekly, noWorkingDays(), 'the member, though the salon has hours now');
  const schedule = await schedules.read(salon, owner.tokens, earlierId);
  expectWeek(schedule.weekly, noWorkingDays(), 'the schedule');
  const given = weekOf('10:00', '15:00');
  await schedules.save(salon, owner.tokens, earlierId, given, schedule.version);
  expectWeek((await schedules.read(salon, owner.tokens, earlierId)).weekly, given, 'a week can be saved for them');
  await team.dispose();
});

// ---------------------------------------------------------------- AC-2 saving a week

test('TC-F06-S02-005 AC-2, AC-11: a Manager saves a whole week at once: every weekday is replaced, working or not, on 15-minute steps up to 00:00 and 23:45; the answer gives a new version, the week and no affected bookings, and it reads back the same', async ({ identity, operator }) => {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const { salon, owner } = await teamSalon(operator, identity);
  const manager = await identity.staff(salon, 'MANAGER');
  const person = await team.add(salon, owner.tokens);
  const before = await schedules.read(salon, manager.tokens, person.id);
  const weekly: WorkDay[] = [
    workingDay('monday', '00:00', '23:45'), workingDay('tuesday', '00:00', '00:15'), workingDay('wednesday', '23:30', '23:45'),
    workingDay('thursday', '09:15', '17:45'), offDay('friday'), workingDay('saturday', '10:30', '14:15'), offDay('sunday'),
  ];
  const saved = await schedules.save(salon, manager.tokens, person.id, weekly, before.version);
  expect(saved.version, 'a new version').toBeGreaterThan(before.version);
  expectWeek(saved.weekly, weekly, 'the answer');
  expect(saved.affected, 'no booking exists before F07, and the answer says so').toEqual([]);
  const read = await schedules.read(salon, owner.tokens, person.id);
  expectWeek(read.weekly, weekly, 'read back');
  expect(read.version, 'the same version').toBe(saved.version);
  await test.step('a working day can become not working, and back', async () => {
    const changed = weekly.map(d => (d.day === 'monday' ? offDay('monday') : d.day === 'friday' ? workingDay('friday', '12:00', '13:00') : d));
    const again = await schedules.save(salon, owner.tokens, person.id, changed, read.version);
    expectWeek(again.weekly, changed, 'the second save');
    expect(again.weekly.find(d => d.day === 'monday'), 'not working has null times and no breaks').toEqual(offDay('monday'));
  });
  await team.dispose();
});

test('TC-F06-S02-006 AC-2: a finish at or before the start, a time off the 15-minute step or a working day with no times is a 400 on that weekday, and a week that is not the seven weekdays once or has no version is a 400; none saves anything, also of the valid days sent with it', async ({ identity, operator }) => {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  const { version } = await schedules.read(salon, owner.tokens, person.id);
  /** Monday changes validly in every row, so that a partial save would show. */
  const withTuesday = (tuesday: Partial<WorkDay>) => weekOf('10:00', '16:00', ['sunday'], { tuesday });
  const onDay: [string, Partial<WorkDay>][] = [
    ['a finish equal to the start', { starts: '12:00', finishes: '12:00' }],
    ['a finish before the start', { starts: '14:00', finishes: '09:30' }],
    ['a start off the step', { starts: '09:10', finishes: '18:00' }],
    ['a finish off the step', { starts: '09:00', finishes: '17:59' }],
    ['working with no start', { starts: null, finishes: '18:00' }],
    ['working with no finish', { starts: '09:00', finishes: null }],
    ['times sent on a day not worked', { working: false, starts: '09:00', finishes: '18:00' }],
  ];
  for (const [label, tuesday] of onDay) {
    await test.step(label, async () => {
      const before = scheduleSnapshot(created.id);
      await refusedOnDay(await schedules.saveRaw(salon, owner.tokens, person.id, version, withTuesday(tuesday)), 'tuesday', label);
      expect(scheduleSnapshot(created.id), `${label}: nothing saved`).toBe(before);
    });
  }
  const shapes: [string, RegExp, unknown, unknown][] = [
    ['a weekday missing', /^weekly/, version, weekOf().slice(0, 6)],
    ['a weekday twice', /^weekly/, version, [...weekOf().slice(0, 6), workingDay('monday')]],
    ['eight days', /^weekly/, version, [...weekOf(), workingDay('monday')]],
    ['a weekday that is not one', /^weekly/, version, [...weekOf().slice(0, 6), { ...offDay('sunday'), day: 'someday' }]],
    ['a time that is not one', /^weekly/, version, weekOf('09:00', '18:00', ['sunday'], { tuesday: { starts: '24:00', finishes: '24:15' } })],
    ['no week', /^weekly/, version, null],
    ['no version', /^version/, null, weekOf()],
  ];
  for (const [label, field, sentVersion, weekly] of shapes) {
    await test.step(label, async () => {
      const before = scheduleSnapshot(created.id);
      await refusedOnField(await schedules.saveRaw(salon, owner.tokens, person.id, sentVersion, weekly), field, label);
      expect(scheduleSnapshot(created.id), `${label}: nothing saved`).toBe(before);
    });
  }
  await team.dispose();
});

// ---------------------------------------------------------------- AC-3 breaks

test('TC-F06-S02-007 AC-3: a working day holds up to 5 breaks, stored exactly at their edges: touching the start, touching the finish, touching each other, sent out of order and leaving one last quarter of an hour', async ({ identity, operator }) => {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const { salon, owner } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  const edges: Partial<Record<DayName, BreakTime[]>> = {
    monday: [brk('09:00', '09:30')],
    tuesday: [brk('17:30', '18:00')],
    wednesday: [brk('12:00', '12:30'), brk('12:30', '13:00')],
    thursday: [brk('09:00', '09:15'), brk('10:00', '10:15'), brk('11:00', '11:15'), brk('12:00', '12:15'), brk('17:45', '18:00')],
    friday: [brk('15:00', '15:30'), brk('10:00', '10:30')],
    saturday: [brk('09:00', '17:45')],
    sunday: [brk('09:00', '12:00'), brk('12:00', '17:45')],
  };
  const weekly = weekOf('09:00', '18:00', [], Object.fromEntries(Object.entries(edges).map(([day, breaks]) => [day, { breaks }])));
  expect(weekly.every(d => d.breaks.length > 0), 'every row is a day with breaks').toBe(true);
  const saved = await schedules.save(salon, owner.tokens, person.id, weekly);
  expectWeek(saved.weekly, weekly, 'the answer');
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, weekly, 'read back');
  await team.dispose();
});

test('TC-F06-S02-008 AC-3: a weekday set to not working keeps no breaks when saved, and working again later has none', async ({ identity, operator }) => {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const { salon, owner } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  const first = await schedules.save(salon, owner.tokens, person.id, weekOf('09:00', '18:00', ['sunday'], { monday: { breaks: [brk('13:00', '14:00')] } }));
  const sentWithBreak = weekOf('09:00', '18:00', ['sunday'], { monday: { working: false, starts: null, finishes: null, breaks: [brk('13:00', '14:00')] } });
  const off = await schedules.save(salon, owner.tokens, person.id, sentWithBreak, first.version);
  expect(off.weekly.find(d => d.day === 'monday'), 'none kept').toEqual(offDay('monday'));
  const back = await schedules.save(salon, owner.tokens, person.id, weekOf(), off.version);
  expect((await schedules.read(salon, owner.tokens, person.id)).weekly.find(d => d.day === 'monday'), 'no break came back').toEqual(workingDay('monday'));
  expect(back.version).toBeGreaterThan(off.version);
  await team.dispose();
});

test('TC-F06-S02-009 AC-3: a break ending at or before its start, off the step, outside the hours or overlapping another, a sixth break, and breaks that fill the day whether one or touching are each a 400 on that weekday that saves nothing', async ({ identity, operator }) => {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  const { version } = await schedules.read(salon, owner.tokens, person.id);
  const rows: [string, BreakTime[]][] = [
    ['ending at its start', [brk('13:00', '13:00')]],
    ['ending before its start', [brk('14:00', '13:00')]],
    ['a start off the step', [brk('13:05', '14:00')]],
    ['an end off the step', [brk('13:00', '14:05')]],
    ['no times', [{ starts: null, finishes: null }]],
    ['starting before the hours', [brk('08:45', '09:30')]],
    ['ending after the hours', [brk('17:30', '18:15')]],
    ['wholly before the hours', [brk('07:00', '08:00')]],
    ['overlapping', [brk('12:00', '13:00'), brk('12:45', '14:00')]],
    ['overlapping, sent out of order', [brk('12:45', '14:00'), brk('12:00', '13:00')]],
    ['one inside another', [brk('12:00', '15:00'), brk('13:00', '13:30')]],
    ['the same twice', [brk('12:00', '13:00'), brk('12:00', '13:00')]],
    ['a sixth', [brk('09:00', '09:15'), brk('10:00', '10:15'), brk('11:00', '11:15'), brk('12:00', '12:15'), brk('13:00', '13:15'), brk('14:00', '14:15')]],
    ['one break covering the day', [brk('09:00', '18:00')]],
    ['two touching breaks filling the day', [brk('09:00', '13:00'), brk('13:00', '18:00')]],
    ['touching breaks filling the day, out of order', [brk('15:00', '18:00'), brk('09:00', '12:00'), brk('12:00', '15:00')]],
  ];
  for (const [label, breaks] of rows) {
    await test.step(label, async () => {
      const before = scheduleSnapshot(created.id);
      const weekly = weekOf('09:00', '18:00', ['sunday'], { wednesday: { breaks } });
      await refusedOnDay(await schedules.saveRaw(salon, owner.tokens, person.id, version, weekly), 'wednesday', label);
      expect(scheduleSnapshot(created.id), `${label}: nothing saved`).toBe(before);
    });
  }
  await team.dispose();
});

// ---------------------------------------------------------------- AC-5 beyond the salon's hours

test('TC-F06-S02-010 AC-5: hours outside the salon\'s are not refused: earlier than it opens, later than it closes and on a day it is closed, stored as entered', async ({ identity, operator }) => {
  const team = new Team(identity);
  const hours = new Hours(identity);
  const schedules = new Schedules(team);
  const { salon, owner } = await teamSalon(operator, identity);
  await hours.save(salon, owner.tokens, week('09:00', '18:00', ['sunday']));
  const person = await team.add(salon, owner.tokens);
  const weekly = weekOf('09:00', '18:00', [], {
    monday: { starts: '06:00', finishes: '12:00' }, tuesday: { starts: '12:00', finishes: '22:15' }, sunday: { starts: '10:00', finishes: '14:00' },
  });
  expectWeek((await schedules.save(salon, owner.tokens, person.id, weekly)).weekly, weekly, 'as entered');
  expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, weekly, 'read back');
  await team.dispose();
});

// ---------------------------------------------------------------- AC-6 versions and saves at once

test('TC-F06-S02-011 AC-6: a stale version is 409 conflict and a save that changes nothing is 409 invalid_state, both changing nothing and neither moving the version', async ({ identity, operator }) => {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  const first = await schedules.read(salon, owner.tokens, person.id);
  const stored = weekOf('09:00', '18:00', ['sunday'], { monday: { breaks: [brk('13:00', '14:00')] } });
  const second = await schedules.save(salon, owner.tokens, person.id, stored, first.version);
  await test.step('stale', async () => {
    const before = scheduleSnapshot(created.id);
    await refused(await schedules.saveRaw(salon, owner.tokens, person.id, first.version, weekOf('11:00', '15:00')), 409, 'conflict', 'a stale version');
    expect(scheduleSnapshot(created.id), 'nothing saved').toBe(before);
  });
  await test.step('a save that changes nothing', async () => {
    const before = scheduleSnapshot(created.id);
    await refused(await schedules.saveRaw(salon, owner.tokens, person.id, second.version, stored), 409, 'invalid_state', 'an unchanged week');
    expect(scheduleSnapshot(created.id), 'nothing saved').toBe(before);
  });
  const read = await schedules.read(salon, owner.tokens, person.id);
  expect(read.version, 'the version stands').toBe(second.version);
  expectWeek(read.weekly, stored, 'the week stands');
  await team.dispose();
});

test('TC-F06-S02-012 AC-6: of two saves of one version sent at once exactly one succeeds, in both orders across a sweep of gaps; the other is 409 conflict, and the stored week is wholly the winner\'s', async ({ identity, operator }) => {
  test.setTimeout(180_000);
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const { salon, owner } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  const wins = [0, 0];
  let overlapped = 0;
  let round = 0;
  for (const gap of GAPS) {
    for (const first of [0, 1]) {
      await test.step(`gap ${gap} ms, side ${first} sent first`, async () => {
        round += 1;
        const { version } = await schedules.read(salon, owner.tokens, person.id);
        const weeks = [roundWeek(round % 8, 0), roundWeek(round % 8, 1)];
        const order = first === 0 ? [0, 1] : [1, 0];
        const fired = await fireTogether(order.map(side => () => schedules.saveRaw(salon, owner.tokens, person.id, version, weeks[side])), gap);
        if (fired.overlapped) overlapped += 1;
        const { answers } = fired;
        const statuses = answers.map(a => a.status());
        expect(statuses.filter(worked).length, `exactly one succeeded (${statuses})`).toBe(1);
        const winnerAt = statuses.findIndex(worked);
        await refused(answers[1 - winnerAt], 409, 'conflict', 'the other is told');
        const winner = order[winnerAt];
        wins[winner] += 1;
        const read = await schedules.read(salon, owner.tokens, person.id);
        expectWeek(read.weekly, weeks[winner], 'the stored week is wholly the winner\'s');
        expect(read.version).toBeGreaterThan(version);
      });
    }
  }
  expect(wins.every(n => n > 0), `each side won at least once (${wins}), so neither order went unfought`).toBe(true);
  expect(overlapped, 'the requests really were in flight together').toBeGreaterThan(0);
  await team.dispose();
});

test('TC-F06-S02-013 AC-6: a schedule has its own version: an hours save sent beside a profile edit never conflicts in either order across a sweep of gaps, neither loses the other\'s change, and neither moves the other\'s version', async ({ identity, operator }) => {
  test.setTimeout(180_000);
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const { salon, owner } = await teamSalon(operator, identity);
  const person = await team.add(salon, owner.tokens);
  await test.step('one at a time, neither moves the other\'s version', async () => {
    const member = await team.get(salon, owner.tokens, person.id);
    const schedule = await schedules.read(salon, owner.tokens, person.id);
    await schedules.save(salon, owner.tokens, person.id, weekOf('08:00', '16:00'), schedule.version);
    expect((await team.get(salon, owner.tokens, person.id)).version, 'an hours save leaves the profile\'s version').toBe(member.version);
    const moved = await schedules.read(salon, owner.tokens, person.id);
    await team.save(salon, owner.tokens, member, { title: 'Senior' });
    expect((await schedules.read(salon, owner.tokens, person.id)).version, 'a profile edit leaves the schedule\'s version').toBe(moved.version);
  });
  let overlapped = 0;
  let round = 0;
  for (const gap of GAPS) {
    for (const hoursFirst of [true, false]) {
      await test.step(`gap ${gap} ms, ${hoursFirst ? 'hours' : 'profile'} sent first`, async () => {
        round += 1;
        const member = await team.get(salon, owner.tokens, person.id);
        const { version } = await schedules.read(salon, owner.tokens, person.id);
        const weekly = roundWeek(round % 8, round % 2);
        const title = `Title ${round}`;
        const saves = [
          () => schedules.saveRaw(salon, owner.tokens, person.id, version, weekly),
          () => team.saveRaw(salon, owner.tokens, person.id, updateOf(member, { title })),
        ];
        const fired = await fireTogether(hoursFirst ? saves : [...saves].reverse(), gap);
        if (fired.overlapped) overlapped += 1;
        const statuses = fired.answers.map(a => a.status());
        expect(statuses, 'both went through').toEqual([200, 200]);
        expect((await team.get(salon, owner.tokens, person.id)).title, 'the profile edit stands').toBe(title);
        expectWeek((await schedules.read(salon, owner.tokens, person.id)).weekly, weekly, 'the hours stand');
      });
    }
  }
  expect(overlapped, 'the requests really were in flight together').toBeGreaterThan(0);
  await team.dispose();
});
