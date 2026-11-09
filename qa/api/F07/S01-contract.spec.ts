/**
 * F07-S01 the contract: every booking endpoint's answers, its documented errors included, are what the committed
 * OpenAPI document says for that operation and status, and the four new problem codes are in it.
 *
 * Expected results are the F07-S01 spec's AC-15. Each answer is checked against the response the document declares for
 * that operation and status (its media type and schema), not against a schema picked by the test. 503 and `default`
 * cannot be provoked from outside without stopping the database, which F01's resilience checks already do.
 */
import { randomUUID } from 'node:crypto';

import { ACTIONS, BOOKINGS, again, bookingSalon, holdInput, staffAt, utcOf } from '../../shared/booking';
import { Seen, problemCodes } from '../../shared/contract';
import { plusDays } from '../../shared/salon-hours';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

test('TC-F07-S01-036 AC-15: every booking endpoint answers each of its successes and documented errors exactly as the committed contract declares, and slot_taken, slot_unavailable, hold_expired and rules_not_set are its problem codes', async ({ identity, operator, clock }) => {
  const codes = problemCodes();
  for (const code of ['slot_taken', 'slot_unavailable', 'hold_expired', 'rules_not_set']) expect(codes, `${code} is a problem code`).toContain(code);

  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const bare = await bookingSalon(identity, operator, clock, { people: 1, rulesSaved: false, keepClock: true });
  const accountant = await staffAt(s, 'ACCOUNTANT');
  const m = s.members[0].id;
  const d = plusDays(s.today, 2);
  const seen = new Seen();
  const check = seen.check.bind(seen);
  const one = '/api/v1/bookings/{id}';
  const act = (a: string) => `/api/v1/bookings/{id}/${a}`;

  // placing
  const hold = await s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(m, s.service.id, d, '10:00'));
  const held = await hold.json();
  await check(hold, 'POST', BOOKINGS, '201 held');
  await check(s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(m, s.service.id, d, '10:00', { channel: 'telegram' })), 'POST', BOOKINGS, '400');
  await check(s.bookings.placeRaw(s.salon, undefined, holdInput(m, s.service.id, d, '12:00')), 'POST', BOOKINGS, '401');
  await check(s.bookings.placeRaw(s.salon, accountant.tokens, holdInput(m, s.service.id, d, '12:00')), 'POST', BOOKINGS, '403');
  await check(s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(randomUUID(), s.service.id, d, '12:00')), 'POST', BOOKINGS, '404');
  await check(s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(m, s.service.id, d, '10:30')), 'POST', BOOKINGS, '409 slot_taken');
  await check(s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(m, s.service.id, d, '10:03')), 'POST', BOOKINGS, '409 slot_unavailable');
  await check(bare.bookings.placeRaw(bare.salon, bare.owner.tokens, holdInput(bare.members[0].id, bare.service.id, d, '10:00')), 'POST', BOOKINGS, '409 rules_not_set');
  const key = randomUUID();
  await s.bookings.place(s.salon, s.owner.tokens, holdInput(m, s.service.id, d, '14:00'), key);
  await check(s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(m, s.service.id, d, '15:00'), key), 'POST', BOOKINGS, '422');

  // reading
  const list = `from=${s.today}&to=${d}`;
  await check(s.bookings.listRaw(s.salon, s.owner.tokens, list), 'GET', BOOKINGS, '200 list');
  await check(s.bookings.listRaw(s.salon, s.owner.tokens, `from=${s.today}&to=${plusDays(s.today, 40)}`), 'GET', BOOKINGS, '400 list');
  await check(s.bookings.listRaw(s.salon, undefined, list), 'GET', BOOKINGS, '401 list');
  await check(s.bookings.listRaw(s.salon, accountant.tokens, list), 'GET', BOOKINGS, '403 list');
  await check(s.bookings.getRaw(s.salon, s.owner.tokens, held.id), 'GET', one, '200 read');
  await check(s.bookings.getRaw(s.salon, undefined, held.id), 'GET', one, '401 read');
  await check(s.bookings.getRaw(s.salon, accountant.tokens, held.id), 'GET', one, '403 read');
  await check(s.bookings.getRaw(s.salon, s.owner.tokens, randomUUID()), 'GET', one, '404 read');

  // every action: its success, 401, 403, 404 and 409 invalid_state; the cancel body; hold_expired
  const day = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m, s.service.id, s.today, '09:00'));
  const toRelease = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m, s.service.id, d, '17:00'));
  for (const action of ACTIONS) {
    await check(s.bookings.actionRaw(s.salon, undefined, held.id, action), 'POST', act(action), `401 ${action}`);
    await check(s.bookings.actionRaw(s.salon, accountant.tokens, held.id, action), 'POST', act(action), `403 ${action}`);
    await check(s.bookings.actionRaw(s.salon, s.owner.tokens, randomUUID(), action), 'POST', act(action), `404 ${action}`);
  }
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, held.id, 'arrive'), 'POST', act('arrive'), '409 arrive a hold');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, held.id, 'start'), 'POST', act('start'), '409 start a hold');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, held.id, 'cancel'), 'POST', act('cancel'), '409 cancel a hold');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, held.id, 'no-show'), 'POST', act('no-show'), '409 no-show a hold');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, held.id, 'undo-no-show'), 'POST', act('undo-no-show'), '409 undo a hold');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, toRelease.id, 'release'), 'POST', act('release'), '204 release');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, held.id, 'confirm'), 'POST', act('confirm'), '200 confirm');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, held.id, 'confirm'), 'POST', act('confirm'), '409 confirm again');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, held.id, 'release'), 'POST', act('release'), '409 release a booking');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, held.id, 'cancel', { reason: 'nope' }), 'POST', act('cancel'), '400 cancel');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, held.id, 'cancel', { reason: 'other', note: 'Rebooking next week' }), 'POST', act('cancel'), '200 cancel');
  await s.bookings.confirm(s.salon, s.owner.tokens, day.id);
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, day.id, 'arrive'), 'POST', act('arrive'), '200 arrive');
  await check(s.bookings.actionRaw(s.salon, s.owner.tokens, day.id, 'start'), 'POST', act('start'), '200 start');
  const nine = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(m, s.service.id, plusDays(s.today, 1), '09:00'))).id);
  const late = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m, s.service.id, plusDays(s.today, 1), '13:00'));
  await clock.set(utcOf(plusDays(s.today, 1), '09:05'));
  const owner = await again(identity, s.owner);
  await check(s.bookings.actionRaw(s.salon, owner.tokens, late.id, 'confirm'), 'POST', act('confirm'), '409 hold_expired');
  await check(s.bookings.actionRaw(s.salon, owner.tokens, nine.id, 'no-show'), 'POST', act('no-show'), '200 no-show');
  await check(s.bookings.actionRaw(s.salon, owner.tokens, nine.id, 'undo-no-show'), 'POST', act('undo-no-show'), '200 undo-no-show');

  // Every success and documented 4xx of every operation was seen at least once, but the list's 404, which no salon host
  // reaches with a valid credential.
  seen.expectAllSeen([['POST', BOOKINGS], ['GET', BOOKINGS], ['GET', one], ...ACTIONS.map(a => ['POST', act(a)] as [string, string])],
    { [`GET ${BOOKINGS}`]: ['404'] });
});
