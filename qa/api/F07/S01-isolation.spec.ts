/**
 * F07-S01 one salon can never see or change another's bookings: through every endpoint and the search, at the
 * database as the application's role, and with requests for two salons sent together.
 *
 * Expected results are the F07-S01 spec's AC-14 and docs/architecture/multi-tenancy.md, "Tests you must write": another
 * salon's id answers as an unknown one; with no tenant context the new tables return no rows. Every check first proves
 * the other salon really has bookings to see. Another salon's professional, service and variant on a placement are in
 * TC-F07-S01-005.
 */
import { randomUUID } from 'node:crypto';

import { refused } from '../../shared/booking-rules';
import {
  ACTIONS, PENDING_F07_S01, bookingSalon, bookingSnapshot, comparable, customer, holdInput, type BookingSalon,
} from '../../shared/booking';
import { identitySql } from '../../shared/identity-storage';
import { plusDays } from '../../shared/salon-hours';
import { fireTogether } from '../../shared/team';
import { test, expect, sqlLiteral, type Operator, type TestClock } from '../../shared/operator';
import type { Identity } from '../../shared/identity';

test.describe.configure({ timeout: 180_000 });

/** Two salons, each with a hold and a confirmed booking on the same dates and times. */
async function twoSalons(identity: Identity, operator: Operator, clock: TestClock) {
  const a = await bookingSalon(identity, operator, clock, { people: 1 });
  const b = await bookingSalon(identity, operator, clock, { people: 1, keepClock: true });
  const date = plusDays(a.today, 2);
  const make = async (s: BookingSalon) => {
    const who = customer();
    const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, '10:00', { customer: who }));
    const booking = await s.bookings.confirm(s.salon, s.owner.tokens, (await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, '12:00'))).id);
    return { who, hold, booking };
  };
  return { a, b, date, inA: await make(a), inB: await make(b) };
}

test('TC-F07-S01-029 AC-14: signed in to one salon, the other salon\'s bookings answer every read and every action as an unknown id, and its list and search show none of them, changing nothing', async ({ identity, operator, clock }) => {
  const { a, b, date, inA, inB } = await twoSalons(identity, operator, clock);
  const range = `from=${date}&to=${date}`;
  expect((await b.bookings.list(b.salon, b.owner.tokens, range)).total, 'the other salon has its two to see').toBe(2);
  expect((await b.bookings.get(b.salon, b.owner.tokens, inB.hold.id)).booking.state, 'and reads its hold').toBe('held');
  const before = bookingSnapshot(b.created.id);
  const unknown = randomUUID();
  const unknownRead = comparable(await (await a.bookings.getRaw(a.salon, a.owner.tokens, unknown)).text());
  for (const id of [inB.hold.id, inB.booking.id]) {
    const { answer } = await refused(await a.bookings.getRaw(a.salon, a.owner.tokens, id), 404, 'not_found', 'read the other\'s');
    expect(comparable(answer.body), 'read: the same answer as an unknown id').toBe(unknownRead);
    for (const action of ACTIONS) {
      const unknownAnswer = comparable(await (await a.bookings.actionRaw(a.salon, a.owner.tokens, unknown, action)).text());
      const { answer: theirs } = await refused(await a.bookings.actionRaw(a.salon, a.owner.tokens, id, action), 404, 'not_found', `${action} the other\'s`);
      expect(comparable(theirs.body), `${action}: the same answer as an unknown id`).toBe(unknownAnswer);
    }
  }
  const mine = await a.bookings.list(a.salon, a.owner.tokens, range);
  expect(mine.items.map(x => x.id).sort(), 'its own list holds only its own').toEqual([inA.hold.id, inA.booking.id].sort());
  for (const q of [inB.hold.reference, inB.booking.reference.replace('-', ''), inB.who.mobile, inB.who.name]) {
    expect((await a.bookings.list(a.salon, a.owner.tokens, `${range}&q=${encodeURIComponent(q)}`)).items.filter(x => [inB.hold.id, inB.booking.id].includes(x.id)), `no search finds the other's (${q.replace(/\d/g, 'n')})`).toEqual([]);
  }
  expect(bookingSnapshot(b.created.id), 'the other salon\'s bookings are unchanged').toBe(before);
  expect((await b.bookings.get(b.salon, b.owner.tokens, inB.hold.id)).booking.state, 'its hold is still held').toBe('held');
});

test('TC-F07-S01-030 AC-14: at the database, the application\'s role with one salon\'s context sees only that salon\'s bookings and history, and with no context none; the overlap and reference constraints are per salon', async ({ identity, operator, clock }) => {
  const { a, b } = await twoSalons(identity, operator, clock);
  const T = PENDING_F07_S01.tables;
  for (const table of [T.booking, T.history]) {
    const owned = (salon: string) => Number(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id = ${sqlLiteral(salon)}`));
    expect(owned(b.created.id), `${table}: the other salon has rows`).toBeGreaterThan(0);
    expect(Number(identitySql(`SELECT count(*) FROM ${table}`, a.created.id)), `${table}: one salon's context sees its own rows only`).toBe(owned(a.created.id));
    expect(Number(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id = ${sqlLiteral(b.created.id)}`, a.created.id)), `${table}: none of the other's`).toBe(0);
    expect(Number(identitySql(`SELECT count(*) FROM ${table}`, null)), `${table}: no context sees nothing`).toBe(0);
  }
  const definition = (name: string) => identitySql(`SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ${sqlLiteral(name)}`);
  expect(definition(PENDING_F07_S01.constraints.overlap), 'the overlap constraint is per salon and professional').toMatch(/^EXCLUDE USING gist \(tenant_id WITH =, member_id WITH =, occupied WITH &&\)/);
  expect(definition(PENDING_F07_S01.constraints.reference), 'the reference is unique per salon').toBe('UNIQUE (tenant_id, reference)');
});

test('TC-F07-S01-031 AC-14: twenty list and read requests for two salons sent together each answer only their own salon\'s bookings', async ({ identity, operator, clock }) => {
  const { a, b, date, inA, inB } = await twoSalons(identity, operator, clock);
  const range = `from=${date}&to=${date}`;
  const own = { [a.created.id]: [inA.hold.id, inA.booking.id].sort(), [b.created.id]: [inB.hold.id, inB.booking.id].sort() };
  const sends = Array.from({ length: 20 }, (_, n) => {
    const s = n % 2 ? b : a;
    const mine = n % 2 ? inB : inA;
    return n % 4 < 2
      ? async () => ({ salon: s.created.id, ids: (await s.bookings.list(s.salon, s.owner.tokens, range)).items.map(x => x.id).sort() })
      : async () => ({ salon: s.created.id, ids: [(await s.bookings.get(s.salon, s.owner.tokens, mine.booking.id)).booking.id] });
  });
  const { answers, overlapped } = await fireTogether(sends, 0);
  expect(overlapped, 'sent together').toBe(true);
  for (const answer of answers) {
    const expected = own[answer.salon];
    if (answer.ids.length === 1) expect(expected, 'a read answers its own salon\'s booking').toContain(answer.ids[0]);
    else expect(answer.ids, 'a list answers its own salon\'s bookings').toEqual(expected);
  }
});
