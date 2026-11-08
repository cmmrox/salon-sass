/**
 * F06-S03 leave from outside: adding whole days from today to a year ahead, one-day leave, each type, the
 * note; overlaps one at a time and at once; the limit of 100 one at a time and under a burst; what the
 * schedule lists, including leave under way; removal; the salon's date against UTC; adding to someone being
 * removed; and the preview of what a period would affect.
 *
 * Expected results are the F06-S03 spec's (AC-1, AC-2, AC-3, "API and data changes", "Risks and test focus") and
 * decision 104. What the sheet says about each is in qa/web/F06/S03-leave-and-block-screens.spec.ts, and who may
 * reach a leave's type and note in S03-access.spec.ts. Names the build gives are in `PENDING_F06_S03`.
 *
 * What this cannot prove: that leave takes a person off their working windows (AC-7) is `TeamSchedule`'s, a Java
 * interface with no route until F07, and "N bookings to resolve" with a number above zero (AC-3, AC-8) needs a
 * booking (deferred cases 030 and 031). Here `bookingsToResolve` and `affected` can only be shown to be zero.
 *
 * A race between two different requests sweeps `GAPS` in both orders and asserts each side won and that the requests
 * really overlapped: sent in one tick, the shorter path can win every time and hide a broken order (F06-S01's lesson).
 */
import { refused } from '../../shared/booking-rules';
import type { SignedStaff, Identity } from '../../shared/identity';
import { test, expect, afterNewestPlan } from '../../shared/operator';
import { localDate, plusDays, plusYear } from '../../shared/salon-hours';
import { GAPS, Team, fireTogether, teamSalon } from '../../shared/team';
import {
  ABSENCE_LIMITS, Absences, LEAVE_TYPES, absenceSalon, absenceSnapshot, comparableLeave, leaveAffectedPath, leaveCopies, leaveInput, nextDays, refusedOn, type Leave,
} from '../../shared/team-leave';

test.describe.configure({ timeout: 120_000 });

const COLOMBO = 'Asia/Colombo';
const DAY = 24 * 60 * 60 * 1000;
const FIRST = /^firstDay$/;
const LAST = /^lastDay$/;
const signedInAgain = async (identity: Identity, owner: SignedStaff) => (await identity.login(owner.salon, owner.account)).tokens;
const firstDays = (list: { firstDay: string }[]) => list.map(l => l.firstDay);

// ---------------------------------------------------------------- AC-1 adding

test('TC-F06-S03-001 AC-1, AC-8: leave is saved from today to a year ahead: one day alone, every type, with a note of up to 60 characters or none; each answers with nothing affected and none to resolve, and reads back in date order', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, person } = await absenceSalon(identity, operator, clock);
  const last = plusYear(today);
  const inputs = [
    leaveInput(plusDays(last, -3), { lastDay: last, type: 'annual', note: 'To a year ahead' }),
    leaveInput(today, { type: 'sick', note: 'N'.repeat(ABSENCE_LIMITS.noteLength) }),
    leaveInput(plusDays(today, 10), { lastDay: plusDays(today, 12), type: 'personal' }),
    leaveInput(plusDays(today, 20), { type: 'other', note: null }),
  ];
  const added: Leave[] = [];
  for (const input of inputs) {
    const answer = await absences.addLeave(salon, owner.tokens, person.id, input);
    expect(comparableLeave(answer.leave), `${String(input.firstDay)}: holds what was sent`).toEqual(comparableLeave(input));
    expect(answer.affected, 'no booking exists before F07, and the answer says so').toEqual([]);
    expect(answer.leave.bookingsToResolve, 'none to resolve').toBe(0);
    added.push(answer.leave);
  }
  expect(LEAVE_TYPES.every(t => added.some(l => l.type === t)), 'each type was saved').toBe(true);
  const read = await absences.read(salon, owner.tokens, person.id);
  expect(firstDays(read.leave), 'date order, whatever order they were added').toEqual([today, plusDays(today, 10), plusDays(today, 20), plusDays(last, -3)]);
  expect(read.leave[0].note, 'a note of exactly 60 characters is kept').toBe('N'.repeat(ABSENCE_LIMITS.noteLength));
  expect(read.leave[1].note ?? null, 'no note').toBeNull();
  await team.dispose();
});

test('TC-F06-S03-002 AC-1: a first day past or beyond a year, a last day before the first or beyond a year, a type that is not one and a note of 61 are each a 400 on their field that saves nothing', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, created, person } = await absenceSalon(identity, operator, clock);
  const free = plusDays(today, 5);
  const beyond = plusDays(plusYear(today), 1);
  const rows: [string, RegExp, unknown][] = [
    ['a first day yesterday', FIRST, leaveInput(plusDays(today, -1), { lastDay: free })],
    // The correction at close (decision 106): a first day beyond a year is a 400 on firstDay alone.
    ['a first day beyond a year', FIRST, leaveInput(beyond)],
    ['a first day that is not a date', FIRST, leaveInput('next tuesday', { lastDay: free })],
    ['a first day that does not exist', FIRST, leaveInput('2099-02-30', { lastDay: free })],
    ['no first day', FIRST, leaveInput(free, { firstDay: null })],
    ['a last day before the first', LAST, leaveInput(free, { lastDay: plusDays(free, -1) })],
    ['a last day beyond a year', LAST, leaveInput(free, { lastDay: beyond })],
    ['no last day', LAST, leaveInput(free, { lastDay: null })],
    ['a type that is not one', /^type$/, leaveInput(free, { type: 'holiday' })],
    ['no type', /^type$/, leaveInput(free, { type: null })],
    ['a note of 61 characters', /^note$/, leaveInput(free, { note: 'N'.repeat(ABSENCE_LIMITS.noteLength + 1) })],
  ];
  for (const [label, field, input] of rows) {
    await test.step(label, async () => {
      const before = absenceSnapshot(created.id);
      await refusedOn(await absences.addLeaveRaw(salon, owner.tokens, person.id, input), field, label);
      expect(absenceSnapshot(created.id), `${label}: nothing saved`).toBe(before);
    });
  }
  await team.dispose();
});

// ---------------------------------------------------------------- AC-2 overlaps

test('TC-F06-S03-003 AC-2: leave overlapping a person\'s leave by a day or more is 409 conflict changing nothing; leave on the days next to it, and another person\'s on the same days, are allowed', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, created, person } = await absenceSalon(identity, operator, clock);
  const [a, b] = [plusDays(today, 10), plusDays(today, 14)];
  await absences.addLeave(salon, owner.tokens, person.id, leaveInput(a, { lastDay: b }));
  const rows: [string, string, string][] = [
    ['the same days', a, b],
    ['inside it', plusDays(a, 1), plusDays(a, 2)],
    ['around it', plusDays(a, -1), plusDays(b, 1)],
    ['ending on its first day', plusDays(a, -3), a],
    ['starting on its last day', b, plusDays(b, 3)],
    ['one day of it', plusDays(a, 2), plusDays(a, 2)],
  ];
  for (const [label, first, last] of rows) {
    await test.step(label, async () => {
      const before = absenceSnapshot(created.id);
      await refused(await absences.addLeaveRaw(salon, owner.tokens, person.id, leaveInput(first, { lastDay: last })), 409, 'conflict', label);
      expect(absenceSnapshot(created.id), `${label}: nothing changed`).toBe(before);
    });
  }
  await test.step('next to it', async () => {
    await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(a, -1)));
    await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(b, 1)));
  });
  await test.step('another person', async () => {
    const other = await team.add(salon, owner.tokens);
    await absences.addLeave(salon, owner.tokens, other.id, leaveInput(a, { lastDay: b }));
  });
  await team.dispose();
});

test('TC-F06-S03-004 AC-2: of two overlapping leaves sent at once exactly one is kept, in both orders across a sweep of gaps; the other is 409 conflict and the kept one is the winner\'s', async ({ identity, operator, clock }) => {
  test.setTimeout(180_000);
  const { today, team, absences, salon, owner, person } = await absenceSalon(identity, operator, clock);
  const wins = [0, 0];
  let overlapped = 0;
  let offset = 1;
  for (const gap of GAPS) {
    for (const first of [0, 1]) {
      await test.step(`gap ${gap} ms, side ${first} sent first`, async () => {
        offset += 6;
        const sides = [
          leaveInput(plusDays(today, offset), { lastDay: plusDays(today, offset + 2), type: 'annual' }),
          leaveInput(plusDays(today, offset + 1), { lastDay: plusDays(today, offset + 3), type: 'sick' }),
        ];
        const order = first === 0 ? [0, 1] : [1, 0];
        const fired = await fireTogether(order.map(side => () => absences.addLeaveRaw(salon, owner.tokens, person.id, sides[side])), gap);
        if (fired.overlapped) overlapped += 1;
        const statuses = fired.answers.map(a => a.status());
        expect(statuses.filter(s => s === 201).length, `exactly one was kept (${statuses})`).toBe(1);
        const winnerAt = statuses.indexOf(201);
        await refused(fired.answers[1 - winnerAt], 409, 'conflict', 'the other is told');
        const winner = order[winnerAt];
        wins[winner] += 1;
        const kept = (await absences.read(salon, owner.tokens, person.id)).leave.filter(l => l.firstDay >= plusDays(today, offset) && l.firstDay <= plusDays(today, offset + 1));
        expect(kept.map(comparableLeave), 'one leave, the winner\'s').toEqual([comparableLeave(sides[winner])]);
      });
    }
  }
  expect(wins.every(n => n > 0), `each side won at least once (${wins}), so neither order went unfought`).toBe(true);
  expect(overlapped, 'the requests really were in flight together').toBeGreaterThan(0);
  await team.dispose();
});

// ---------------------------------------------------------------- AC-2 the limit

test('TC-F06-S03-005 AC-2: a person has at most 100 upcoming leaves: the 100th is saved, the next is 409 limit_reached saving nothing, another person is not limited by it, and removing one or one ending makes room', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, created, person } = await absenceSalon(identity, operator, clock);
  const first = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(today))).leave;
  leaveCopies(created.id, first.id, nextDays(plusDays(today, 1), ABSENCE_LIMITS.leaves - 2));
  expect((await absences.read(salon, owner.tokens, person.id)).leave.length, 'arranged at 99').toBe(ABSENCE_LIMITS.leaves - 1);
  const hundredth = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 120)))).leave;
  expect((await absences.read(salon, owner.tokens, person.id)).leave.length, 'the 100th was saved').toBe(ABSENCE_LIMITS.leaves);
  await test.step('the 101st', async () => {
    const before = absenceSnapshot(created.id);
    await refused(await absences.addLeaveRaw(salon, owner.tokens, person.id, leaveInput(plusDays(today, 130))), 409, 'limit_reached', 'the 101st');
    expect(absenceSnapshot(created.id), 'nothing saved').toBe(before);
  });
  await test.step('another person is not limited by it', async () => {
    const other = await team.add(salon, owner.tokens);
    await absences.addLeave(salon, owner.tokens, other.id, leaveInput(plusDays(today, 130)));
  });
  await test.step('removing one makes room, for one', async () => {
    await absences.removeLeave(salon, owner.tokens, person.id, hundredth.id);
    await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 130)));
    await refused(await absences.addLeaveRaw(salon, owner.tokens, person.id, leaveInput(plusDays(today, 131))), 409, 'limit_reached', 'full again');
  });
  await test.step('leave that has ended is not upcoming, so it makes room', async () => {
    await clock.advance(DAY);
    const tokens = await signedInAgain(identity, owner);
    await absences.addLeave(salon, tokens, person.id, leaveInput(plusDays(today, 131)));
    await refused(await absences.addLeaveRaw(salon, tokens, person.id, leaveInput(plusDays(today, 132))), 409, 'limit_reached', 'full again');
  });
  await team.dispose();
});

test('TC-F06-S03-006 AC-2: the limit of 100 holds under simultaneous requests: a burst stops at exactly 100, every other request is told 409 limit_reached, and the same holds after making room', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, created, person } = await absenceSalon(identity, operator, clock);
  const first = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(today))).leave;
  leaveCopies(created.id, first.id, nextDays(plusDays(today, 1), 94));
  const burst = async (label: string, from: number, requests: number, room: number) => {
    const before = firstDays((await absences.read(salon, owner.tokens, person.id)).leave);
    expect(before.length, `${label}: the room is what it should be`).toBe(ABSENCE_LIMITS.leaves - room);
    const sent = nextDays(plusDays(today, from), requests);
    const { answers, overlapped } = await fireTogether(sent.map(day => () => absences.addLeaveRaw(salon, owner.tokens, person.id, leaveInput(day))), 0);
    expect(overlapped, `${label}: every request was in flight before any was answered`).toBe(true);
    const statuses = answers.map(a => a.status());
    expect(statuses.filter(s => s === 201).length, `${label}: exactly the room left was taken (${statuses})`).toBe(room);
    for (const r of answers.filter(a => a.status() !== 201)) await refused(r, 409, 'limit_reached', `${label}: the others are told why`);
    const after = firstDays((await absences.read(salon, owner.tokens, person.id)).leave);
    expect(after.length, `${label}: exactly 100 stored`).toBe(ABSENCE_LIMITS.leaves);
    expect(after.filter(d => !before.includes(d)).sort(), `${label}: the ones stored are the ones answered 201`).toEqual(sent.filter((_, n) => statuses[n] === 201).sort());
  };
  await burst('five places, twelve requests', 150, 12, 5);
  await test.step('three places made by removing, then six requests', async () => {
    const listed = (await absences.read(salon, owner.tokens, person.id)).leave;
    for (const leave of listed.slice(10, 13)) await absences.removeLeave(salon, owner.tokens, person.id, leave.id);
    await burst('three places, six requests', 170, 6, 3);
  });
  await team.dispose();
});

// ---------------------------------------------------------------- AC-3 the list and removal

test('TC-F06-S03-007 AC-3: the schedule lists leave that has not ended, including leave under way, which can be removed; leave that has ended leaves the list and answers 404; so do leave that is gone, unknown or another person\'s', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, created, person } = await absenceSalon(identity, operator, clock);
  const endsToday = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(today))).leave;
  const underWay = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 1), { lastDay: plusDays(today, 3) }))).leave;
  const later = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 8)))).leave;
  await test.step('another person\'s, through this person', async () => {
    const other = await team.add(salon, owner.tokens);
    const theirs = (await absences.addLeave(salon, owner.tokens, other.id, leaveInput(today))).leave;
    const before = absenceSnapshot(created.id);
    await refused(await absences.removeLeaveRaw(salon, owner.tokens, person.id, theirs.id), 404, 'not_found', 'through the wrong person');
    expect(absenceSnapshot(created.id), 'nothing removed').toBe(before);
  });
  await clock.advance(2 * DAY);
  const tokens = await signedInAgain(identity, owner);
  await test.step('two days on: the first has ended, the second is under way', async () => {
    const listed = await absences.read(salon, tokens, person.id);
    expect(listed.leave.map(l => l.id), 'under way and later, in date order').toEqual([underWay.id, later.id]);
    const before = absenceSnapshot(created.id);
    await refused(await absences.removeLeaveRaw(salon, tokens, person.id, endsToday.id), 404, 'not_found', 'leave that has ended');
    expect(absenceSnapshot(created.id), 'nothing removed').toBe(before);
  });
  await test.step('leave under way can be removed, and then is gone', async () => {
    await absences.removeLeave(salon, tokens, person.id, underWay.id);
    expect((await absences.read(salon, tokens, person.id)).leave.map(l => l.id)).toEqual([later.id]);
    await refused(await absences.removeLeaveRaw(salon, tokens, person.id, underWay.id), 404, 'not_found', 'removed twice');
  });
  await team.dispose();
});

test('TC-F06-S03-008 AC-8: the preview of what a period would affect answers an empty list before F07, for a period that overlaps nothing or already has leave, and a period that breaks AC-1\'s rule is a 400', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, person } = await absenceSalon(identity, operator, clock);
  const day = plusDays(today, 4);
  await absences.addLeave(salon, owner.tokens, person.id, leaveInput(day));
  for (const [label, first, last] of [['free days', plusDays(today, 20), plusDays(today, 22)], ['days with leave', day, day]] as const) {
    const r = await team.call(salon, 'GET', leaveAffectedPath(person.id, first, last), owner.tokens);
    expect(r.status(), label).toBe(200);
    expect(await r.json(), label).toEqual({ affected: [] });
  }
  const reversed = await team.call(salon, 'GET', leaveAffectedPath(person.id, plusDays(today, 9), plusDays(today, 8)), owner.tokens);
  await refused(reversed, 400, 'bad_request', 'a last day before the first');
  await team.dispose();
});

// ---------------------------------------------------------------- the salon's date

test('TC-F06-S03-009 AC-1: "today" and the year ahead are the salon\'s date on either side of UTC, for the first day and the last, and leave ends when the salon\'s day does', async ({ identity, operator, clock }) => {
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
      const today = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(base))).leave;
      await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusYear(base)));
      const before = absenceSnapshot(created.id);
      await refusedOn(await absences.addLeaveRaw(salon, owner.tokens, person.id, leaveInput(plusDays(base, -1), { lastDay: plusDays(base, 1) })), FIRST, 'the salon\'s yesterday');
      await refusedOn(await absences.addLeaveRaw(salon, owner.tokens, person.id, leaveInput(plusDays(plusYear(base), -1), { lastDay: plusDays(plusYear(base), 1) })), LAST, 'a year and a day from the salon\'s today');
      expect(absenceSnapshot(created.id), 'nothing saved').toBe(before);
      expect(firstDays((await absences.read(salon, owner.tokens, person.id)).leave)[0], 'today\'s is listed').toBe(base);
      if (side.west) {
        await clock.advance(31 * 60 * 1000);
        expect(localDate(await identity.now(), side.timeZone), '00:01 the next day at the salon').toBe(plusDays(base, 1));
        const tokens = await signedInAgain(identity, owner);
        await refused(await absences.removeLeaveRaw(salon, tokens, person.id, today.id), 404, 'not_found', 'its last day has ended at the salon');
        expect(firstDays((await absences.read(salon, tokens, person.id)).leave)[0], 'it left the list').toBe(plusYear(base));
      }
    });
  }
  await team.dispose();
});

// ---------------------------------------------------------------- someone being removed

test('TC-F06-S03-010 AC-2, AC-5: leave or a block added while the person is being removed either goes in first or answers 404, in both orders across a sweep of gaps, and the person ends removed with nothing of theirs readable', async ({ identity, operator, clock }) => {
  test.setTimeout(180_000);
  const { today, team, absences, salon, owner } = await absenceSalon(identity, operator, clock);
  const outcomes: Record<string, number> = { 201: 0, 404: 0 };
  let overlapped = 0;
  const adds = [
    { label: 'leave', send: (id: string) => absences.addLeaveRaw(salon, owner.tokens, id, leaveInput(plusDays(today, 3))) },
    { label: 'a block', send: (id: string) => absences.addBlockRaw(salon, owner.tokens, id, { date: plusDays(today, 3), starts: '10:00', finishes: '11:00', label: 'Training' }) },
  ];
  for (const add of adds) {
    for (const gap of GAPS) {
      for (const addFirst of [true, false]) {
        await test.step(`${add.label}, gap ${gap} ms, ${addFirst ? 'added' : 'removed'} first`, async () => {
          const person = await team.add(salon, owner.tokens);
          const sends = [() => add.send(person.id), () => team.removeRaw(salon, owner.tokens, person.id, person.version)];
          const fired = await fireTogether(addFirst ? sends : [...sends].reverse(), gap);
          if (fired.overlapped) overlapped += 1;
          const [added, removed] = addFirst ? fired.answers : [...fired.answers].reverse();
          expect(removed.status(), 'the removal goes through').toBe(204);
          expect([201, 404], `the addition went in first or found them gone (${added.status()})`).toContain(added.status());
          outcomes[added.status()] += 1;
          await refused(await team.getRaw(salon, owner.tokens, person.id), 404, 'not_found', 'the person is removed');
        });
      }
    }
  }
  expect(Object.values(outcomes).every(n => n > 0), `both outcomes were seen (${JSON.stringify(outcomes)}), so neither order went unfought`).toBe(true);
  expect(overlapped, 'the requests really were in flight together').toBeGreaterThan(0);
  await team.dispose();
});
