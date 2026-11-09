/**
 * F07-S01 one professional, one time: overlapping holds and bookings refused with slot_taken, touching ones kept,
 * the buffer occupying the professional, what occupies nothing (released, cancelled, no-show, run out), and many
 * holds fired at once for one professional, and for ten.
 *
 * Expected results are the F07-S01 spec's (AC-3, AC-4, "Risks and test focus") and its handoff ("OVERLAP
 * MECHANISM": the pre-insert checks never look at other bookings, so every slot_taken comes from the exclusion
 * constraint). A race asserts the invariant read back from the database, not only the answers, and that the requests
 * really overlapped. AC-4's processing segment exists only from F17 (decision 074); no endpoint can make one in R1.
 */
import { refused } from '../../shared/booking-rules';
import {
  CUT, PENDING_F07_S01, again, bookingRows, bookingSalon, holdInput, liveRowsOf, localAt, plusMinutes, storedBooking, utcOf,
} from '../../shared/booking';
import { plusDays } from '../../shared/salon-hours';
import { fireTogether } from '../../shared/team';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

const LENGTH = CUT.minutes + CUT.buffer;

test('TC-F07-S01-009 AC-3, AC-4: a hold overlapping a live hold or booking of the same professional, by a minute or by its buffer, is 409 slot_taken whatever the live state; touching ones and another professional at the same time are kept', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const [m, other] = s.members;
  const date = plusDays(s.today, 4);
  const held = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, '10:00'));
  const confirmed = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, '14:00'))).id);
  expect(confirmed.state).toBe('confirmed');
  // Arrived and in service happen on the booking's own day.
  const arrived = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, s.today, '11:00'));
  await s.bookings.confirm(s.salon, s.owner.tokens, arrived.id);
  await s.bookings.act(s.salon, s.owner.tokens, arrived.id, 'arrive');
  const inService = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, s.today, '15:00'));
  await s.bookings.confirm(s.salon, s.owner.tokens, inService.id);
  await s.bookings.act(s.salon, s.owner.tokens, inService.id, 'arrive');
  expect((await s.bookings.act(s.salon, s.owner.tokens, inService.id, 'start')).state).toBe('in_service');

  const refusedRows: [string, string, string][] = [
    ['the same start as a hold', date, '10:00'],
    ['starting inside a hold', date, '10:30'],
    ['ending inside a hold', date, '09:05'],
    ['starting in a hold\'s buffer (AC-4)', date, plusMinutes('10:00', CUT.minutes + 5)],
    ['around a hold', date, '09:55'],
    ['over a confirmed booking', date, '14:20'],
    ['over an arrived booking', s.today, '11:30'],
    ['over a booking in service', s.today, '14:30'],
  ];
  const rows = bookingRows(s.created.id);
  for (const [label, day, time] of refusedRows) {
    await test.step(label, async () => {
      await refused(await s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, day, time)), 409, 'slot_taken', label);
    });
  }
  expect(bookingRows(s.created.id), 'nothing stored by the refusals').toBe(rows);
  await test.step('touching: ending as a hold starts, and starting as its buffer ends', async () => {
    await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, plusMinutes('10:00', -LENGTH)));
    await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, plusMinutes('10:00', LENGTH)));
  });
  await test.step('another professional at the same time', async () => {
    await s.bookings.place(s.salon, s.owner.tokens, holdInput(other.id, s.service.id, date, '10:00'));
  });
  expect(liveRowsOf(s.created.id, m.id).map(r => r.id), 'the held one is still there').toContain(held.id);
});

test('TC-F07-S01-010 AC-3, AC-5, AC-7: released holds, cancelled bookings, no-shows and holds that ran out occupy nothing: the same time is held again at once, a run-out hold even before the job clears it', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [m] = s.members;
  const date = plusDays(s.today, 3);
  const place = (day: string, time: string) => s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, day, time));

  await test.step('released', async () => {
    const hold = await place(date, '10:00');
    await s.bookings.release(s.salon, s.owner.tokens, hold.id);
    await place(date, '10:00');
  });
  await test.step('cancelled', async () => {
    const booking = await place(date, '12:00');
    await s.bookings.confirm(s.salon, s.owner.tokens, booking.id);
    await s.bookings.act(s.salon, s.owner.tokens, booking.id, 'cancel', { reason: 'customer_asked' });
    await place(date, '12:00');
  });
  await test.step('a no-show: the rest of its time is held again', async () => {
    const booking = await place(s.today, '09:00');
    await s.bookings.confirm(s.salon, s.owner.tokens, booking.id);
    await clock.set(utcOf(s.today, '09:05'));
    const owner = await again(identity, s.owner);
    await s.bookings.act(s.salon, owner.tokens, booking.id, 'no-show');
    await s.bookings.place(s.salon, owner.tokens, holdInput(m.id, s.service.id, s.today, '09:10'));
  });
  await test.step('a hold that ran out', async () => {
    const owner = await again(identity, s.owner);
    const hold = await s.bookings.place(s.salon, owner.tokens, holdInput(m.id, s.service.id, date, '15:00'));
    await clock.set(new Date(Date.parse(hold.holdExpiresAt!) + 1_000).toISOString());
    const now = await again(identity, s.owner);
    const stateBefore = storedBooking(s.created.id, hold.id).state;
    const next = await s.bookings.place(s.salon, now.tokens, holdInput(m.id, s.service.id, date, '15:00'));
    expect(next.state, 'held again').toBe('held');
    test.info().annotations.push({ type: 'run-out hold before the new one', description: `stored state '${stateBefore}' (held: the job had not cleared it)` });
    expect([PENDING_F07_S01.endedHold.expired], 'the run-out hold has ended').toContain(storedBooking(s.created.id, hold.id).state);
  });
});

test('TC-F07-S01-011 AC-3: of twenty simultaneous holds for overlapping times of one professional exactly one is held and nineteen are 409 slot_taken, and only that one is stored', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [m] = s.members;
  const date = plusDays(s.today, 6);
  // Every pair overlaps: eight at 10:00 and twelve from 10:00 to 10:55, all inside one booking's 60 minutes of another.
  const times = [...Array(8).fill('10:00'), ...Array.from({ length: 12 }, (_, n) => plusMinutes('10:00', 5 * n))];
  const { answers, overlapped } = await fireTogether(times.map(time => () => s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, time))), 0);
  expect(overlapped, 'every request was sent before any was answered').toBe(true);
  const statuses = answers.map(a => a.status());
  expect(statuses.filter(x => x === 201).length, `exactly one held (${statuses.join(',')})`).toBe(1);
  for (const answer of answers.filter(a => a.status() !== 201)) await refused(answer, 409, 'slot_taken', 'a loser');
  const winner = await answers.find(a => a.status() === 201)!.json();
  expect(liveRowsOf(s.created.id, m.id).map(r => r.id), 'one live row, the winner').toEqual([winner.id]);
  expect(bookingRows(s.created.id), 'nothing else stored').toBe(1);
});

test('TC-F07-S01-012 AC-3: ten simultaneous holds at one time for ten different professionals are all held', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 10 });
  const date = plusDays(s.today, 6);
  const { answers, overlapped } = await fireTogether(s.members.map(m => () => s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, '10:00'))), 0);
  expect(overlapped, 'every request was sent before any was answered').toBe(true);
  expect(answers.map(a => a.status()), 'all held').toEqual(Array(10).fill(201));
  const held = await Promise.all(answers.map(a => a.json()));
  expect(new Set(held.map(b => b.member.id)).size, 'one each').toBe(10);
  for (const b of held) expect(b.startsAt).toBe(localAt(date, '10:00'));
});
