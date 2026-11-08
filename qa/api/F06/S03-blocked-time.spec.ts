/**
 * F06-S03 blocked time from outside: part of one date on 15-minute steps with a label, from today to a year
 * ahead; touching and overlapping one at a time and at once; the limit of 200 one at a time and under a burst;
 * a block outside the person's hours or on their leave; what the schedule lists; removal; and the preview.
 *
 * Expected results are the F06-S03 spec's (AC-4, AC-5, AC-6, "API and data changes", "Risks and test focus"). What
 * the sheet says about each is in qa/web/F06/S03-leave-and-block-screens.spec.ts, and who may add or remove in
 * S03-access.spec.ts. What a block does to a person's working windows (AC-7) is the deferred case 030's.
 */
import { refused } from '../../shared/booking-rules';
import type { Identity, SignedStaff } from '../../shared/identity';
import { test, expect, afterNewestPlan } from '../../shared/operator';
import { localDate, plusDays, plusYear } from '../../shared/salon-hours';
import { GAPS, Team, fireTogether, teamSalon } from '../../shared/team';
import {
  ABSENCE_LIMITS, Absences, absenceSalon, absenceSnapshot, blockAffectedPath, blockCopies, blockInput, comparableBlock, leaveInput, nextDays, refusedOn,
} from '../../shared/team-leave';
import { Schedules, offDay, weekOf } from '../../shared/team-schedule';

test.describe.configure({ timeout: 120_000 });

const COLOMBO = 'Asia/Colombo';
const DAY = 24 * 60 * 60 * 1000;
const chars = (n: number) => `A${'a'.repeat(n - 1)}`;
const signedInAgain = async (identity: Identity, owner: SignedStaff) => (await identity.login(owner.salon, owner.account)).tokens;

// ---------------------------------------------------------------- AC-4 adding

test('TC-F06-S03-011 AC-4, AC-8: a block is saved from today to a year ahead, on 15-minute steps up to 00:00 and 23:45, with a label of 2 to 40 characters, trimmed; each answers with nothing affected and none to resolve', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, person } = await absenceSalon(identity, operator, clock);
  const rows = [
    { input: blockInput(today, { starts: '00:00', finishes: '00:15', label: 'Ab' }), label: 'Ab' },
    { input: blockInput(plusDays(today, 1), { starts: '10:15', finishes: '23:45', label: chars(ABSENCE_LIMITS.label[1]) }), label: chars(ABSENCE_LIMITS.label[1]) },
    { input: blockInput(plusYear(today), { label: '  Training  ' }), label: 'Training' },
  ];
  for (const { input, label } of rows) {
    const answer = await absences.addBlock(salon, owner.tokens, person.id, input);
    expect(comparableBlock(answer.block), `${String(input.date)}: holds what was sent`).toEqual(comparableBlock({ ...input, label }));
    expect(answer.affected, 'no booking exists before F07, and the answer says so').toEqual([]);
    expect(answer.block.bookingsToResolve, 'none to resolve').toBe(0);
  }
  await team.dispose();
});

test('TC-F06-S03-012 AC-4: a date past or beyond a year, an end at or before the start, a time off the step and a label too short, too long or only spaces are each a 400 on their field that saves nothing', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, created, person } = await absenceSalon(identity, operator, clock);
  const free = plusDays(today, 5);
  const DATE = /^date$/;
  const TIMES = /^(starts|finishes)$/;
  const LABEL = /^label$/;
  const rows: [string, RegExp, unknown][] = [
    ['yesterday', DATE, blockInput(plusDays(today, -1))],
    ['a year and a day ahead', DATE, blockInput(plusDays(plusYear(today), 1))],
    ['not a date', DATE, blockInput('next tuesday')],
    ['no date', DATE, blockInput(free, { date: null })],
    ['an end equal to the start', TIMES, blockInput(free, { starts: '11:00', finishes: '11:00' })],
    ['an end before the start', TIMES, blockInput(free, { starts: '11:00', finishes: '10:00' })],
    ['a start off the step', TIMES, blockInput(free, { starts: '10:05' })],
    ['an end off the step', TIMES, blockInput(free, { finishes: '11:10' })],
    ['no start', TIMES, blockInput(free, { starts: null })],
    ['a label of 1', LABEL, blockInput(free, { label: 'A' })],
    ['a label of 1 after trimming', LABEL, blockInput(free, { label: '   A   ' })],
    ['a label of only spaces', LABEL, blockInput(free, { label: '     ' })],
    ['a label of 41', LABEL, blockInput(free, { label: chars(ABSENCE_LIMITS.label[1] + 1) })],
    ['no label', LABEL, blockInput(free, { label: null })],
  ];
  for (const [label, field, input] of rows) {
    await test.step(label, async () => {
      const before = absenceSnapshot(created.id);
      await refusedOn(await absences.addBlockRaw(salon, owner.tokens, person.id, input), field, label);
      expect(absenceSnapshot(created.id), `${label}: nothing saved`).toBe(before);
    });
  }
  await team.dispose();
});

// ---------------------------------------------------------------- AC-5 touching, overlapping, and what it may lie on

test('TC-F06-S03-013 AC-5: blocks of one person may touch but not overlap, 409 conflict changing nothing; another person\'s at the same time, and the same times on another date, are allowed', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, created, person } = await absenceSalon(identity, operator, clock);
  const date = plusDays(today, 3);
  await absences.addBlock(salon, owner.tokens, person.id, blockInput(date, { starts: '10:00', finishes: '12:00' }));
  await test.step('touching on each side', async () => {
    await absences.addBlock(salon, owner.tokens, person.id, blockInput(date, { starts: '09:00', finishes: '10:00', label: 'Before' }));
    await absences.addBlock(salon, owner.tokens, person.id, blockInput(date, { starts: '12:00', finishes: '13:00', label: 'After' }));
  });
  const rows: [string, string, string][] = [
    ['the same times', '10:00', '12:00'], ['inside it', '10:30', '11:00'], ['around all three', '08:00', '14:00'],
    ['over its start', '09:45', '10:15'], ['over its end', '11:45', '12:15'],
  ];
  for (const [label, starts, finishes] of rows) {
    await test.step(label, async () => {
      const before = absenceSnapshot(created.id);
      await refused(await absences.addBlockRaw(salon, owner.tokens, person.id, blockInput(date, { starts, finishes })), 409, 'conflict', label);
      expect(absenceSnapshot(created.id), `${label}: nothing changed`).toBe(before);
    });
  }
  await test.step('another person, and another date', async () => {
    const other = await team.add(salon, owner.tokens);
    await absences.addBlock(salon, owner.tokens, other.id, blockInput(date, { starts: '10:00', finishes: '12:00' }));
    await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(date, 1), { starts: '10:00', finishes: '12:00' }));
  });
  await team.dispose();
});

test('TC-F06-S03-014 AC-5: of two overlapping blocks sent at once exactly one is kept, in both orders across a sweep of gaps, and two touching blocks sent at once are both kept', async ({ identity, operator, clock }) => {
  test.setTimeout(180_000);
  const { today, team, absences, salon, owner, person } = await absenceSalon(identity, operator, clock);
  const wins = [0, 0];
  let overlapped = 0;
  let offset = 1;
  for (const gap of GAPS) {
    for (const first of [0, 1]) {
      await test.step(`gap ${gap} ms, side ${first} sent first`, async () => {
        offset += 1;
        const date = plusDays(today, offset);
        const sides = [blockInput(date, { starts: '10:00', finishes: '11:00', label: 'Side zero' }), blockInput(date, { starts: '10:30', finishes: '11:30', label: 'Side one' })];
        const order = first === 0 ? [0, 1] : [1, 0];
        const fired = await fireTogether(order.map(side => () => absences.addBlockRaw(salon, owner.tokens, person.id, sides[side])), gap);
        if (fired.overlapped) overlapped += 1;
        const statuses = fired.answers.map(a => a.status());
        expect(statuses.filter(s => s === 201).length, `exactly one was kept (${statuses})`).toBe(1);
        const winnerAt = statuses.indexOf(201);
        await refused(fired.answers[1 - winnerAt], 409, 'conflict', 'the other is told');
        const winner = order[winnerAt];
        wins[winner] += 1;
        const kept = (await absences.read(salon, owner.tokens, person.id)).blockedTime.filter(b => b.date === date);
        expect(kept.map(comparableBlock), 'one block, the winner\'s').toEqual([comparableBlock(sides[winner])]);
      });
    }
  }
  expect(wins.every(n => n > 0), `each side won at least once (${wins}), so neither order went unfought`).toBe(true);
  expect(overlapped, 'the requests really were in flight together').toBeGreaterThan(0);
  await test.step('touching blocks at once are not an overlap', async () => {
    const date = plusDays(today, offset + 1);
    const answers = await Promise.all([
      absences.addBlockRaw(salon, owner.tokens, person.id, blockInput(date, { starts: '10:00', finishes: '11:00' })),
      absences.addBlockRaw(salon, owner.tokens, person.id, blockInput(date, { starts: '11:00', finishes: '12:00' })),
    ]);
    expect(answers.map(a => a.status()), 'both kept').toEqual([201, 201]);
  });
  await team.dispose();
});

test('TC-F06-S03-015 AC-5: a block may lie outside the person\'s hours, on a day they do not work and on their leave, and is stored as entered', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, person } = await absenceSalon(identity, operator, clock);
  const week = weekOf('09:00', '18:00', ['sunday']);
  await new Schedules(team).save(salon, owner.tokens, person.id, week);
  expect(week.find(d => d.day === 'sunday'), 'Sunday is not worked').toEqual(offDay('sunday'));
  let sunday = plusDays(today, 1);
  while (new Date(`${sunday}T00:00:00Z`).getUTCDay() !== 0) sunday = plusDays(sunday, 1);
  const onLeave = plusDays(sunday, 2);
  await absences.addLeave(salon, owner.tokens, person.id, leaveInput(onLeave));
  const inputs = [
    blockInput(sunday, { starts: '10:00', finishes: '11:00', label: 'On a day off' }),
    blockInput(plusDays(sunday, 1), { starts: '06:00', finishes: '07:00', label: 'Before the hours' }),
    blockInput(onLeave, { starts: '12:00', finishes: '13:00', label: 'On leave' }),
  ];
  for (const input of inputs) {
    expect(comparableBlock((await absences.addBlock(salon, owner.tokens, person.id, input)).block), String(input.label)).toEqual(comparableBlock(input));
  }
  expect((await absences.read(salon, owner.tokens, person.id)).blockedTime.map(comparableBlock), 'read back').toEqual(inputs.map(comparableBlock));
  await team.dispose();
});

// ---------------------------------------------------------------- AC-5 the limit

test('TC-F06-S03-016 AC-5: a person has at most 200 upcoming blocks: the 200th is saved, the next is 409 limit_reached saving nothing, another person is not limited by it, and removing one or one passing makes room', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, created, person } = await absenceSalon(identity, operator, clock);
  const first = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(today))).block;
  blockCopies(created.id, first.id, nextDays(plusDays(today, 1), ABSENCE_LIMITS.blocks - 2));
  expect((await absences.read(salon, owner.tokens, person.id)).blockedTime.length, 'arranged at 199').toBe(ABSENCE_LIMITS.blocks - 1);
  const free = plusDays(today, 300);
  const last = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(free))).block;
  expect((await absences.read(salon, owner.tokens, person.id)).blockedTime.length, 'the 200th was saved').toBe(ABSENCE_LIMITS.blocks);
  await test.step('the 201st', async () => {
    const before = absenceSnapshot(created.id);
    await refused(await absences.addBlockRaw(salon, owner.tokens, person.id, blockInput(plusDays(free, 1))), 409, 'limit_reached', 'the 201st');
    expect(absenceSnapshot(created.id), 'nothing saved').toBe(before);
  });
  await test.step('another person is not limited by it', async () => {
    const other = await team.add(salon, owner.tokens);
    await absences.addBlock(salon, owner.tokens, other.id, blockInput(free));
  });
  await test.step('removing one makes room, for one', async () => {
    await absences.removeBlock(salon, owner.tokens, person.id, last.id);
    await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(free, 1)));
    await refused(await absences.addBlockRaw(salon, owner.tokens, person.id, blockInput(plusDays(free, 2))), 409, 'limit_reached', 'full again');
  });
  await test.step('a block that has passed is not upcoming, so it makes room', async () => {
    await clock.advance(DAY);
    const tokens = await signedInAgain(identity, owner);
    await absences.addBlock(salon, tokens, person.id, blockInput(plusDays(free, 2)));
    await refused(await absences.addBlockRaw(salon, tokens, person.id, blockInput(plusDays(free, 3))), 409, 'limit_reached', 'full again');
  });
  await team.dispose();
});

test('TC-F06-S03-017 AC-5: the limit of 200 holds under simultaneous requests: a burst stops at exactly 200, every other request is told 409 limit_reached, and the same holds after making room', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, created, person } = await absenceSalon(identity, operator, clock);
  const first = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(today))).block;
  blockCopies(created.id, first.id, nextDays(plusDays(today, 1), 194));
  const dates = async () => (await absences.read(salon, owner.tokens, person.id)).blockedTime.map(b => b.date);
  const burst = async (label: string, from: number, requests: number, room: number) => {
    const before = await dates();
    expect(before.length, `${label}: the room is what it should be`).toBe(ABSENCE_LIMITS.blocks - room);
    const sent = nextDays(plusDays(today, from), requests);
    const { answers, overlapped } = await fireTogether(sent.map(date => () => absences.addBlockRaw(salon, owner.tokens, person.id, blockInput(date))), 0);
    expect(overlapped, `${label}: every request was in flight before any was answered`).toBe(true);
    const statuses = answers.map(a => a.status());
    expect(statuses.filter(s => s === 201).length, `${label}: exactly the room left was taken (${statuses})`).toBe(room);
    for (const r of answers.filter(a => a.status() !== 201)) await refused(r, 409, 'limit_reached', `${label}: the others are told why`);
    const after = await dates();
    expect(after.length, `${label}: exactly 200 stored`).toBe(ABSENCE_LIMITS.blocks);
    expect(after.filter(d => !before.includes(d)).sort(), `${label}: the ones stored are the ones answered 201`).toEqual(sent.filter((_, n) => statuses[n] === 201).sort());
  };
  await burst('five places, twelve requests', 250, 12, 5);
  await test.step('three places made by removing, then six requests', async () => {
    const listed = (await absences.read(salon, owner.tokens, person.id)).blockedTime;
    for (const block of listed.slice(10, 13)) await absences.removeBlock(salon, owner.tokens, person.id, block.id);
    await burst('three places, six requests', 270, 6, 3);
  });
  await team.dispose();
});

// ---------------------------------------------------------------- AC-6 the list and removal

test('TC-F06-S03-018 AC-6: the schedule lists blocks from today on in date and time order; Remove takes one away; one that has passed, is gone, unknown or another person\'s answers 404 and removes nothing', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, created, person } = await absenceSalon(identity, operator, clock);
  const passing = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(today, { label: 'Passing' }))).block;
  const late = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(today, 2), { starts: '15:00', finishes: '16:00', label: 'Late' }))).block;
  const early = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(today, 2), { starts: '09:00', finishes: '10:00', label: 'Early' }))).block;
  const next = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(today, 1), { starts: '18:00', finishes: '19:00', label: 'Next' }))).block;
  expect((await absences.read(salon, owner.tokens, person.id)).blockedTime.map(b => b.id), 'date and time order').toEqual([passing.id, next.id, early.id, late.id]);
  await test.step('another person\'s, through this person', async () => {
    const other = await team.add(salon, owner.tokens);
    const theirs = (await absences.addBlock(salon, owner.tokens, other.id, blockInput(today))).block;
    const before = absenceSnapshot(created.id);
    await refused(await absences.removeBlockRaw(salon, owner.tokens, person.id, theirs.id), 404, 'not_found', 'through the wrong person');
    expect(absenceSnapshot(created.id), 'nothing removed').toBe(before);
  });
  await test.step('removed, then gone', async () => {
    await absences.removeBlock(salon, owner.tokens, person.id, late.id);
    await refused(await absences.removeBlockRaw(salon, owner.tokens, person.id, late.id), 404, 'not_found', 'removed twice');
    await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(today, 2), { starts: '15:00', finishes: '16:00', label: 'Its time is free again' }));
  });
  await clock.advance(DAY);
  const tokens = await signedInAgain(identity, owner);
  await test.step('a day on, the first has passed', async () => {
    const before = absenceSnapshot(created.id);
    await refused(await absences.removeBlockRaw(salon, tokens, person.id, passing.id), 404, 'not_found', 'one that has passed');
    expect(absenceSnapshot(created.id), 'nothing removed').toBe(before);
    expect((await absences.read(salon, tokens, person.id)).blockedTime[0].id, 'the list starts from the salon\'s today').toBe(next.id);
  });
  await team.dispose();
});

test('TC-F06-S03-019 AC-8: the preview of what a block would affect answers an empty list before F07, and times that break AC-4\'s rule are a 400', async ({ identity, operator, clock }) => {
  const { today, team, salon, owner, person } = await absenceSalon(identity, operator, clock);
  const date = plusDays(today, 4);
  const r = await team.call(salon, 'GET', blockAffectedPath(person.id, date, '10:00', '11:00'), owner.tokens);
  expect(r.status()).toBe(200);
  expect(await r.json()).toEqual({ affected: [] });
  await refused(await team.call(salon, 'GET', blockAffectedPath(person.id, date, '11:00', '10:00'), owner.tokens), 400, 'bad_request', 'an end before the start');
  await team.dispose();
});

// ---------------------------------------------------------------- the salon's date

test('TC-F06-S03-020 AC-4: "today" and the year ahead are the salon\'s date on either side of UTC, and a block passes when the salon\'s day does', async ({ identity, operator, clock }) => {
  test.setTimeout(180_000);
  const base = plusDays(afterNewestPlan().slice(0, 10), 2);
  const sides = [
    { label: 'Colombo, UTC+05:30', timeZone: COLOMBO, at: `${plusDays(base, -1)}T19:00:00Z`, utcIs: plusDays(base, -1), west: false },
    { label: 'Bogota, UTC-05:00', timeZone: 'America/Bogota', at: `${plusDays(base, 1)}T04:30:00Z`, utcIs: plusDays(base, 1), west: true },
  ];
  const team = new Team(identity);
  const absences = new Absences(team);
  for (const side of sides) {
    await test.step(side.label, async () => {
      await clock.set(side.at);
      const now = await identity.now();
      expect(localDate(now, side.timeZone), `${side.label}: the salon's date is the day under test`).toBe(base);
      expect(now.slice(0, 10), 'UTC is on another date, or this proves nothing').toBe(side.utcIs);
      const { salon, owner, created } = await teamSalon(operator, identity, 20, { timeZone: side.timeZone });
      const person = await team.add(salon, owner.tokens);
      const today = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(base))).block;
      await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusYear(base)));
      const before = absenceSnapshot(created.id);
      await refusedOn(await absences.addBlockRaw(salon, owner.tokens, person.id, blockInput(plusDays(base, -1))), /^date$/, 'the salon\'s yesterday');
      await refusedOn(await absences.addBlockRaw(salon, owner.tokens, person.id, blockInput(plusDays(plusYear(base), 1))), /^date$/, 'a year and a day from the salon\'s today');
      expect(absenceSnapshot(created.id), 'nothing saved').toBe(before);
      if (side.west) {
        await clock.advance(31 * 60 * 1000);
        const tokens = await signedInAgain(identity, owner);
        await refused(await absences.removeBlockRaw(salon, tokens, person.id, today.id), 404, 'not_found', 'its day has ended at the salon');
      }
    });
  }
  await team.dispose();
});
