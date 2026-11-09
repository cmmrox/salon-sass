/**
 * F07-S01 placing a hold: what a placed hold answers and keeps, the idempotency key, every refusal of AC-2 with
 * nothing stored, and the edges a desk reaches (a booking ending at closing, a year ahead, customer notice and
 * horizon not applying to staff).
 *
 * Expected results are the F07-S01 spec's (AC-1, AC-2, AC-9, AC-10, "API and data changes"), its handoff's final
 * shapes, and decisions 084, 089, 108 and 109. Every booking is made in a salon the operator creates now
 * (`bookingSalon`), with the test clock at 08:00 in Colombo.
 */
import { randomUUID } from 'node:crypto';

import { refused } from '../../shared/booking-rules';
import {
  APP_CHANNELS, CLOSES, CUT, LIMITS, PENDING_F07_S01, REFERENCE_SHOWN, STAFF_CHANNELS, bookingAudit, bookingRows, bookingSalon, bookingSnapshot,
  comparable, customer, e164, holdInput, localAt, minutesBetween, mobileForms, national, performs, plusMinutes, refusedOnField, refusedWithDetail,
  storedBooking, storedHistory, variantService,
} from '../../shared/booking';
import { serviceInput } from '../../shared/catalogue';
import { BookingRules } from '../../shared/booking-rules';
import { Schedules, brk, weekOf } from '../../shared/team-schedule';
import { Absences, blockInput, leaveInput } from '../../shared/team-leave';
import { plusDays, plusYear } from '../../shared/salon-hours';
import { test, expect } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

// ---------------------------------------------------------------- AC-1 a hold

test('TC-F07-S01-001 AC-1, AC-9, AC-10: a hold placed by the desk answers 201 held with a reference, its times in the salon\'s zone, an expiry 10 minutes after it was placed and its channel, and keeps the terms, segments, price and rules version it was made under', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock);
  const [sanduni] = s.members;
  const who = customer();
  const date = plusDays(s.today, 3);
  const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(sanduni.id, s.service.id, date, '10:30', { customer: who, channel: 'whatsapp' }));

  expect(hold.state, 'a hold').toBe('held');
  expect(hold.reference, 'a reference shown as K7M-4QX').toMatch(REFERENCE_SHOWN);
  expect(hold.startsAt, 'the start, as an offset date-time in Colombo').toBe(localAt(date, '10:30'));
  expect(hold.endsAt, 'the end of its last segment, the buffer included').toBe(localAt(date, plusMinutes('10:30', CUT.minutes + CUT.buffer)));
  expect(hold.holdExpiresAt, 'held, so it runs out').not.toBeNull();
  expect(minutesBetween(hold.createdAt, hold.holdExpiresAt!), '10 minutes after it was placed, by the application\'s clock').toBe(LIMITS.holdMinutes);
  expect(Math.abs(Date.parse(hold.createdAt) - Date.parse(await identity.now())), 'placed now by the test clock').toBeLessThan(60_000);
  expect(hold.member, 'the professional').toEqual({ id: sanduni.id, name: sanduni.name });
  expect(hold.service, 'the service, no variant').toEqual({ id: s.service.id, name: CUT.name, variantId: null, variantName: null });
  expect(hold.customer, 'the customer, the mobile in the national format').toEqual({ name: who.name, mobile: national(who.mobile) });
  expect(hold.channel, 'where it came from').toBe('whatsapp');
  expect({ price: hold.priceMinor, currency: hold.currency }, 'the price').toEqual({ price: CUT.price, currency: 'LKR' });

  const rulesVersion = await new BookingRules(identity).settings(s.salon, s.owner.tokens);
  const stored = storedBooking(s.created.id, hold.id);
  expect(stored.reference, 'stored without the hyphen').toBe(hold.reference.replace('-', ''));
  expect(stored.service_terms_id, 'the service terms version it was made under').toBe(s.service.terms.versionId);
  expect(stored.variant_id, 'no variant').toBeNull();
  expect({ kinds: stored.segment_kinds, minutes: stored.segment_minutes }, 'a copy of the segments').toEqual({ kinds: ['active', 'buffer'], minutes: [CUT.minutes, CUT.buffer] });
  expect(Number(stored.price_minor), 'a copy of the price').toBe(CUT.price);
  expect(stored.rules_version_id, 'the booking-rules version current when placed').toBeTruthy();
  expect(rulesVersion.version, 'the salon has saved its rules once').toBe(1);
  expect(stored.customer_mobile, 'the mobile in E.164').toBe(e164(who.mobile));

  await test.step('AC-9: one history entry and one audit entry for the placing', async () => {
    const history = storedHistory(s.created.id, hold.id);
    expect(history.map(h => [h.from_state, h.to_state, h.actor_kind, h.actor_id]), 'placed by the Owner').toEqual([[null, 'held', 'staff', s.owner.principal.id]]);
    const audit = bookingAudit(s.created.id, hold.id);
    expect(audit.map(a => [a.action, a.actorKind, a.actorId]), 'audited with its actor').toEqual([[PENDING_F07_S01.audit.placed, 'staff', s.owner.principal.id]]);
    expect(audit[0].details, 'the audit never holds the customer\'s mobile').not.toContain(who.mobile.slice(1));
  });
});

test('TC-F07-S01-002 AC-1: repeating a placement with the same Idempotency-Key answers the same booking and places nothing new; the same key with another body is 422 request_rejected; no key is a 400', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock);
  const date = plusDays(s.today, 1);
  const input = holdInput(s.members[0].id, s.service.id, date, '11:00');
  const key = randomUUID();
  const first = await s.bookings.place(s.salon, s.owner.tokens, input, key);
  const rows = bookingRows(s.created.id);

  const again = await s.bookings.place(s.salon, s.owner.tokens, input, key);
  expect(again.id, 'the same booking').toBe(first.id);
  expect(again.reference, 'with the same reference').toBe(first.reference);
  expect(bookingRows(s.created.id), 'nothing new stored').toBe(rows);

  const before = bookingSnapshot(s.created.id);
  await refused(await s.bookings.placeRaw(s.salon, s.owner.tokens, { ...input, time: '14:00' }, key), 422, 'request_rejected', 'the same key, another body');
  await refusedOnField(await s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, '15:00'), null), 'Idempotency-Key', 'no key');
  expect(bookingSnapshot(s.created.id), 'nothing changed').toBe(before);
});

// ---------------------------------------------------------------- AC-2 field refusals

test('TC-F07-S01-003 AC-2: a name not 2 to 60 characters once trimmed, a mobile that is not a valid mobile of the salon\'s country, a channel staff may not send and a missing or wrong variant are each a 400 on their field that stores nothing', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock);
  const colour = await variantService(s);
  const colourToo = await variantService(s);
  const date = plusDays(s.today, 2);
  const m = s.members[0].id;
  const ok = customer();
  const rows: [string, string, unknown][] = [
    ['a name of one character', 'customer.name', holdInput(m, s.service.id, date, '10:00', { customer: { name: 'A', mobile: ok.mobile } })],
    ['a name of one character inside spaces', 'customer.name', holdInput(m, s.service.id, date, '10:00', { customer: { name: '   A   ', mobile: ok.mobile } })],
    ['a name of 61 characters', 'customer.name', holdInput(m, s.service.id, date, '10:00', { customer: { name: 'N'.repeat(LIMITS.nameMax + 1), mobile: ok.mobile } })],
    ['no name', 'customer.name', holdInput(m, s.service.id, date, '10:00', { customer: { name: '', mobile: ok.mobile } })],
    ['a landline', 'customer.mobile', holdInput(m, s.service.id, date, '10:00', { customer: { name: ok.name, mobile: '0112345678' } })],
    ['a foreign mobile', 'customer.mobile', holdInput(m, s.service.id, date, '10:00', { customer: { name: ok.name, mobile: '+447700900123' } })],
    ['too few digits', 'customer.mobile', holdInput(m, s.service.id, date, '10:00', { customer: { name: ok.name, mobile: '077123456' } })],
    ['letters', 'customer.mobile', holdInput(m, s.service.id, date, '10:00', { customer: { name: ok.name, mobile: '07712345ab' } })],
    ['no mobile', 'customer.mobile', holdInput(m, s.service.id, date, '10:00', { customer: { name: ok.name, mobile: '' } })],
    ['the customer app channel', 'channel', holdInput(m, s.service.id, date, '10:00', { channel: APP_CHANNELS[0] })],
    ['the shared app link channel', 'channel', holdInput(m, s.service.id, date, '10:00', { channel: APP_CHANNELS[1] })],
    ['a channel that is not one', 'channel', holdInput(m, s.service.id, date, '10:00', { channel: 'telegram' })],
    ['a service with variants and none named', 'variantId', holdInput(m, colour.id, date, '10:00')],
    ['another of the salon\'s services\' variant', 'variantId', holdInput(m, colour.id, date, '10:00', { variantId: colourToo.terms.variants[0].id })],
    ['a variant on a service without variants', 'variantId', holdInput(m, s.service.id, date, '10:00', { variantId: colour.terms.variants[0].id })],
  ];
  const before = bookingSnapshot(s.created.id);
  for (const [label, field, input] of rows) {
    await test.step(label, async () => {
      await refusedOnField(await s.bookings.placeRaw(s.salon, s.owner.tokens, input), field, label);
    });
  }
  expect(bookingSnapshot(s.created.id), 'nothing stored').toBe(before);
});

test('TC-F07-S01-004 AC-1, AC-2: the edges a desk types are accepted: names of 2 and 60 characters kept trimmed, a mobile in every common form stored as one number, every staff channel, and a variant\'s own time and price', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const [m] = s.members;
  const colour = await variantService(s);
  const date = plusDays(s.today, 5);
  let time = '09:00';
  const next = () => { const t = time; time = plusMinutes(time, 60); return t; };

  await test.step('names of 2 and 60 characters, trimmed', async () => {
    for (const name of ['Al', 'N'.repeat(LIMITS.nameMax)]) {
      const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, next(), { customer: { name: `  ${name}  `, mobile: customer().mobile } }));
      expect(hold.customer.name, `${name.length} characters`).toBe(name);
    }
  });
  await test.step('a mobile in every common form', async () => {
    const local = customer().mobile;
    for (const form of mobileForms(local)) {
      const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, plusDays(date, 1 + mobileForms(local).indexOf(form)), '10:00', { customer: { name: 'Kavindi Herath', mobile: form } }));
      expect(hold.customer.mobile, `${form}: the national format`).toBe(national(local));
      expect(storedBooking(s.created.id, hold.id).customer_mobile, `${form}: stored in E.164`).toBe(e164(local));
    }
  });
  await test.step('every staff channel', async () => {
    for (const channel of STAFF_CHANNELS) {
      const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, plusDays(date, 10), plusMinutes('09:00', 60 * STAFF_CHANNELS.indexOf(channel)), { channel }));
      expect(hold.channel, channel).toBe(channel);
    }
  });
  await test.step('a variant: its own time and price', async () => {
    const long = colour.terms.variants.find(v => v.name === 'Long')!;
    const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, colour.id, plusDays(date, 11), '10:00', { variantId: long.id }));
    expect(hold.service, 'the variant').toEqual({ id: colour.id, name: colour.name, variantId: long.id, variantName: 'Long' });
    expect(hold.priceMinor, 'the variant\'s price').toBe(long.priceMinor);
    expect(hold.endsAt, 'its own time and the service\'s buffer').toBe(localAt(plusDays(date, 11), plusMinutes('10:00', long.durationMinutes + colour.terms.bufferMinutes)));
    expect(storedBooking(s.created.id, hold.id).variant_id, 'the variant kept').toBe(long.id);
  });
});

// ---------------------------------------------------------------- AC-2 not found

test('TC-F07-S01-005 AC-2, AC-14: a professional, service or variant that is another salon\'s, removed, deleted or unknown is 404 not_found with the same body as an unknown id, storing nothing', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 3 });
  const other = await bookingSalon(identity, operator, clock, { keepClock: true });
  const otherColour = await variantService(other);
  const colour = await variantService(s);
  const [m, gone] = s.members;
  await s.team.remove(s.salon, s.owner.tokens, await s.team.get(s.salon, s.owner.tokens, gone.id));
  const deleted = await s.catalogue.create(s.salon, s.owner.tokens, serviceInput(s.category.id, { name: 'Fringe trim' }));
  await s.catalogue.remove(s.salon, s.owner.tokens, deleted);
  const date = plusDays(s.today, 2);

  const unknown = await s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(randomUUID(), s.service.id, date, '10:00'));
  const unknownBody = comparable(await unknown.text());
  expect(unknown.status(), `an unknown professional (${unknownBody.slice(0, 200)})`).toBe(404);
  const rows: [string, unknown][] = [
    ['another salon\'s professional', holdInput(other.members[0].id, s.service.id, date, '10:00')],
    ['a removed professional', holdInput(gone.id, s.service.id, date, '10:00')],
    ['an unknown service', holdInput(m.id, randomUUID(), date, '10:00')],
    ['another salon\'s service', holdInput(m.id, other.service.id, date, '10:00')],
    ['a deleted service', holdInput(m.id, deleted.id, date, '10:00')],
    ['an unknown variant', holdInput(m.id, colour.id, date, '10:00', { variantId: randomUUID() })],
    ['another salon\'s variant', holdInput(m.id, colour.id, date, '10:00', { variantId: otherColour.terms.variants[0].id })],
  ];
  const before = bookingSnapshot(s.created.id);
  for (const [label, input] of rows) {
    await test.step(label, async () => {
      const { answer } = await refused(await s.bookings.placeRaw(s.salon, s.owner.tokens, input), 404, 'not_found', label);
      expect(comparable(answer.body), `${label}: the same body as an unknown id`).toBe(unknownBody);
    });
  }
  expect(bookingSnapshot(s.created.id), 'nothing stored').toBe(before);
  expect(bookingRows(other.created.id), 'nothing in the other salon').toBe(0);
});

// ---------------------------------------------------------------- AC-2 slot unavailable

test('TC-F07-S01-006 AC-2: a professional who does not perform the service, a start off the 5-minute step, in the past or beyond a year, and a booking reaching outside the opening hours or the professional\'s working windows (an off day, a break, leave, a block) are each 409 slot_unavailable with a detail, storing nothing', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const [m, notCut] = s.members;
  await performs(s, notCut, []);
  const schedules = new Schedules(s.team);
  const absences = new Absences(s.team);
  const d = (n: number) => plusDays(s.today, n);
  // A week with a break from 13:00 to 14:00 every day and Sunday off, then leave and a block on chosen dates.
  await schedules.save(s.salon, s.owner.tokens, m.id, weekOf('09:00', '19:00', ['sunday'], Object.fromEntries(
    (['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'] as const).map(day => [day, { breaks: [brk('13:00', '14:00')] }]))));
  const sunday = [1, 2, 3, 4, 5, 6, 7].map(d).find(x => new Date(`${x}T00:00:00Z`).getUTCDay() === 0)!;
  const working = [10, 11, 12, 13, 14, 15, 16].map(d).filter(x => new Date(`${x}T00:00:00Z`).getUTCDay() !== 0);
  const [leaveDay, blockDay, plain] = working;
  await absences.addLeave(s.salon, s.owner.tokens, m.id, leaveInput(leaveDay));
  await absences.addBlock(s.salon, s.owner.tokens, m.id, blockInput(blockDay, { starts: '15:00', finishes: '16:00' }));
  const rows: [string, unknown][] = [
    ['a professional who does not perform it', holdInput(notCut.id, s.service.id, plain, '10:00')],
    ['a start off the 5-minute step', holdInput(m.id, s.service.id, plain, '10:03')],
    ['a start earlier today', holdInput(m.id, s.service.id, s.today, '07:30')],
    ['yesterday', holdInput(m.id, s.service.id, d(-1), '10:00')],
    ['a day beyond a year by the salon\'s date', holdInput(m.id, s.service.id, plusDays(plusYear(s.today), 1), '10:00')],
    ['before opening', holdInput(m.id, s.service.id, plain, '08:30')],
    ['ending after closing', holdInput(m.id, s.service.id, plain, plusMinutes(CLOSES, -55))],
    ['a day the professional is off', holdInput(m.id, s.service.id, sunday, '10:00')],
    ['reaching into a break', holdInput(m.id, s.service.id, plain, '12:30')],
    ['inside a break', holdInput(m.id, s.service.id, plain, '13:15')],
    ['a day of leave', holdInput(m.id, s.service.id, leaveDay, '10:00')],
    ['reaching into blocked time', holdInput(m.id, s.service.id, blockDay, '14:30')],
  ];
  const before = bookingSnapshot(s.created.id);
  for (const [label, input] of rows) {
    await test.step(label, async () => {
      await refusedWithDetail(await s.bookings.placeRaw(s.salon, s.owner.tokens, input), 'slot_unavailable', label);
    });
  }
  expect(bookingSnapshot(s.created.id), 'nothing stored').toBe(before);
});

test('TC-F07-S01-007 AC-2: staff are not bound by the customer notice or horizon (decision 108): a booking starting at the next 5-minute step, one ending exactly at closing or at a break, and one exactly a year ahead are held', async ({ identity, operator, clock }) => {
  // The rules give customers 60 minutes' notice and 60 days' horizon; the clock is 09:02, two minutes after opening.
  const s = await bookingSalon(identity, operator, clock, { people: 1, startAt: '09:02' });
  const [m] = s.members;
  const schedules = new Schedules(s.team);
  const length = CUT.minutes + CUT.buffer;
  const rows: [string, string, string][] = [
    ['the next 5-minute step, inside the customer notice', s.today, '09:05'],
    ['ending exactly at closing', s.today, plusMinutes(CLOSES, -length)],
    ['beyond the customer horizon', plusDays(s.today, 200), '10:00'],
    ['exactly a year ahead', plusYear(s.today), '10:00'],
  ];
  for (const [label, date, time] of rows) {
    await test.step(label, async () => {
      const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, date, time));
      expect(hold.startsAt, label).toBe(localAt(date, time));
    });
  }
  await test.step('ending exactly at a break, and starting exactly at its end', async () => {
    const day = plusDays(s.today, 30);
    const weekday = (['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'] as const)[new Date(`${day}T00:00:00Z`).getUTCDay()];
    await schedules.save(s.salon, s.owner.tokens, m.id, weekOf('09:00', '19:00', [], { [weekday]: { breaks: [brk('13:00', '14:00')] } }));
    await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, day, plusMinutes('13:00', -length)));
    await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, s.service.id, day, '14:00'));
  });
});

test('TC-F07-S01-008 AC-2: a salon that has never saved its booking rules refuses a hold with 409 rules_not_set, storing nothing', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { rulesSaved: false });
  expect((await s.bookingRules.settings(s.salon, s.owner.tokens)).version, 'never saved').toBe(0);
  await refused(await s.bookings.placeRaw(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, plusDays(s.today, 1), '10:00')), 409, 'rules_not_set', 'no rules');
  expect(bookingRows(s.created.id), 'nothing stored').toBe(0);
});
