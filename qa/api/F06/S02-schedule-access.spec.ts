/**
 * F06-S02 who may reach a schedule: each role at every endpoint, who receives a date change's note, a
 * support session by every route it has, suspended and read-only salons, the public list, tenant
 * isolation through the API and at the database, and the audit trail.
 *
 * Expected results are the F06-S02 spec's (AC-13, AC-14, AC-15), decisions 100 and 102 and the lead's
 * rulings for the build: a date change's note is not written to the audit trail, because a support session
 * can read the audit and must not receive notes (`qa/shared/team-schedule.ts`).
 *
 * What this cannot prove: "including through `TeamSchedule`" in AC-14, a Java interface with no route until
 * F07 (deferred case 028). Everything an outside caller can reach is here.
 */
import { randomUUID } from 'node:crypto';

import { readOnlySalon } from '../../shared/billing';
import { refused } from '../../shared/booking-rules';
import { Catalogue, comparable, serviceInput } from '../../shared/catalogue';
import { Customers } from '../../shared/customer-identity';
import { json, type Role, type Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import {
  test, expect, afterNewestPlan, onSalonHost, operatorJson, sqlLiteral, supportJson, type Operator, type SupportStart,
} from '../../shared/operator';
import { localDate, plusDays } from '../../shared/salon-hours';
import { MEMBERS, PUBLIC_TEAM, Team, auditOf, mark, memberInput, memberPath, publicKeys, teamSalon, type AuditRow } from '../../shared/team';
import {
  Schedules, auditEntriesWith, brk, changeInput, changePath, changeRoutes, changesPath, expectWeek, offChange, schedulePath, scheduleSnapshot,
  scheduleTables, weekOf, weeklyPath, type DateChange,
} from '../../shared/team-schedule';
import type { Salon } from '../../shared/tenants';
import type { Identity } from '../../shared/identity';

test.describe.configure({ timeout: 120_000 });

const COLOMBO = 'Asia/Colombo';
const AUDIT = '/api/v1/identity/audit?size=100';
const salonToday = async (identity: Identity) => localDate(await identity.now(), COLOMBO);

/** A salon with one person who has a date change carrying a note. */
async function arranged(identity: Identity, operator: Operator) {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const made = await teamSalon(operator, identity);
  const today = await salonToday(identity);
  const person = await team.add(made.salon, made.owner.tokens);
  const note = `Private note ${mark()}`;
  const { change } = await schedules.add(made.salon, made.owner.tokens, person.id, changeInput(plusDays(today, 3), { note }));
  return { ...made, team, schedules, today, person, note, change };
}

/** A support session's credential on the salon's hostname. */
async function supportTokens(operator: Operator, salonId: string, salon: Salon): Promise<Tokens> {
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, salonId, { reason: 'QA F06 support reads a schedule' }), 'supportStart', 201);
  return operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
}

const withoutNote = (change: DateChange) => { const { note: _note, ...rest } = change; return rest; };

// ---------------------------------------------------------------- AC-13 roles

test('TC-F06-S02-021 AC-13: each role at every schedule endpoint: the Owner and Manager read with the note and change; the Receptionist and Professional read with no note and are refused every change; the Accountant, a customer and a signed-out caller are refused everything; each refusal changes nothing', async ({ identity, operator }) => {
  test.setTimeout(240_000);
  const { salon, owner, created, team, schedules, today, person, note, change } = await arranged(identity, operator);
  const roles: Role[] = ['MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'];
  const actors: { role: Role; tokens: Tokens }[] = [{ role: 'OWNER', tokens: owner.tokens }];
  for (const role of roles) actors.push({ role, tokens: (await identity.staff(salon, role)).tokens });
  const freeDate = plusDays(today, 20);
  /** Every change is refused with the same code; each body would succeed, so the refusal is about who asks. */
  const changesRefused = async (label: string, status: number, code: string, tokens: Tokens | undefined) => {
    const current = await schedules.read(salon, owner.tokens, person.id);
    for (const route of changeRoutes(person.id, current, change, freeDate)) {
      const before = scheduleSnapshot(created.id);
      await refused(await team.call(salon, route.method, route.path, tokens, route.data), status, code, `${label} ${route.label}`);
      expect(scheduleSnapshot(created.id), `${label} ${route.label}: nothing changed`).toBe(before);
    }
  };
  let round = 0;
  for (const { role, tokens } of actors) {
    await test.step(role, async () => {
      if (role === 'ACCOUNTANT') {
        await refused(await schedules.readRaw(salon, tokens, person.id), 403, 'permission_denied', `${role} read`);
        await changesRefused(role, 403, 'permission_denied', tokens);
        return;
      }
      const read = await schedules.read(salon, tokens, person.id);
      const seen = read.dateChanges.find(c => c.id === change.id);
      expect(seen, `${role}: reads the change`).toBeTruthy();
      if (role === 'OWNER' || role === 'MANAGER') {
        expect(seen!.note, `${role}: receives the note`).toBe(note);
        round += 1;
        const week = weekOf('09:00', `${12 + round}:00`);
        expectWeek((await schedules.save(salon, tokens, person.id, week, read.version)).weekly, week, `${role}: saves hours`);
        const added = await schedules.add(salon, tokens, person.id, changeInput(freeDate, { note: 'Mine' }));
        expect(added.change.note, `${role}: the answer carries its note`).toBe('Mine');
        await schedules.remove(salon, tokens, person.id, added.change.id);
        return;
      }
      expect(seen, `${role}: no note at all, not an empty one`).not.toHaveProperty('note');
      const body = await (await schedules.readRaw(salon, tokens, person.id)).text();
      expect(body, `${role}: the note is nowhere in the answer`).not.toContain(note);
      expect((await team.get(salon, tokens, person.id)).weekly.length, `${role}: reads the week on the member too`).toBe(7);
      await changesRefused(role, 403, 'permission_denied', tokens);
    });
  }
  await test.step('a customer', async () => {
    const customer = await new Customers(identity).login(salon);
    await refused(await schedules.readRaw(salon, customer.tokens, person.id), 403, 'permission_denied', 'customer read');
    await changesRefused('customer', 403, 'permission_denied', customer.tokens);
  });
  await test.step('signed out', async () => {
    await refused(await schedules.readRaw(salon, undefined, person.id), 401, 'invalid_session', 'signed out read');
    await changesRefused('signed out', 401, 'invalid_session', undefined);
  });
  await team.dispose();
});

test('TC-F06-S02-022 AC-13: a support session reads a schedule as the Owner does but never receives a note by any route, the schedule, the member, the list or the audit it can read, and every change is 403 support_read_only, changing nothing', async ({ identity, operator }) => {
  const { salon, owner, created, team, schedules, today, person, note, change } = await arranged(identity, operator);
  const removedNote = `Removed note ${mark()}`;
  const removedDate = plusDays(today, 4);
  const removed = await schedules.add(salon, owner.tokens, person.id, changeInput(removedDate, { note: removedNote }));
  await schedules.remove(salon, owner.tokens, person.id, removed.change.id);
  const tokens = await supportTokens(operator, created.id, salon);
  const asOwner = await schedules.read(salon, owner.tokens, person.id);
  await test.step('it reads the schedule, with no note', async () => {
    const read = await schedules.read(salon, tokens, person.id);
    expect(read.dateChanges[0], 'no note at all').not.toHaveProperty('note');
    expect(read, 'otherwise what the Owner reads').toEqual({ ...asOwner, dateChanges: asOwner.dateChanges.map(withoutNote) });
  });
  const routes: [string, string][] = [['the schedule', schedulePath(person.id)], ['the member', memberPath(person.id)], ['the list', MEMBERS]];
  for (const [label, path] of routes) {
    await test.step(`no note in ${label}`, async () => {
      const r = await team.call(salon, 'GET', path, tokens);
      expect(r.status(), `${label}: it can read this`).toBe(200);
      const body = await r.text();
      expect(body, label).not.toContain(note);
      expect(body, label).not.toContain(removedNote);
    });
  }
  await test.step('no note in the audit it can read, or in the entries stored about the changes', async () => {
    const page = await json<{ items: unknown[] }>(await onSalonHost(identity, salon, 'GET', AUDIT, tokens), 'PageAuditView');
    const body = JSON.stringify(page.items);
    expect(page.items.length, 'there are entries to read').toBeGreaterThan(0);
    expect(body).not.toContain(note);
    expect(body).not.toContain(removedNote);
    // The list shows `identity.*` and `support.*` actions only, so the stored entries are what the check rests on.
    expect(auditEntriesWith(created.id, change.date), 'the change was audited, so there is an entry to search').toBeGreaterThan(0);
    expect(auditEntriesWith(created.id, removedDate), 'and the removed one').toBeGreaterThan(0);
    expect(auditEntriesWith(created.id, note), 'and no entry stored has its note').toBe(0);
    expect(auditEntriesWith(created.id, removedNote), 'or the removed one\'s').toBe(0);
  });
  await test.step('every change', async () => {
    for (const route of changeRoutes(person.id, asOwner, change, plusDays(today, 20))) {
      const before = scheduleSnapshot(created.id);
      await refused(await team.call(salon, route.method, route.path, tokens, route.data), 403, 'support_read_only', `support ${route.label}`);
      expect(scheduleSnapshot(created.id), `support ${route.label}: nothing changed`).toBe(before);
    }
  });
  await team.dispose();
});

test('TC-F06-S02-023 AC-13: a suspended salon answers 403 salon_suspended on every schedule endpoint, and a read-only salon reads a schedule and refuses every change with salon_read_only', async ({ identity, operator, clock }) => {
  test.setTimeout(240_000);
  await clock.set(afterNewestPlan());
  await test.step('suspended', async () => {
    const { admin, salon, owner, created, team, schedules, today, person, change } = await arranged(identity, operator);
    const current = await schedules.read(salon, owner.tokens, person.id);
    await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
    const before = scheduleSnapshot(created.id);
    await refused(await schedules.readRaw(salon, owner.tokens, person.id), 403, 'salon_suspended', 'suspended read');
    for (const route of changeRoutes(person.id, current, change, plusDays(today, 20))) {
      await refused(await team.call(salon, route.method, route.path, owner.tokens, route.data), 403, 'salon_suspended', `suspended ${route.label}`);
    }
    expect(scheduleSnapshot(created.id), 'nothing changed').toBe(before);
    await team.dispose();
  });
  await test.step('read-only', async () => {
    const team = new Team(identity);
    const schedules = new Schedules(team);
    const { admin, salon, owner, created } = await teamSalon(operator, identity);
    const person = await team.add(salon, owner.tokens);
    // Far enough ahead to still be upcoming when the salon has become read-only.
    const { change } = await schedules.add(salon, owner.tokens, person.id, offChange(plusDays(await salonToday(identity), 360), 'Still upcoming'));
    await readOnlySalon(operator, clock, admin, created.id);
    const now = await identity.login(salon, owner.account);
    const read = await schedules.read(salon, now.tokens, person.id);
    expect(read.dateChanges.map(c => c.id), 'still readable, and still upcoming').toEqual([change.id]);
    const before = scheduleSnapshot(created.id);
    for (const route of changeRoutes(person.id, read, change, plusDays(await salonToday(identity), 1))) {
      await refused(await team.call(salon, route.method, route.path, now.tokens, route.data), 403, 'salon_read_only', `read-only ${route.label}`);
    }
    expect(scheduleSnapshot(created.id), 'nothing changed').toBe(before);
    await team.dispose();
  });
});

test('TC-F06-S02-024 AC-13: nothing of a schedule appears in the public team list, for a visitor or a signed-in Owner, of a person the list does show', async ({ identity, operator }) => {
  const team = new Team(identity);
  const cat = new Catalogue(identity);
  const schedules = new Schedules(team);
  const { salon, owner } = await teamSalon(operator, identity);
  const colour = await cat.category(salon, owner.tokens, 'Colour');
  await cat.create(salon, owner.tokens, serviceInput(colour.id, { name: 'Balayage' }));
  const person = await team.add(salon, owner.tokens, memberInput({ services: { categoryIds: [colour.id], serviceIds: [] } }));
  // Times and words that appear nowhere else in the answer, so that finding one means a schedule leaked.
  await schedules.save(salon, owner.tokens, person.id, weekOf('07:15', '19:45', [], { monday: { breaks: [brk('11:45', '12:15')] } }));
  const note = `Private note ${mark()}`;
  await schedules.add(salon, owner.tokens, person.id, changeInput(plusDays(await salonToday(identity), 3), { starts: '06:45', finishes: '16:15', note }));
  const answers = [await team.publicRaw(salon.hostname), await team.call(salon, 'GET', PUBLIC_TEAM, owner.tokens)];
  for (const [n, answer] of answers.entries()) {
    const label = n === 0 ? 'a visitor' : 'a signed-in Owner';
    expect(answer.status(), label).toBe(200);
    const body = await answer.text();
    const items = (JSON.parse(body) as { items: Record<string, unknown>[] }).items;
    expect(items.map(i => i.id), `${label}: the person is listed, or this proves nothing`).toEqual([person.id]);
    expect(Object.keys(items[0]).sort(), `${label}: exactly the public fields`).toEqual(publicKeys);
    for (const word of ['weekly', 'dateChanges', 'breaks', '07:15', '19:45', '11:45', '06:45', '16:15', note]) {
      expect(body, `${label}: no trace of "${word}"`).not.toContain(word);
    }
  }
  await team.dispose();
  await cat.dispose();
});

// ---------------------------------------------------------------- AC-14 tenant isolation

test('TC-F06-S02-025 AC-14: signed in to one salon, another salon\'s person and date changes are "not found" to every read and change exactly as unknown ids are, a change is reachable only through its own person, a removed person answers the same, and another salon\'s staff reach nothing', async ({ identity, operator }) => {
  test.setTimeout(180_000);
  const a = await arranged(identity, operator);
  const b = await arranged(identity, operator);
  const { team, schedules } = a;
  const theirs = await schedules.read(b.salon, b.owner.tokens, b.person.id);
  expect(theirs.dateChanges.map(c => c.id), 'the other salon has a change to hide').toEqual([b.change.id]);
  expect(theirs.dateChanges[0].note, 'and reads its note').toBe(b.note);
  const weekBody = { version: theirs.version, weekly: weekOf('10:00', '15:00') };
  const addBody = changeInput(plusDays(a.today, 21), { note: 'Reached across' });
  const aBefore = scheduleSnapshot(a.created.id);
  const bBefore = scheduleSnapshot(b.created.id);
  await test.step('every route with their ids answers as with unknown ids', async () => {
    const unknown = randomUUID();
    const paired = [
      { label: 'read their schedule', method: 'GET', theirs: schedulePath(b.person.id), unknown: schedulePath(unknown), data: undefined as unknown },
      { label: 'save their hours', method: 'PUT', theirs: weeklyPath(b.person.id), unknown: weeklyPath(unknown), data: weekBody },
      { label: 'add a change for them', method: 'POST', theirs: changesPath(b.person.id), unknown: changesPath(unknown), data: addBody },
      { label: 'remove their change', method: 'DELETE', theirs: changePath(b.person.id, b.change.id), unknown: changePath(unknown, randomUUID()), data: undefined },
      { label: 'their change through my person', method: 'DELETE', theirs: changePath(a.person.id, b.change.id), unknown: changePath(a.person.id, randomUUID()), data: undefined },
      { label: 'my change through their person', method: 'DELETE', theirs: changePath(b.person.id, a.change.id), unknown: changePath(unknown, a.change.id), data: undefined },
    ];
    for (const route of paired) {
      const reached = await team.call(a.salon, route.method, route.theirs, a.owner.tokens, route.data);
      const none = await team.call(a.salon, route.method, route.unknown, a.owner.tokens, route.data);
      await refused(reached, 404, 'not_found', route.label);
      expect(comparable(await reached.text()), `${route.label}: the same as an unknown id`).toBe(comparable(await none.text()));
    }
  });
  await test.step('lists never show them', async () => {
    const text = await (await team.listRaw(a.salon, a.owner.tokens)).text();
    expect(text).not.toContain(b.person.id);
    const mine = await (await schedules.readRaw(a.salon, a.owner.tokens, a.person.id)).text();
    expect(mine).not.toContain(b.change.id);
    expect(mine).not.toContain(b.note);
  });
  await test.step('their Owner on this hostname', async () => {
    await refused(await schedules.readRaw(a.salon, b.owner.tokens, a.person.id), 404, 'not_found', 'their Owner: read');
    for (const route of changeRoutes(a.person.id, await schedules.read(a.salon, a.owner.tokens, a.person.id), a.change, plusDays(a.today, 22))) {
      await refused(await team.call(a.salon, route.method, route.path, b.owner.tokens, route.data), 404, 'not_found', `their Owner: ${route.label}`);
    }
  });
  expect(scheduleSnapshot(a.created.id), 'this salon changed in no way').toBe(aBefore);
  expect(scheduleSnapshot(b.created.id), 'the other salon changed in no way').toBe(bBefore);
  await test.step('a removed person answers as an unknown one', async () => {
    const gone = await team.add(a.salon, a.owner.tokens);
    const goneChange = (await schedules.add(a.salon, a.owner.tokens, gone.id, offChange(plusDays(a.today, 5)))).change;
    const goneSchedule = await schedules.read(a.salon, a.owner.tokens, gone.id);
    await team.remove(a.salon, a.owner.tokens, await team.get(a.salon, a.owner.tokens, gone.id));
    const before = scheduleSnapshot(a.created.id);
    await refused(await schedules.readRaw(a.salon, a.owner.tokens, gone.id), 404, 'not_found', 'removed: read');
    for (const route of changeRoutes(gone.id, goneSchedule, goneChange, plusDays(a.today, 23))) {
      await refused(await team.call(a.salon, route.method, route.path, a.owner.tokens, route.data), 404, 'not_found', `removed: ${route.label}`);
    }
    expect(scheduleSnapshot(a.created.id), 'nothing saved for someone who has left').toBe(before);
  });
  await team.dispose();
});

test('TC-F06-S02-026 AC-14: with no tenant context every schedule table returns no rows to the application role, a salon\'s context shows only its own, every row is on the salon\'s default branch, and reads for two salons sent together never cross', async ({ identity, operator }) => {
  const a = await arranged(identity, operator);
  const b = await arranged(identity, operator);
  for (const x of [a, b]) {
    await x.schedules.save(x.salon, x.owner.tokens, x.person.id, weekOf('09:00', '18:00', ['sunday'], { monday: { breaks: [brk('13:00', '14:00')] } }));
    await x.schedules.add(x.salon, x.owner.tokens, x.person.id, changeInput(plusDays(x.today, 6), { breaks: [brk('12:00', '12:30')] }));
  }
  const rowsOf = (table: string, salonId: string) => Number(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id = ${sqlLiteral(salonId)}`));
  for (const table of scheduleTables()) {
    await test.step(table, async () => {
      const aRows = rowsOf(table, a.created.id);
      expect(aRows, `${table}: the salon has rows`).toBeGreaterThan(0);
      expect(rowsOf(table, b.created.id), `${table}: the other salon has rows`).toBeGreaterThan(0);
      expect(identitySql(`SELECT count(*) FROM ${table}`, null), `${table}: no tenant, no rows`).toBe('0');
      expect(Number(identitySql(`SELECT count(*) FROM ${table}`, a.created.id)), `${table}: the salon sees its rows`).toBe(aRows);
      expect(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id <> ${sqlLiteral(a.created.id)}`, a.created.id), `${table}: and no other salon's`).toBe('0');
      const offBranch = identitySql(`SELECT count(*) FROM ${table} x WHERE tenant_id = ${sqlLiteral(a.created.id)}
        AND branch_id IS DISTINCT FROM (SELECT id FROM branding_branch b WHERE b.tenant_id = x.tenant_id AND b.is_default)`);
      expect(offBranch, `${table}: every row carries the default branch`).toBe('0');
    });
  }
  await test.step('reads for two salons sent together', async () => {
    const order = Array.from({ length: 16 }, (_, n) => (n % 2 === 0 ? a : b));
    const answers = await Promise.all(order.map(x => x.schedules.readRaw(x.salon, x.owner.tokens, x.person.id)));
    for (const [n, answer] of answers.entries()) {
      expect(answer.status(), `request ${n}`).toBe(200);
      const text = await answer.text();
      const other = order[n] === a ? b : a;
      expect(text, `request ${n}: its own note`).toContain(order[n].note);
      expect(text, `request ${n}: nothing of the other salon`).not.toContain(other.note);
      expect(text).not.toContain(other.change.id);
    }
  });
  await a.team.dispose();
  await b.team.dispose();
});

// ---------------------------------------------------------------- AC-15 audit

test('TC-F06-S02-027 AC-15: every save of weekly hours, naming the weekdays it changed, and every date change added or removed is audited with the actor, the time and the before and after values; a refused change is not, and no entry carries a note', async ({ identity, operator }) => {
  const team = new Team(identity);
  const schedules = new Schedules(team);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const manager = await identity.staff(salon, 'MANAGER');
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Audit Person' }));
  const today = await salonToday(identity);
  const first = weekOf('09:00', '18:00', ['sunday']);
  await schedules.save(salon, owner.tokens, person.id, first);
  const t0 = await identity.now();
  let seen = auditOf(created.id, null, t0).length;
  /** Runs a change and returns the details it wrote: at least one new entry, by the actor, inside the change's time. */
  const audited = async (label: string, actorId: string, change: () => Promise<unknown>) => {
    const from = await identity.now();
    await change();
    const to = await identity.now();
    const rows: AuditRow[] = auditOf(created.id, null, t0).slice(seen);
    seen += rows.length;
    expect(rows.length, `${label}: audited`).toBeGreaterThan(0);
    for (const row of rows) {
      expect(row.actorId, `${label}: the actor`).toBe(actorId);
      expect(Date.parse(row.at)).toBeGreaterThanOrEqual(Date.parse(from) - 1000);
      expect(Date.parse(row.at)).toBeLessThanOrEqual(Date.parse(to) + 1000);
    }
    return rows.map(r => r.details).join('\n');
  };
  await test.step('a save names the weekdays it changed, with before and after', async () => {
    const changed = first.map(d => (d.day === 'tuesday' ? { ...d, starts: '10:15', finishes: '16:45' } : d.day === 'sunday' ? { ...d, working: true, starts: '11:30', finishes: '13:30' } : d));
    const details = await audited('a save', owner.principal.id, () => schedules.save(salon, owner.tokens, person.id, changed));
    expect(details, 'its action').toContain('staff.team_member_hours_saved');
    expect(details, 'names Tuesday').toMatch(/tuesday/i);
    expect(details, 'names Sunday').toMatch(/sunday/i);
    for (const unchanged of ['monday', 'wednesday', 'thursday', 'friday', 'saturday']) expect(details, `does not name ${unchanged}, which did not change`).not.toMatch(new RegExp(unchanged, 'i'));
    expect(details, 'Tuesday before').toContain('09:00');
    expect(details, 'Tuesday after').toContain('10:15');
    expect(details, 'Sunday after').toContain('11:30');
  });
  const note = `Private note ${mark()}`;
  const date = plusDays(today, 9);
  let change!: DateChange;
  await test.step('adding a change, with what it holds and without its note', async () => {
    const details = await audited('add', owner.principal.id, async () => { change = (await schedules.add(salon, owner.tokens, person.id, changeInput(date, { starts: '10:45', finishes: '15:15', note }))).change; });
    expect(details, 'its action').toContain('staff.team_member_date_change_added');
    expect(details, 'the date').toContain(date);
    expect(details, 'after').toContain('10:45');
    expect(details, 'no note').not.toContain(note);
    expect(details, 'only that there is one').toMatch(/hasNote\\*"?\s*:\s*true/);
  });
  await test.step('removing it, by another actor, with what it held and without its note', async () => {
    const details = await audited('remove', manager.principal.id, () => schedules.remove(salon, manager.tokens, person.id, change.id));
    expect(details, 'its action').toContain('staff.team_member_date_change_removed');
    expect(details, 'the date').toContain(date);
    expect(details, 'before').toContain('10:45');
    expect(details, 'no note').not.toContain(note);
  });
  await test.step('a refused change is not audited', async () => {
    const current = await schedules.read(salon, owner.tokens, person.id);
    await refused(await schedules.saveRaw(salon, owner.tokens, person.id, current.version - 1, weekOf('11:00', '15:00')), 409, 'conflict', 'stale');
    await refused(await schedules.saveRaw(salon, owner.tokens, person.id, current.version, current.weekly), 409, 'invalid_state', 'no change');
    expect(auditOf(created.id, null, t0).length, 'a refused save: nothing audited').toBe(seen);
    await audited('the first on a date', owner.principal.id, () => schedules.add(salon, owner.tokens, person.id, changeInput(plusDays(today, 12))));
    await refused(await schedules.addRaw(salon, owner.tokens, person.id, changeInput(plusDays(today, 12))), 409, 'conflict', 'a second on a date');
    await refused(await schedules.removeRaw(salon, owner.tokens, person.id, randomUUID()), 404, 'not_found', 'unknown');
    expect(auditOf(created.id, null, t0).length, 'a refused change: nothing audited').toBe(seen);
  });
  expect(auditEntriesWith(created.id, note), 'the note is in no entry stored').toBe(0);
  await team.dispose();
});
