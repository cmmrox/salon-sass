/**
 * F06-S02's case deferred to F07, run once F07-S02's availability exists: the working windows TeamSchedule gives, seen
 * from outside through the free times offered for one person. Whether they work, their windows after breaks at every
 * edge, a date change replacing the week on its date only, a removed person and another salon's person "not found".
 *
 * Expected results are F06-S02's AC-8, AC-9 and AC-14 and its deferred entry's reason (stage.yaml): "with F07 the offered
 * times must match the windows, for this salon only". Expected times are worked out by qa/shared/availability.ts. That
 * TeamSchedule itself ignores closures and holidays cannot be seen through availability, which applies the salon's
 * hours on top; the developer's TeamScheduleContractIntegrationTest holds it. Registered under F06-S02 at F07's
 * feature-verify.
 */
import { randomUUID } from 'node:crypto';

import { refused } from '../../shared/booking-rules';
import { minus, range, startsFor, toClock, type Person } from '../../shared/availability';
import { CUT, bookingSalon } from '../../shared/booking';
import { plusDays, weekdayOf } from '../../shared/salon-hours';
import { Schedules, brk, changeInput, weekOf } from '../../shared/team-schedule';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

const LENGTH = CUT.minutes + CUT.buffer;

test('TC-F06-S02-028 AC-8, AC-9, AC-14: the times offered for a person are their week\'s windows after breaks, edge to edge; a date change replaces the week on its date only; a day off offers nothing; a removed person and another salon\'s answer "not found"', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const other = await bookingSalon(identity, operator, clock, { people: 1, keepClock: true });
  const [person, leaving] = s.members;
  const d1 = plusDays(s.today, 7);
  const d2 = plusDays(d1, 7);
  const d3 = plusDays(d2, 7);
  const day = weekdayOf(d1);
  const off = weekdayOf(plusDays(d1, 1));
  const schedules = new Schedules(s.team);
  await schedules.save(s.salon, s.owner.tokens, person.id, weekOf('09:00', '17:00', [off], { [day]: { breaks: [brk('11:00', '11:30'), brk('14:00', '14:15')] } }));
  await schedules.add(s.salon, s.owner.tokens, person.id, changeInput(d2, { starts: '12:00', finishes: '16:00', breaks: [] }));
  const offered = async (date: string) => (await s.bookings.availability(s.salon, s.owner.tokens, { serviceId: s.service.id, date, memberId: person.id })).times.map(t => t.time);
  const week: Person = { id: person.id, name: person.name, windows: minus([range('09:00', '17:00')], [range('11:00', '11:30'), range('14:00', '14:15')]), busy: [] };
  expect(await offered(d1), 'the week, after both breaks').toEqual(startsFor(week, LENGTH, 15).map(toClock));
  expect(await offered(d2), 'the date change, on its date').toEqual(startsFor({ ...week, windows: [range('12:00', '16:00')] }, LENGTH, 15).map(toClock));
  expect(await offered(d3), 'the week again a week later').toEqual(startsFor(week, LENGTH, 15).map(toClock));
  const dayOff = await s.bookings.availability(s.salon, s.owner.tokens, { serviceId: s.service.id, date: plusDays(d1, 1), memberId: person.id });
  expect([dayOff.times, dayOff.reason], 'a day off').toEqual([[], 'not_working']);
  await s.team.remove(s.salon, s.owner.tokens, await s.team.get(s.salon, s.owner.tokens, leaving.id));
  for (const [label, memberId] of [['a removed person', leaving.id], ['another salon\'s person', other.members[0].id], ['an unknown id', randomUUID()]]) {
    await refused(await s.bookings.availabilityRaw(s.salon, s.owner.tokens, { serviceId: s.service.id, date: d1, memberId }), 404, 'not_found', label);
  }
});
