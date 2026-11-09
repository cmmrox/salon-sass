/**
 * F06-S03's case deferred to F07, run once F07-S02's availability exists: leave and blocked time taken out of the times
 * offered for a person. A date within leave offers nothing whichever way the week reads; a block at a window's edge and
 * one across a break; a day whose blocks leave no time; removing them restores the times; another salon's absences
 * change nothing here.
 *
 * Expected results are F06-S03's AC-7 and AC-11 and its deferred entry's reason (stage.yaml): "with F07 the offered times
 * must leave out leave and blocks, for this salon only". Expected times are worked out by qa/shared/availability.ts.
 * Registered under F06-S03 at F07's feature-verify.
 */
import { minus, range, startsFor, toClock, type Person } from '../../shared/availability';
import { CUT, bookingSalon } from '../../shared/booking';
import { plusDays, weekdayOf } from '../../shared/salon-hours';
import { Absences, blockInput, leaveInput } from '../../shared/team-leave';
import { Schedules, brk, changeInput, weekOf } from '../../shared/team-schedule';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

const LENGTH = CUT.minutes + CUT.buffer;

test('TC-F06-S03-030 AC-7, AC-11: leave takes a person\'s whole day out, even a day a date change made a working one; blocks are taken out at a window\'s edge and across a break, and blocks that fill the day leave nothing; removing them brings the times back; another salon\'s absences change nothing', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const other = await bookingSalon(identity, operator, clock, { people: 1, keepClock: true });
  const [person] = s.members;
  const absences = new Absences(s.team);
  const d = (n: number) => plusDays(s.today, n);
  const day = weekdayOf(d(10));
  await new Schedules(s.team).save(s.salon, s.owner.tokens, person.id, weekOf('09:00', '17:00', [], { [day]: { breaks: [brk('13:00', '14:00')] } }));
  await new Schedules(s.team).add(s.salon, s.owner.tokens, person.id, changeInput(d(11), { starts: '10:00', finishes: '15:00' }));
  const ask = async (date: string) => s.bookings.availability(s.salon, s.owner.tokens, { serviceId: s.service.id, date, memberId: person.id });
  const week: Person = { id: person.id, name: person.name, windows: minus([range('09:00', '17:00')], [range('13:00', '14:00')]), busy: [] };
  const expected = (cuts: [string, string][]) => startsFor({ ...week, windows: minus(week.windows, cuts.map(([a, b]) => range(a, b))) }, LENGTH, 15).map(toClock);

  const leave = await absences.addLeave(s.salon, s.owner.tokens, person.id, leaveInput(d(10), { lastDay: d(11) }));
  for (const date of [d(10), d(11)]) {
    const a = await ask(date);
    expect([a.times, a.reason], `${date}: on leave, a date change or not`).toEqual([[], 'not_working']);
  }
  await absences.removeLeave(s.salon, s.owner.tokens, person.id, leave.leave.id);
  expect((await ask(d(10))).times.map(t => t.time), 'back after the leave goes').toEqual(expected([]));

  const edge = await absences.addBlock(s.salon, s.owner.tokens, person.id, blockInput(d(10), { starts: '09:00', finishes: '10:00' }));
  const across = await absences.addBlock(s.salon, s.owner.tokens, person.id, blockInput(d(10), { starts: '12:30', finishes: '14:30' }));
  expect((await ask(d(10))).times.map(t => t.time), 'a block at the edge and one across the break').toEqual(expected([['09:00', '10:00'], ['12:30', '14:30']]));
  await absences.removeBlock(s.salon, s.owner.tokens, person.id, edge.block.id);
  await absences.removeBlock(s.salon, s.owner.tokens, person.id, across.block.id);
  expect((await ask(d(10))).times.map(t => t.time), 'back after the blocks go').toEqual(expected([]));

  const full = d(17);
  for (const [a, b] of [['09:00', '13:00'], ['14:00', '17:00']]) await absences.addBlock(s.salon, s.owner.tokens, person.id, blockInput(full, { starts: a, finishes: b }));
  const filled = await ask(full);
  expect(filled.times, 'blocks that fill the day leave no time').toEqual([]);
  expect(['not_working', 'fully_booked'], 'and say why').toContain(filled.reason);

  const theirs = other.members[0];
  await new Absences(other.team).addLeave(other.salon, other.owner.tokens, theirs.id, leaveInput(d(12)));
  await new Absences(other.team).addBlock(other.salon, other.owner.tokens, theirs.id, blockInput(d(13), { starts: '09:00', finishes: '17:00' }));
  for (const date of [d(12), d(13)]) expect((await ask(date)).times.length, `${date}: another salon's absences change nothing`).toBeGreaterThan(0);
});
