/**
 * F07-S01 the booking's states: confirming and releasing a hold, a hold running out and the job that ends it, the
 * staff changes on the booking's day with their history and allowed actions, every change refused at the wrong time or
 * from the wrong state, undoing a no-show whose time was booked again, two changes at once, the BookingConfirmed event
 * and the audit trail.
 *
 * Expected results are the F07-S01 spec's (AC-5 to AC-9, AC-11 for `allowedActions` and `history`) and decisions 075,
 * 108 and 109. The clock starts at 08:00 in Colombo; a step that needs "after the start" moves it forward and signs in
 * again. The in-process payment and checkout contracts (AC-8's pending payment and completion, and AC-6's "never a hold
 * in pending payment") have no route until F08 and F10.
 */
import { refused } from '../../shared/booking-rules';
import {
  ACTIONS, PENDING_F07_S01, again, bookingAudit, bookingSalon, bookingSnapshot, customer, e164, holdInput, localAt, national,
  outboxAbout, refusedOnField, refusedWithDetail, storedBooking, storedHistory, utcOf, type Action, type BookingSalon,
} from '../../shared/booking';
import type { SignedStaff } from '../../shared/identity';
import { logOffsets, logsSince } from '../../shared/product-logs';
import { plusDays } from '../../shared/salon-hours';
import { fireTogether } from '../../shared/team';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

const A = PENDING_F07_S01.audit;

/** A confirmed booking for the first member at `time` on `date`. */
async function confirmed(s: BookingSalon, who: SignedStaff, date: string, time: string, memberIndex = 0) {
  const hold = await s.bookings.place(s.salon, who.tokens, holdInput(s.members[memberIndex].id, s.service.id, date, time));
  return s.bookings.confirm(s.salon, who.tokens, hold.id);
}

// ---------------------------------------------------------------- AC-5 confirm and release

test('TC-F07-S01-013 AC-5: a live hold is confirmed once (409 invalid_state the second time); a released hold is 204, then 404 to every call, and its time is free at once; releasing a confirmed booking is 409 invalid_state', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [m] = s.members;
  const date = plusDays(s.today, 2);
  const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, '10:00'));
  const booking = await s.bookings.confirm(s.salon, s.owner.tokens, hold.id);
  expect(booking.state, 'confirmed').toBe('confirmed');
  expect(booking.holdExpiresAt, 'no longer runs out').toBeNull();
  expect(booking.reference, 'the reference is kept').toBe(hold.reference);
  let before = bookingSnapshot(s.created.id);
  await refusedWithDetail(await s.bookings.actionRaw(s.salon, s.owner.tokens, hold.id, 'confirm'), 'invalid_state', 'confirm again');
  await refusedWithDetail(await s.bookings.actionRaw(s.salon, s.owner.tokens, hold.id, 'release'), 'invalid_state', 'release a booking');
  expect(bookingSnapshot(s.created.id), 'nothing changed').toBe(before);

  const other = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, '14:00'));
  await s.bookings.release(s.salon, s.owner.tokens, other.id);
  before = bookingSnapshot(s.created.id);
  await refused(await s.bookings.getRaw(s.salon, s.owner.tokens, other.id), 404, 'not_found', 'read a released hold');
  for (const action of ACTIONS) await refused(await s.bookings.actionRaw(s.salon, s.owner.tokens, other.id, action), 404, 'not_found', `${action} a released hold`);
  expect((await s.bookings.day(s.salon, s.owner.tokens, date)).map(b => b.id), 'not listed').toEqual([booking.id]);
  expect(bookingSnapshot(s.created.id), 'nothing changed').toBe(before);
  await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, '14:00'));
});

test('TC-F07-S01-014 AC-5, AC-6: confirming a hold after it ran out is 409 hold_expired, before the job ends it and after, and its time is free for another hold', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [m] = s.members;
  const date = plusDays(s.today, 2);
  const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, '10:00'));
  await clock.set(new Date(Date.parse(hold.holdExpiresAt!) + 1_000).toISOString());
  const owner = await again(identity, s.owner);
  await refused(await s.bookings.actionRaw(s.salon, owner.tokens, hold.id, 'confirm'), 409, 'hold_expired', 'confirm at once');
  await expect.poll(() => storedBooking(s.created.id, hold.id).state, { message: 'the job ends it', timeout: 90_000, intervals: [1_000] })
    .toBe(PENDING_F07_S01.endedHold.expired);
  await refused(await s.bookings.actionRaw(s.salon, owner.tokens, hold.id, 'confirm'), 409, 'hold_expired', 'confirm after the job');
  await s.bookings.place(s.salon, owner.tokens, holdInput(m.id, s.service.id, date, '10:00'));
});

test('TC-F07-S01-015 AC-6, AC-9: a hold that ran out is shown nowhere at once, and the job ends it by itself within a minute or so, as the system, with one history entry and one audit entry; a confirmed booking is left alone', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const date = plusDays(s.today, 2);
  const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, '10:00'));
  const kept = await confirmed(s, s.owner, date, '10:00', 1);
  await clock.set(new Date(Date.parse(hold.holdExpiresAt!) + 1_000).toISOString());
  const owner = await again(identity, s.owner);
  await refused(await s.bookings.getRaw(s.salon, owner.tokens, hold.id), 404, 'not_found', 'read a run-out hold');
  expect((await s.bookings.day(s.salon, owner.tokens, date)).map(b => b.id), 'only the booking is listed').toEqual([kept.id]);
  await expect.poll(() => storedBooking(s.created.id, hold.id).state, { message: 'the job ends it', timeout: 90_000, intervals: [1_000] })
    .toBe(PENDING_F07_S01.endedHold.expired);
  const history = storedHistory(s.created.id, hold.id);
  expect(history.map(h => [h.from_state, h.to_state, h.actor_kind, h.actor_id]), 'placed by staff, ended by the system').toEqual([
    [null, 'held', 'staff', s.owner.principal.id], ['held', PENDING_F07_S01.endedHold.expired, 'system', null]]);
  expect(Date.parse(history[1].changed_at), 'ended after it ran out, by the application\'s clock').toBeGreaterThanOrEqual(Date.parse(hold.holdExpiresAt!));
  const audit = bookingAudit(s.created.id, hold.id).map(a => [a.action, a.actorKind]);
  expect(audit, 'placed and expired').toEqual([[A.placed, 'staff'], [A.expired, 'system']]);
  expect((await s.bookings.get(s.salon, owner.tokens, kept.id)).booking.state, 'the booking is untouched').toBe('confirmed');
});

// ---------------------------------------------------------------- AC-7 the allowed changes

test('TC-F07-S01-016 AC-7, AC-9, AC-11: on the booking\'s day a confirmed booking is marked arrived before its start and then in service; each step is in its history, oldest first, by the staff account, and allowedActions says what may be done next', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, s.today, '10:00'));
  const allowed = async () => (await s.bookings.get(s.salon, s.owner.tokens, hold.id)).allowedActions;
  expect(await allowed(), 'a hold').toEqual(['confirm', 'release']);
  expect((await s.bookings.get(s.salon, s.owner.tokens, hold.id)).booking.holdExpiresAt, 'its expiry while held').toBe(hold.holdExpiresAt);
  await s.bookings.confirm(s.salon, s.owner.tokens, hold.id);
  expect(await allowed(), 'confirmed, on the day, before the start').toEqual(['arrive', 'cancel']);
  expect((await s.bookings.act(s.salon, s.owner.tokens, hold.id, 'arrive')).state).toBe('arrived');
  expect(await allowed(), 'arrived').toEqual(['start']);
  expect((await s.bookings.act(s.salon, s.owner.tokens, hold.id, 'start')).state).toBe('in_service');
  expect(await allowed(), 'in service: completed only through checkout').toEqual([]);
  const detail = await s.bookings.get(s.salon, s.owner.tokens, hold.id);
  expect(detail.history.map(h => [h.from, h.to, h.by.kind, h.by.label, h.reason, h.note]), 'every change, oldest first').toEqual([
    [null, 'held', 'staff', s.owner.account.email, null, null], ['held', 'confirmed', 'staff', s.owner.account.email, null, null],
    ['confirmed', 'arrived', 'staff', s.owner.account.email, null, null], ['arrived', 'in_service', 'staff', s.owner.account.email, null, null]]);
  const now = Date.parse(await identity.now());
  for (const h of detail.history) {
    expect(h.at, 'in the salon\'s zone').toMatch(/\+05:30$/);
    expect(Math.abs(Date.parse(h.at) - now), 'by the application\'s clock').toBeLessThan(60_000);
  }
});

test('TC-F07-S01-017 AC-7, AC-9: before its start a booking is cancelled with a reason and a note, kept in its history; at its start it is marked no-show and undone the same day back to confirmed', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  await test.step('cancel before the start', async () => {
    const booking = await confirmed(s, s.owner, plusDays(s.today, 1), '10:00');
    const note = 'Stylist called in unwell; customer to rebook';
    const cancelled = await s.bookings.act(s.salon, s.owner.tokens, booking.id, 'cancel', { reason: 'salon_unable', note });
    expect(cancelled.state).toBe('cancelled');
    const detail = await s.bookings.get(s.salon, s.owner.tokens, booking.id);
    expect(detail.history.at(-1), 'the reason and note').toMatchObject({ from: 'confirmed', to: 'cancelled', reason: 'salon_unable', note });
    expect(detail.allowedActions, 'nothing more').toEqual([]);
  });
  await test.step('no-show at the start, undone the same day', async () => {
    const booking = await confirmed(s, s.owner, s.today, '09:00');
    await clock.set(utcOf(s.today, '09:00'));
    const owner = await again(identity, s.owner);
    expect((await s.bookings.get(s.salon, owner.tokens, booking.id)).allowedActions, 'at the start').toEqual(['arrive', 'no-show']);
    expect((await s.bookings.act(s.salon, owner.tokens, booking.id, 'no-show')).state).toBe('no_show');
    expect((await s.bookings.get(s.salon, owner.tokens, booking.id)).allowedActions, 'a no-show today').toEqual(['undo-no-show']);
    await clock.set(utcOf(s.today, '23:50'));
    const late = await again(identity, s.owner);
    expect((await s.bookings.act(s.salon, late.tokens, booking.id, 'undo-no-show')).state, 'undone late the same day').toBe('confirmed');
    const history = (await s.bookings.get(s.salon, late.tokens, booking.id)).history.map(h => [h.from, h.to]);
    expect(history.slice(-2), 'both in the history').toEqual([['confirmed', 'no_show'], ['no_show', 'confirmed']]);
  });
});

// ---------------------------------------------------------------- AC-7 refused changes

test('TC-F07-S01-018 AC-7: every other change, and an allowed one at the wrong time, is 409 invalid_state with a detail naming the rule and changes nothing; a cancellation without a valid reason or with a note over 200 characters is a 400; no endpoint completes a booking or starts a payment', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 3 });
  const t = s.today;
  const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, t, '12:00'));
  const before10 = await confirmed(s, s.owner, t, '10:00', 1);
  const tomorrow = await confirmed(s, s.owner, plusDays(t, 1), '10:00', 1);
  const arrived = await confirmed(s, s.owner, t, '15:00', 2);
  await s.bookings.act(s.salon, s.owner.tokens, arrived.id, 'arrive');
  const cancelled = await confirmed(s, s.owner, t, '17:00', 2);
  await s.bookings.act(s.salon, s.owner.tokens, cancelled.id, 'cancel', { reason: 'booked_by_mistake' });
  const noShow = await confirmed(s, s.owner, t, '09:00', 2);

  const refuse = async (who: SignedStaff, label: string, id: string, action: Action, body?: unknown) => {
    await test.step(label, async () => {
      const snapshot = bookingSnapshot(s.created.id);
      await refusedWithDetail(await s.bookings.actionRaw(s.salon, who.tokens, id, action, body), 'invalid_state', label);
      expect(bookingSnapshot(s.created.id), `${label}: nothing changed`).toBe(snapshot);
    });
  };
  for (const action of ['arrive', 'start', 'cancel', 'no-show', 'undo-no-show'] as Action[]) await refuse(s.owner, `${action} a hold`, hold.id, action);
  // Released now, so the expiry job has nothing to end inside a later step's snapshot once the clock moves on.
  await s.bookings.release(s.salon, s.owner.tokens, hold.id);
  await refuse(s.owner, 'start a confirmed booking', before10.id, 'start');
  await refuse(s.owner, 'no-show before the start', before10.id, 'no-show');
  await refuse(s.owner, 'undo a no-show that is not one', before10.id, 'undo-no-show');
  await refuse(s.owner, 'arrive on another day than the booking\'s', tomorrow.id, 'arrive');
  await refuse(s.owner, 'cancel once arrived', arrived.id, 'cancel');
  await refuse(s.owner, 'no-show once arrived', arrived.id, 'no-show');
  for (const action of ['confirm', 'arrive', 'cancel', 'undo-no-show'] as Action[]) await refuse(s.owner, `${action} a cancelled booking`, cancelled.id, action);

  await test.step('the cancellation\'s body', async () => {
    const snapshot = bookingSnapshot(s.created.id);
    await refusedOnField(await s.bookings.actionRaw(s.salon, s.owner.tokens, tomorrow.id, 'cancel', {}), 'reason', 'no reason');
    await refusedOnField(await s.bookings.actionRaw(s.salon, s.owner.tokens, tomorrow.id, 'cancel', { reason: 'changed_mind' }), 'reason', 'a reason that is not one');
    await refusedOnField(await s.bookings.actionRaw(s.salon, s.owner.tokens, tomorrow.id, 'cancel', { reason: 'other', note: 'N'.repeat(201) }), 'note', 'a note of 201 characters');
    expect(bookingSnapshot(s.created.id), 'nothing changed').toBe(snapshot);
  });
  await test.step('no route reaches pending payment or completed', async () => {
    for (const path of ['complete', 'pending-payment', 'pay', 'checkout']) {
      const r = await s.bookings.call(s.salon, 'POST', `/api/v1/bookings/${arrived.id}/${path}`, s.owner.tokens);
      expect([404, 405], `${path}: no such action (${r.status()})`).toContain(r.status());
    }
    expect((await s.bookings.get(s.salon, s.owner.tokens, arrived.id)).booking.state, 'still arrived').toBe('arrived');
  });

  await clock.set(utcOf(t, '10:05'));
  const late = await again(identity, s.owner);
  await refuse(late, 'cancel after the start', before10.id, 'cancel');
  await s.bookings.act(s.salon, late.tokens, noShow.id, 'no-show');
  await clock.set(utcOf(plusDays(t, 1), '08:00'));
  await refuse(await again(identity, s.owner), 'undo a no-show the next day', noShow.id, 'undo-no-show');
});

test('TC-F07-S01-019 AC-7: undoing a no-show whose time has since been held for someone else is 409 slot_taken, and the booking stays a no-show', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const booking = await confirmed(s, s.owner, s.today, '09:00');
  await clock.set(utcOf(s.today, '09:05'));
  const owner = await again(identity, s.owner);
  await s.bookings.act(s.salon, owner.tokens, booking.id, 'no-show');
  await s.bookings.place(s.salon, owner.tokens, holdInput(s.members[0].id, s.service.id, s.today, '09:30'));
  const snapshot = bookingSnapshot(s.created.id);
  await refused(await s.bookings.actionRaw(s.salon, owner.tokens, booking.id, 'undo-no-show'), 409, 'slot_taken', 'undo');
  expect((await s.bookings.get(s.salon, owner.tokens, booking.id)).booking.state, 'still a no-show').toBe('no_show');
  expect(bookingSnapshot(s.created.id), 'nothing changed').toBe(snapshot);
});

test('TC-F07-S01-020 AC-7: of two changes sent at once to one booking exactly one applies and the other is judged against the result: two confirms of a hold, and a cancel against an arrival', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  for (const n of [0, 1, 2]) {
    await test.step(`two confirms, round ${n + 1}`, async () => {
      const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, plusDays(s.today, 1), `1${n}:00`));
      const { answers, overlapped } = await fireTogether([0, 1].map(() => () => s.bookings.actionRaw(s.salon, s.owner.tokens, hold.id, 'confirm')), 0);
      expect(overlapped, 'sent together').toBe(true);
      expect(answers.map(a => a.status()).sort(), 'one applied').toEqual([200, 409]);
      await refused(answers.find(a => a.status() === 409)!, 409, 'invalid_state', 'the second');
      expect(storedHistory(s.created.id, hold.id).map(h => h.to_state), 'confirmed once').toEqual(['held', 'confirmed']);
    });
  }
  for (const n of [0, 1, 2]) {
    await test.step(`cancel and arrive, round ${n + 1}`, async () => {
      const booking = await confirmed(s, s.owner, s.today, `1${n + 3}:00`);
      const { answers, overlapped } = await fireTogether([
        () => s.bookings.actionRaw(s.salon, s.owner.tokens, booking.id, 'cancel', { reason: 'customer_asked' }),
        () => s.bookings.actionRaw(s.salon, s.owner.tokens, booking.id, 'arrive')], 0);
      expect(overlapped, 'sent together').toBe(true);
      expect(answers.map(a => a.status()).sort(), 'one applied').toEqual([200, 409]);
      const winner = await answers.find(a => a.status() === 200)!.json();
      const states = storedHistory(s.created.id, booking.id).map(h => h.to_state);
      expect(states, 'exactly one change after confirming, the winner\'s').toEqual(['held', 'confirmed', winner.state]);
      expect(['cancelled', 'arrived'], 'a state either change leads to').toContain(winner.state);
    });
  }
});

// ---------------------------------------------------------------- AC-8 the event

test('TC-F07-S01-021 AC-8: confirming writes one BookingConfirmed event to the outbox carrying ids only, which is not dead-lettered for having no consumer; placing and releasing write none', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const who = customer();
  const date = plusDays(s.today, 2);
  const released = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, '14:00'));
  await s.bookings.release(s.salon, s.owner.tokens, released.id);
  const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, '10:00', { customer: who }));
  expect(outboxAbout(s.created.id, hold.id), 'nothing for a hold').toEqual([]);
  await s.bookings.confirm(s.salon, s.owner.tokens, hold.id);
  const events = outboxAbout(s.created.id, hold.id);
  expect(events.map(e => e.eventType), 'one event').toEqual([PENDING_F07_S01.outboxType]);
  const payload = JSON.parse(events[0].payload) as Record<string, unknown>;
  expect(payload, 'ids only').toEqual({ bookingId: hold.id, memberId: s.members[0].id, serviceId: s.service.id });
  for (const secret of [who.name, who.mobile.slice(1), hold.reference]) expect(events[0].payload, 'no customer detail').not.toContain(secret);
  expect(outboxAbout(s.created.id, released.id), 'nothing for a released hold').toEqual([]);
  await expect.poll(() => outboxAbout(s.created.id, hold.id)[0].status, { message: 'settled without a consumer', timeout: 30_000, intervals: [1_000] })
    .not.toMatch(/^(PENDING|SENDING)$/);
  expect(outboxAbout(s.created.id, hold.id)[0].status, 'not dead-lettered').not.toBe('DEAD');
});

// ---------------------------------------------------------------- AC-9 the audit trail

test('TC-F07-S01-022 AC-9: every change is in the salon\'s audit trail with its actor, time, before and after, the cancellation with its reason; the customer\'s mobile appears in no audit entry and no log line', async ({ identity, operator, clock }) => {
  const offsets = logOffsets();
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const who = customer();
  const place = (date: string, time: string) => s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, time, { customer: who }));
  const released = await place(plusDays(s.today, 1), '10:00');
  await s.bookings.release(s.salon, s.owner.tokens, released.id);
  const day = await place(s.today, '09:00');
  await s.bookings.confirm(s.salon, s.owner.tokens, day.id);
  const cancelled = await confirmed(s, s.owner, plusDays(s.today, 1), '12:00');
  await s.bookings.act(s.salon, s.owner.tokens, cancelled.id, 'cancel', { reason: 'other', note: 'Moved abroad' });
  await clock.set(utcOf(s.today, '09:05'));
  const owner = await again(identity, s.owner);
  await s.bookings.act(s.salon, owner.tokens, day.id, 'no-show');
  await s.bookings.act(s.salon, owner.tokens, day.id, 'undo-no-show');
  await s.bookings.act(s.salon, owner.tokens, day.id, 'arrive');
  await s.bookings.act(s.salon, owner.tokens, day.id, 'start');

  const expected: [string, string[]][] = [
    [released.id, [A.placed, A.released]],
    [day.id, [A.placed, A.confirmed, A.noShow, A.noShowUndone, A.arrived, A.started]],
    [cancelled.id, [A.placed, A.confirmed, A.cancelled]],
  ];
  for (const [id, actions] of expected) {
    const audit = bookingAudit(s.created.id, id);
    expect(audit.map(a => a.action), `${id}: one entry per change`).toEqual(actions);
    for (const entry of audit) {
      expect([entry.actorKind, entry.actorId], `${entry.action}: the actor`).toEqual(['staff', s.owner.principal.id]);
      const row = JSON.parse(entry.details) as { before_values: unknown; after_values: unknown; reason: string | null; created_at: string };
      expect(row.after_values, `${entry.action}: what it became`).toBeTruthy();
      if (entry.action !== A.placed) expect(row.before_values, `${entry.action}: what it was`).toBeTruthy();
      for (const form of [who.mobile, who.mobile.slice(1), e164(who.mobile), national(who.mobile)]) expect(entry.details, `${entry.action}: no mobile`).not.toContain(form);
    }
  }
  expect(bookingAudit(s.created.id, cancelled.id).at(-1)!.details, 'the reason is in the entry').toContain('other');
  const logs = logsSince(offsets);
  expect(logs.length, 'the API wrote log lines during the test').toBeGreaterThan(0);
  for (const form of [who.mobile, who.mobile.slice(1), e164(who.mobile), national(who.mobile)]) expect(logs, `no log line holds ${form.replace(/\d/g, 'n')}`).not.toContain(form);
  expect(localAt(s.today, '09:00'), 'a booking of the day').toBe(day.startsAt);
});
