/**
 * F06-S03's case deferred to F07, run with F07's feature pass now that bookings exist: the bookings a period of leave or a
 * block would affect, "N bookings to resolve" above zero, worked out when read so that it follows the bookings and goes
 * when the leave goes, and every booking left untouched.
 *
 * Expected results are F06-S03's AC-3 and AC-8 and its deferred entry's reason (stage.yaml). The sheet's words are F06's
 * case 036. Bookings are made through F07's endpoints. Registered under F06-S03 at F07's feature-verify.
 */
import { bookingSalon, customer, holdInput } from '../../shared/booking';
import { json } from '../../shared/identity';
import { plusDays } from '../../shared/salon-hours';
import { Absences, blockAffectedPath, blockInput, leaveAffectedPath, leaveInput } from '../../shared/team-leave';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

test('TC-F06-S03-031 AC-3, AC-8: leave and a block list the bookings they affect and count them to resolve; the count is worked out when read, following a cancellation and going with the leave; every booking is untouched', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [person] = s.members;
  const absences = new Absences(s.team);
  const d1 = plusDays(s.today, 5);
  const d2 = plusDays(s.today, 6);
  const [amaya, kavindi, nethmi] = [customer(), customer(), customer()];
  const a = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, d1, '10:00', { customer: amaya }))).id);
  const k = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, d1, '14:00', { customer: kavindi }))).id);
  const n = await s.bookings.place(s.salon, s.owner.tokens, holdInput(person.id, s.service.id, d2, '11:00', { customer: nethmi }));
  const preview = await json<{ affected: { customerName: string }[] }>(await s.team.call(s.salon, 'GET', leaveAffectedPath(person.id, d1, d1), s.owner.tokens), 'TeamPeriodImpact');
  expect(preview.affected.map(x => x.customerName).sort(), 'the preview').toEqual([amaya.name, kavindi.name].sort());
  const leave = await absences.addLeave(s.salon, s.owner.tokens, person.id, leaveInput(d1));
  expect(leave.leave.bookingsToResolve, 'two to resolve').toBe(2);
  const block = await absences.addBlock(s.salon, s.owner.tokens, person.id, blockInput(d2, { starts: '11:30', finishes: '12:30' }));
  expect(block.block.bookingsToResolve, 'the block, one').toBe(1);
  const blockPreview = await json<{ affected: { customerName: string }[] }>(await s.team.call(s.salon, 'GET', blockAffectedPath(person.id, d2, '11:30', '12:30'), s.owner.tokens), 'TeamPeriodImpact');
  expect(blockPreview.affected.map(x => x.customerName), 'its preview').toEqual([nethmi.name]);
  await s.bookings.act(s.salon, s.owner.tokens, k.id, 'cancel', { reason: 'customer_asked' });
  const read = await absences.read(s.salon, s.owner.tokens, person.id);
  expect(read.leave.find(l => l.id === leave.leave.id)!.bookingsToResolve, 'worked out when read: one after a cancellation').toBe(1);
  await absences.removeLeave(s.salon, s.owner.tokens, person.id, leave.leave.id);
  expect((await absences.read(s.salon, s.owner.tokens, person.id)).leave, 'the leave, and its count, gone').toEqual([]);
  expect((await s.bookings.get(s.salon, s.owner.tokens, a.id)).booking.state, 'untouched').toBe('confirmed');
  expect((await s.bookings.get(s.salon, s.owner.tokens, n.id)).booking.state, 'untouched').toBe('held');
});
