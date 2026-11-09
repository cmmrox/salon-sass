/**
 * F07-S01 reading bookings: references, the day list with its filters, search and pages, the single read, and what a
 * Professional sees by their data scope.
 *
 * Expected results are the F07-S01 spec's (AC-10, AC-11, AC-12 for `booking.attend`) and its handoff (BookingPage,
 * BookingDetail; `from` and `to` both included, at most 31 dates; size 1 to 100, 50 by default; `q` at most 60
 * characters), decision 108 (d) and decision 109 (h).
 */
import { refused } from '../../shared/booking-rules';
import {
  PENDING_F07_S01, REFERENCE_ALPHABET, REFERENCE_SHOWN, again, bookingSalon, customer, holdInput, mobileForms, national, plusMinutes, refusedOnField,
  staffAt, type Booking,
} from '../../shared/booking';
import { identitySql } from '../../shared/identity-storage';
import { plusDays } from '../../shared/salon-hours';
import { test, expect, sqlLiteral } from '../../shared/operator';

test.describe.configure({ timeout: 180_000 });

const ids = (list: { id: string }[]) => list.map(b => b.id);
const byStart = (list: Booking[]) => [...list].sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt) || a.id.localeCompare(b.id));

test('TC-F07-S01-023 AC-10, AC-14: every reference is six characters of the unambiguous alphabet shown as K7M-4QX, unique within the salon by a constraint per salon, and found as shown, without its hyphen and in lower case', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 1 });
  const placed: Booking[] = [];
  for (let day = 1; day <= 4; day++) {
    for (let n = 0; n < 8; n++) placed.push(await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, plusDays(s.today, day), plusMinutes('09:00', 60 * n))));
  }
  const references = placed.map(b => b.reference);
  for (const r of references) {
    expect(r, 'shown as K7M-4QX').toMatch(REFERENCE_SHOWN);
    for (const c of r.replace('-', '')) expect(REFERENCE_ALPHABET, `${r}: no ambiguous character`).toContain(c);
  }
  expect(new Set(references).size, 'unique within the salon').toBe(references.length);
  const definition = identitySql(`SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ${sqlLiteral(PENDING_F07_S01.constraints.reference)}`);
  expect(definition, 'unique by a constraint, per salon').toBe('UNIQUE (tenant_id, reference)');
  const target = placed[17];
  for (const q of [target.reference, target.reference.replace('-', ''), target.reference.toLowerCase(), target.reference.replace('-', '').toLowerCase()]) {
    const found = await s.bookings.list(s.salon, s.owner.tokens, `from=${plusDays(s.today, 1)}&to=${plusDays(s.today, 4)}&q=${encodeURIComponent(q)}`);
    expect(ids(found.items), `${q}: found`).toEqual([target.id]);
  }
});

test('TC-F07-S01-024 AC-11: the list gives a range of local dates in start order, page by page, filtered by state, professional and a search by name, mobile in any common form or reference; ended and run-out holds are never listed; bad ranges, pages and searches are 400s on their field', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const [m1, m2] = s.members;
  const d1 = plusDays(s.today, 1);
  const d3 = plusDays(s.today, 3);
  const runOut = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m1.id, s.service.id, d1, '17:00'));
  await clock.set(new Date(Date.parse(runOut.holdExpiresAt!) + 1_000).toISOString());
  const owner = await again(identity, s.owner);
  const place = (m: string, date: string, time: string, who = customer()) => s.bookings.place(s.salon, owner.tokens, holdInput(m, s.service.id, date, time, { customer: who }));
  const amaya = customer();
  const held = await place(m1.id, d1, '10:00', amaya);
  const confirmed = await s.bookings.confirm(s.salon, owner.tokens, (await place(m2.id, d1, '10:00')).id);
  const later = await s.bookings.confirm(s.salon, owner.tokens, (await place(m1.id, d3, '09:00')).id);
  const cancelled = await place(m2.id, plusDays(s.today, 2), '11:00');
  await s.bookings.confirm(s.salon, owner.tokens, cancelled.id);
  await s.bookings.act(s.salon, owner.tokens, cancelled.id, 'cancel', { reason: 'customer_asked' });
  const released = await place(m2.id, d3, '15:00');
  await s.bookings.release(s.salon, owner.tokens, released.id);
  const outside = await place(m1.id, plusDays(s.today, 4), '10:00');
  const range = `from=${d1}&to=${d3}`;
  const all = await s.bookings.list(s.salon, owner.tokens, range);
  const expected = byStart([held, confirmed, later, { ...cancelled, state: 'cancelled' } as Booking]);
  expect(ids(all.items), 'start order, both dates included, ended and run-out holds left out').toEqual(ids(expected));
  expect(all.total, 'the total').toBe(4);
  expect(ids(all.items), 'not the day after the range').not.toContain(outside.id);
  expect(all.items.find(b => b.id === held.id)!.customer.mobile, 'the national format').toBe(national(amaya.mobile));

  await test.step('pages', async () => {
    const pages = [0, 1].map(async page => s.bookings.list(s.salon, owner.tokens, `${range}&size=3&page=${page}`));
    const [first, second] = await Promise.all(pages);
    expect([first.page, first.size, first.total, first.items.length], 'page 0').toEqual([0, 3, 4, 3]);
    expect([...ids(first.items), ...ids(second.items)], 'the pages together, in order').toEqual(ids(expected));
    expect((await s.bookings.list(s.salon, owner.tokens, range)).size, '50 by default').toBe(50);
  });
  await test.step('filters', async () => {
    expect(ids((await s.bookings.list(s.salon, owner.tokens, `${range}&state=held`)).items), 'held, no ended or run-out hold').toEqual([held.id]);
    expect(ids((await s.bookings.list(s.salon, owner.tokens, `${range}&state=held&state=cancelled`)).items).sort(), 'two states').toEqual([held.id, cancelled.id].sort());
    expect(ids((await s.bookings.list(s.salon, owner.tokens, `${range}&memberId=${m2.id}`)).items), 'one professional').toEqual(ids(byStart([confirmed, { ...cancelled } as Booking])));
  });
  await test.step('search', async () => {
    const search = async (q: string) => ids((await s.bookings.list(s.salon, owner.tokens, `${range}&q=${encodeURIComponent(q)}`)).items);
    expect(await search(amaya.name.split(' ')[1].toLowerCase()), 'part of the name, any case').toContain(held.id);
    for (const form of mobileForms(amaya.mobile)) expect(await search(form), `the mobile as ${form.replace(/\d/g, 'n')}`).toEqual([held.id]);
    expect(await search(released.reference), 'an ended hold, not even by its reference').toEqual([]);
  });
  await test.step('refused queries', async () => {
    const rows: [string, string, string][] = [
      ['32 dates', 'to', `from=${d1}&to=${plusDays(d1, 31)}`],
      ['no from', 'from', `to=${d1}`],
      ['no to', 'to', `from=${d1}`],
      ['a from that is not a date', 'from', `from=tomorrow&to=${d1}`],
      ['to before from', 'to', `from=${d3}&to=${d1}`],
      ['size 0', 'size', `${range}&size=0`],
      ['size 101', 'size', `${range}&size=101`],
      ['a negative page', 'page', `${range}&page=-1`],
      ['a state that is not one', 'state', `${range}&state=booked`],
      ['a memberId that is not an id', 'memberId', `${range}&memberId=sanduni`],
      ['a search of 61 characters', 'q', `${range}&q=${'a'.repeat(61)}`],
    ];
    for (const [label, field, query] of rows) await refusedOnField(await s.bookings.listRaw(s.salon, owner.tokens, query), field, label);
    expect((await s.bookings.list(s.salon, owner.tokens, `from=${d1}&to=${plusDays(d1, 30)}`)).total, '31 dates are allowed').toBe(5);
  });
});

test('TC-F07-S01-025 AC-11, AC-12: a Professional sees the bookings of the member linked to them and marks those arrived and in service; another\'s is 404 to read or change; unlinked they see none; with the IAM-08 grant they read the salon\'s but arriving another\'s is still 404', async ({ identity, operator, clock }) => {
  const s = await bookingSalon(identity, operator, clock, { people: 2 });
  const [sanduni, ishara] = s.members;
  const mine = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(sanduni.id, s.service.id, s.today, '10:00'))).id);
  const theirs = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(ishara.id, s.service.id, s.today, '10:00'))).id);
  const range = `from=${s.today}&to=${plusDays(s.today, 7)}`;
  const own = await staffAt(s, 'PROFESSIONAL', { linkTo: sanduni });
  const unlinked = await staffAt(s, 'PROFESSIONAL');

  await test.step('own scope', async () => {
    expect(ids((await s.bookings.list(s.salon, own.tokens, range)).items), 'only their member\'s').toEqual([mine.id]);
    expect(ids((await s.bookings.list(s.salon, own.tokens, `${range}&memberId=${ishara.id}`)).items), 'not by asking for another').toEqual([]);
    expect(ids((await s.bookings.list(s.salon, own.tokens, `${range}&q=${encodeURIComponent(theirs.reference)}`)).items), 'nor by searching').toEqual([]);
    await refused(await s.bookings.getRaw(s.salon, own.tokens, theirs.id), 404, 'not_found', 'another\'s booking');
    for (const action of ['arrive', 'start'] as const) await refused(await s.bookings.actionRaw(s.salon, own.tokens, theirs.id, action), 404, 'not_found', `${action} another\'s`);
    expect((await s.bookings.get(s.salon, own.tokens, mine.id)).allowedActions, 'their own: only arriving, no cancel').toEqual(['arrive']);
    expect((await s.bookings.act(s.salon, own.tokens, mine.id, 'arrive')).state).toBe('arrived');
    expect((await s.bookings.act(s.salon, own.tokens, mine.id, 'start')).state).toBe('in_service');
  });
  await test.step('unlinked', async () => {
    expect((await s.bookings.list(s.salon, unlinked.tokens, range)).total, 'none').toBe(0);
    await refused(await s.bookings.getRaw(s.salon, unlinked.tokens, mine.id), 404, 'not_found', 'unlinked reads one');
  });
  await test.step('granted the salon\'s calendar', async () => {
    const granted = await staffAt(s, 'PROFESSIONAL', { granted: true });
    expect(ids((await s.bookings.list(s.salon, granted.tokens, range)).items).sort(), 'the salon\'s').toEqual([mine.id, theirs.id].sort());
    const detail = await s.bookings.get(s.salon, granted.tokens, theirs.id);
    expect(detail.allowedActions, 'no action on another\'s').toEqual([]);
    await refused(await s.bookings.actionRaw(s.salon, granted.tokens, theirs.id, 'arrive'), 404, 'not_found', 'arrive another\'s (decision 109 h)');
    expect((await s.bookings.get(s.salon, s.owner.tokens, theirs.id)).booking.state, 'unchanged').toBe('confirmed');
  });
});
