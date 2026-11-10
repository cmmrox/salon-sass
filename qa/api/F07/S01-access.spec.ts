/**
 * F07-S01 who may do what: each role at every booking endpoint, signed out, a support session, and a suspended and a
 * read-only salon. A refused call changes nothing.
 *
 * Expected results are the F07-S01 spec's AC-12 and decision 108 (b): `booking.manage` (Owner, Manager, Receptionist)
 * places, confirms, releases, cancels, marks no-show and undoes it; `booking.attend` (those three and the Professional)
 * marks arrived and starts service, a Professional on their own bookings only; an Accountant gets 403
 * permission_denied everywhere; a support session reads and gets 403 support_read_only from every change; suspended
 * and read-only salons answer as the catalogue endpoints do (403 salon_suspended on everything, 403 salon_read_only on
 * changes), as F06's access cases prove for the team endpoints.
 */
import { randomUUID } from 'node:crypto';

import type { APIResponse } from '@playwright/test';

import { readOnlySalon } from '../../shared/billing';
import { refused } from '../../shared/booking-rules';
import {
  ACTIONS, BOOKINGS, again, bookingPath, bookingSalon, bookingSnapshot, holdInput, staffAt, storedBooking, utcOf, type Booking, type BookingSalon,
} from '../../shared/booking';
import type { SignedStaff, Tokens } from '../../shared/identity';
import { plusDays } from '../../shared/salon-hours';
import { test, expect, operatorJson, supportJson, type Operator, type SupportStart } from '../../shared/operator';

test.describe.configure({ timeout: 240_000 });

/** Every booking endpoint with a body that would succeed, for refusals that must not depend on the state. */
function routes(s: BookingSalon, booking: Booking) {
  const date = plusDays(s.today, 9);
  const list = `${BOOKINGS}?from=${s.today}&to=${plusDays(s.today, 10)}`;
  return [
    { label: 'list', method: 'GET', path: list, data: undefined as unknown },
    { label: 'read', method: 'GET', path: bookingPath(booking.id), data: undefined as unknown },
    { label: 'place', method: 'POST', path: BOOKINGS, data: holdInput(s.members[0].id, s.service.id, date, '16:00') as unknown },
    ...ACTIONS.map(action => ({ label: action, method: 'POST', path: `${bookingPath(booking.id)}/${action}`, data: (action === 'cancel' ? { reason: 'customer_asked' } : undefined) as unknown })),
  ];
}
const send = (s: BookingSalon, route: ReturnType<typeof routes>[number], tokens?: Tokens): Promise<APIResponse> =>
  s.bookings.call(s.salon, route.method, route.path, tokens, route.data, route.path === BOOKINGS && route.method === 'POST' ? { 'Idempotency-Key': randomUUID() } : {});

async function supportTokens(operator: Operator, s: BookingSalon): Promise<Tokens> {
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, s.created.id, { reason: 'The salon asked about a booking that did not show' }), 'supportStart', 201);
  return operator.supportCredential(await operator.exchange(session.entryCode, s.salon.hostname));
}

test('TC-F07-S01-026 AC-12: the Owner, a Manager and a Receptionist each place, confirm, release, cancel, mark arrived, start, mark no-show and undo it; a Professional only reads, arrives and starts their own; an Accountant is 403 permission_denied everywhere; signed out is 401; no refusal changes anything', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 4 });
  const managers: [string, SignedStaff][] = [['the Owner', s.owner], ['a Manager', await staffAt(s, 'MANAGER')], ['a Receptionist', await staffAt(s, 'RECEPTIONIST')]];
  const professional = await staffAt(s, 'PROFESSIONAL', { linkTo: s.members[3] });
  const accountant = await staffAt(s, 'ACCOUNTANT');
  const tomorrow = plusDays(s.today, 1);
  const refuse = async (label: string, answer: Promise<APIResponse>, status: number, code: string) => {
    const before = bookingSnapshot(s.created.id);
    await refused(await answer, status, code, label);
    expect(bookingSnapshot(s.created.id), `${label}: nothing changed`).toBe(before);
  };

  const noShowLater: [string, SignedStaff, Booking][] = [];
  for (const [n, [who, actor]] of managers.entries()) {
    await test.step(`${who}: every booking action`, async () => {
      const m = s.members[n].id;
      const day = await s.bookings.place(s.salon, actor.tokens, holdInput(m, s.service.id, s.today, '10:00'));
      expect((await s.bookings.confirm(s.salon, actor.tokens, day.id)).state).toBe('confirmed');
      expect((await s.bookings.act(s.salon, actor.tokens, day.id, 'arrive')).state).toBe('arrived');
      expect((await s.bookings.act(s.salon, actor.tokens, day.id, 'start')).state).toBe('in_service');
      await s.bookings.release(s.salon, actor.tokens, (await s.bookings.place(s.salon, actor.tokens, holdInput(m, s.service.id, tomorrow, '10:00'))).id);
      const later = await s.bookings.confirm(s.salon, actor.tokens, (await s.bookings.place(s.salon, actor.tokens, holdInput(m, s.service.id, tomorrow, '12:00'))).id);
      expect((await s.bookings.act(s.salon, actor.tokens, later.id, 'cancel', { reason: 'customer_asked' })).state).toBe('cancelled');
      expect((await s.bookings.list(s.salon, actor.tokens, `from=${s.today}&to=${tomorrow}`)).total, 'reads the salon\'s').toBeGreaterThan(0);
      noShowLater.push([who, actor, await s.bookings.confirm(s.salon, actor.tokens, (await s.bookings.place(s.salon, actor.tokens, holdInput(m, s.service.id, s.today, '09:00'))).id)]);
    });
  }
  const p = s.members[3].id;
  const pDay = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(p, s.service.id, s.today, '11:00'))).id);
  const pHold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(p, s.service.id, tomorrow, '10:00'));
  const pLater = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(p, s.service.id, tomorrow, '12:00'))).id);
  const pNine = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(p, s.service.id, s.today, '09:00'))).id);

  await test.step('a Professional, on their own bookings', async () => {
    await refuse('place', s.bookings.placeRaw(s.salon, professional.tokens, holdInput(p, s.service.id, tomorrow, '16:00')), 403, 'permission_denied');
    await refuse('confirm', s.bookings.actionRaw(s.salon, professional.tokens, pHold.id, 'confirm'), 403, 'permission_denied');
    await refuse('release', s.bookings.actionRaw(s.salon, professional.tokens, pHold.id, 'release'), 403, 'permission_denied');
    await refuse('cancel', s.bookings.actionRaw(s.salon, professional.tokens, pLater.id, 'cancel'), 403, 'permission_denied');
    expect((await s.bookings.get(s.salon, professional.tokens, pDay.id)).booking.id, 'reads their own').toBe(pDay.id);
    expect((await s.bookings.act(s.salon, professional.tokens, pDay.id, 'arrive')).state).toBe('arrived');
    expect((await s.bookings.act(s.salon, professional.tokens, pDay.id, 'start')).state).toBe('in_service');
  });
  await test.step('an Accountant, everywhere', async () => {
    for (const route of routes(s, pLater)) await refuse(`Accountant ${route.label}`, send(s, route, accountant.tokens), 403, 'permission_denied');
  });
  await test.step('signed out, everywhere', async () => {
    for (const route of routes(s, pLater)) await refuse(`signed out ${route.label}`, send(s, route), 401, 'invalid_session');
  });

  await clock.set(utcOf(s.today, '09:05'));
  // The clock now passes the Professional's hold's expiry (08:10), and the hold-expiry job, which runs as the clock moves,
  // ends it (AC-6). Settle that before the refusals below compare what is stored, as docs/qa/test-plan.md asks of a test
  // that moves the clock: the job's change is not the refusal's.
  await expect.poll(() => storedBooking(s.created.id, pHold.id).state, { message: 'the job has ended the run-out hold', timeout: 90_000 }).toBe('expired');
  for (const [who, actor, booking] of noShowLater) {
    await test.step(`${who}: no-show and undo`, async () => {
      const now = await again(identity, actor);
      expect((await s.bookings.act(s.salon, now.tokens, booking.id, 'no-show')).state).toBe('no_show');
      expect((await s.bookings.act(s.salon, now.tokens, booking.id, 'undo-no-show')).state).toBe('confirmed');
    });
  }
  await test.step('a Professional cannot mark no-show or undo it', async () => {
    const now = await again(identity, professional);
    await refuse('no-show', s.bookings.actionRaw(s.salon, now.tokens, pNine.id, 'no-show'), 403, 'permission_denied');
    await s.bookings.act(s.salon, (await again(identity, s.owner)).tokens, pNine.id, 'no-show');
    await refuse('undo-no-show', s.bookings.actionRaw(s.salon, now.tokens, pNine.id, 'undo-no-show'), 403, 'permission_denied');
  });
});

test('TC-F07-S01-027 AC-12: a support session reads the list and a booking, with no allowed action, and every change, placing included, is 403 support_read_only changing nothing', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const booking = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, plusDays(s.today, 1), '10:00'))).id);
  const tokens = await supportTokens(operator, s);
  expect((await s.bookings.list(s.salon, tokens, `from=${s.today}&to=${plusDays(s.today, 2)}`)).items.map(b => b.id), 'the list').toEqual([booking.id]);
  const detail = await s.bookings.get(s.salon, tokens, booking.id);
  expect(detail.allowedActions, 'nothing it may do').toEqual([]);
  for (const route of routes(s, booking).filter(r => r.method !== 'GET')) {
    const before = bookingSnapshot(s.created.id);
    await refused(await send(s, route, tokens), 403, 'support_read_only', `support ${route.label}`);
    expect(bookingSnapshot(s.created.id), `support ${route.label}: nothing changed`).toBe(before);
  }
});

test('TC-F07-S01-028 AC-12: a suspended salon answers 403 salon_suspended at every booking endpoint, and a read-only salon reads its bookings and refuses every change with 403 salon_read_only, changing nothing', async ({ identity, operator, clock }) => {
  await test.step('suspended', async () => {
    const s = await bookingSalon(identity, operator, clock, { people: 1 });
    const booking = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, plusDays(s.today, 1), '10:00'));
    await operatorJson(await operator.suspend(s.admin, s.created.id, await operator.version(s.admin, s.created.id)), 'salon');
    const before = bookingSnapshot(s.created.id);
    for (const route of routes(s, booking)) await refused(await send(s, route, s.owner.tokens), 403, 'salon_suspended', `suspended ${route.label}`);
    expect(bookingSnapshot(s.created.id), 'nothing changed').toBe(before);
  });
  await test.step('read-only', async () => {
    const s = await bookingSalon(identity, operator, clock, { people: 1, keepClock: true });
    const far = plusDays(s.today, 300);
    const booking = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, far, '10:00'))).id);
    await readOnlySalon(operator, clock, s.admin, s.created.id);
    const owner = await again(identity, s.owner);
    expect((await s.bookings.get(s.salon, owner.tokens, booking.id)).booking.state, 'still readable').toBe('confirmed');
    expect((await s.bookings.list(s.salon, owner.tokens, `from=${far}&to=${far}`)).total, 'and listed').toBe(1);
    const before = bookingSnapshot(s.created.id);
    const changes = routes(s, booking).filter(r => r.method !== 'GET').map(r => r.label === 'place' ? { ...r, data: holdInput(s.members[0].id, s.service.id, plusDays(far, 1), '10:00') } : r);
    for (const route of changes) await refused(await send(s, route, owner.tokens), 403, 'salon_read_only', `read-only ${route.label}`);
    expect(bookingSnapshot(s.created.id), 'nothing changed').toBe(before);
  });
});

