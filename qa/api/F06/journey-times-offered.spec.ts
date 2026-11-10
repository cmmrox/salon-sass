/**
 * F06's journey deferred to F07, run once F07-S02's availability exists: a person's week, a half day, leave and a block,
 * then the times offered for them are exactly their windows inside the salon's own hours.
 *
 * Expected results are the F06 README's journey ("the working windows ... then say exactly that") and the deferred entry
 * in F06's feature.yaml (STF-02, STF-03, STF-05). Expected times are worked out by qa/shared/availability.ts. Registered
 * under F06 at F07's feature-verify, when the deferred entry moves to automation.
 */
import { intersect, minus, range, startsFor, toClock, type Person } from '../../shared/availability';
import { CUT, bookingSalon } from '../../shared/booking';
import { plusDays, week, weekdayOf } from '../../shared/salon-hours';
import { Absences, blockInput, leaveInput } from '../../shared/team-leave';
import { Schedules, brk, changeInput, weekOf } from '../../shared/team-schedule';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

const LENGTH = CUT.minutes + CUT.buffer;

test('TC-F06-004 STF-02, STF-03, STF-05: for a person with a week, a half day, leave and a block, the times offered are exactly their windows inside the salon\'s hours', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [person] = s.members;
  const d = (n: number) => plusDays(s.today, n);
  const [normal, half, away, blocked] = [d(8), d(9), d(10), d(11)];
  // The salon opens 10:00 to 18:00 on the normal day's weekday; the person works 08:00 to 19:00 with a lunch break.
  const salonWeek = week('09:00', '19:00', [], { [weekdayOf(normal)]: ['10:00', '18:00'] });
  await s.hours.save(s.salon, s.owner.tokens, salonWeek);
  await new Schedules(s.team).save(s.salon, s.owner.tokens, person.id, weekOf('08:00', '19:00', [], Object.fromEntries(
    [normal, half, away, blocked].map(x => [weekdayOf(x), { breaks: [brk('13:00', '13:45')] }]))));
  await new Schedules(s.team).add(s.salon, s.owner.tokens, person.id, changeInput(half, { starts: '09:00', finishes: '13:00' }));
  await new Absences(s.team).addLeave(s.salon, s.owner.tokens, person.id, leaveInput(away));
  await new Absences(s.team).addBlock(s.salon, s.owner.tokens, person.id, blockInput(blocked, { starts: '15:00', finishes: '16:30' }));
  const offered = async (date: string) => (await s.bookings.availability(s.salon, s.owner.tokens, { serviceId: s.service.id, date, memberId: person.id })).times.map(t => t.time);
  const at = (windows: [string, string][], salon: [string, string]): string[] =>
    startsFor({ id: person.id, name: person.name, windows: intersect(windows.map(([a, b]) => range(a, b)), [range(...salon)]), busy: [] } as Person, LENGTH, 15).map(toClock);
  const lunch = minus([range('08:00', '19:00')], [range('13:00', '13:45')]).map(([a, b]) => [toClock(a), toClock(b)] as [string, string]);
  expect(await offered(normal), 'the week, inside the salon\'s shorter hours').toEqual(at(lunch, ['10:00', '18:00']));
  expect(await offered(half), 'the half day').toEqual(at([['09:00', '13:00']], ['09:00', '19:00']));
  expect(await offered(away), 'leave: nothing').toEqual([]);
  const withBlock = minus(lunch.map(([a, b]) => range(a, b)), [range('15:00', '16:30')]).map(([a, b]) => [toClock(a), toClock(b)] as [string, string]);
  expect(await offered(blocked), 'the block taken out').toEqual(at(withBlock, ['09:00', '19:00']));
});
