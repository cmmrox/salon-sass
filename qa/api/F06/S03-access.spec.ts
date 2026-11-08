/**
 * F06-S03 who may reach leave and blocked time. Privacy is what this stage can get wrong: a leave's type and note,
 * sick leave above all, reach nobody without `team.manage` by any route: the schedule, the member and the member
 * list, the audit rows and the public list. Also each role at every endpoint, `team.block`, a support session,
 * suspended and read-only salons, tenant isolation through the API and at the database, and the audit trail.
 *
 * Expected results are the F06-S03 spec's (AC-9 to AC-12, "API and data changes") and decision 104. Every privacy
 * check is held against a control: the Owner receives the words the others must not, in the same answer shape. What
 * a browser receives is in qa/web/F06/S03-leave-and-block-screens.spec.ts.
 *
 * The audit list shows `identity.*` and `support.*` actions only, so the stored rows are what the audit checks rest
 * on (the lead's ruling): the rule must hold when the list widens.
 *
 * What this cannot prove: "including through `TeamSchedule`" in AC-11, a Java interface with no route until F07
 * (deferred case 030).
 */
import { randomUUID } from 'node:crypto';

import { readOnlySalon } from '../../shared/billing';
import { refused } from '../../shared/booking-rules';
import { Catalogue, comparable, serviceInput } from '../../shared/catalogue';
import { Customers } from '../../shared/customer-identity';
import { json, type Identity, type Role, type Tokens } from '../../shared/identity';
import { identitySql } from '../../shared/identity-storage';
import {
  test, expect, afterNewestPlan, onSalonHost, operatorJson, sqlLiteral, supportJson, type Operator, type SupportStart,
} from '../../shared/operator';
import { plusDays } from '../../shared/salon-hours';
import { MEMBERS, PUBLIC_TEAM, Team, auditOf, mark, memberInput, memberPath, publicKeys, teamSalon, type AuditRow } from '../../shared/team';
import {
  Absences, LEAVE_KEYS_WITHOUT_MANAGE, NEXT_LEAVE_KEYS_WITHOUT_MANAGE, PENDING_F06_S03, absenceRoutes, absenceSalon, absenceSnapshot, absenceTables, auditEntriesLike, blockAffectedPath, blockInput, blocksPath,
  leaveAffectedPath, leaveInput, leavePath, oneBlockPath, oneLeavePath, salonDate, type Block, type Leave, type MemberWithLeave,
} from '../../shared/team-leave';
import { schedulePath } from '../../shared/team-schedule';
import type { Salon } from '../../shared/tenants';

test.describe.configure({ timeout: 120_000 });

const AUDIT = '/api/v1/identity/audit?size=100';
const DAY = 24 * 60 * 60 * 1000;

/** A salon with one person on sick leave with a private note from today, and a block. */
async function sickPerson(identity: Identity, operator: Operator) {
  const team = new Team(identity);
  const absences = new Absences(team);
  const made = await teamSalon(operator, identity);
  const today = await salonDate(identity);
  const person = await team.add(made.salon, made.owner.tokens);
  const note = `Private note ${mark()}`;
  const label = `Blocked label ${mark()}`;
  const leave = (await absences.addLeave(made.salon, made.owner.tokens, person.id, leaveInput(today, { lastDay: plusDays(today, 2), type: 'sick', note }))).leave;
  const block = (await absences.addBlock(made.salon, made.owner.tokens, person.id, blockInput(plusDays(today, 5), { label }))).block;
  return { ...made, team, absences, today, person, note, label, leave, block, free: plusDays(today, 20) };
}

async function supportTokens(operator: Operator, salonId: string, salon: Salon): Promise<Tokens> {
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, salonId, { reason: 'QA F06 support reads leave' }), 'supportStart', 201);
  return operator.supportCredential(await operator.exchange(session.entryCode, salon.hostname));
}

const readRoutes = (memberId: string) => [['the schedule', schedulePath(memberId)], ['the member', memberPath(memberId)], ['the member list', MEMBERS]] as const;

// ---------------------------------------------------------------- AC-9, AC-10 privacy

test('TC-F06-S03-021 AC-9, AC-10: sick leave\'s type and note reach only the Owner and Manager: a Receptionist, a Professional and a support session receive only its days, from the schedule, the member and the member list', async ({ identity, operator }) => {
  const { salon, owner, created, team, absences, person, note, leave } = await sickPerson(identity, operator);
  await test.step('the control: the Owner and a Manager do receive them, in the same answers', async () => {
    const manager = await identity.staff(salon, 'MANAGER');
    for (const [who, tokens] of [['the Owner', owner.tokens], ['a Manager', manager.tokens]] as const) {
      expect((await absences.read(salon, tokens, person.id)).leave[0], `${who}: the schedule`).toMatchObject({ id: leave.id, type: 'sick', note });
      const member = await team.get(salon, tokens, person.id) as MemberWithLeave;
      expect(member.nextLeave, `${who}: the member`).toEqual({ firstDay: leave.firstDay, lastDay: leave.lastDay, type: 'sick' });
    }
  });
  const receptionist = await identity.staff(salon, 'RECEPTIONIST');
  const professional = await identity.staff(salon, 'PROFESSIONAL');
  const readers: [string, Tokens][] = [['a Receptionist', receptionist.tokens], ['a Professional', professional.tokens], ['a support session', await supportTokens(operator, created.id, salon)]];
  for (const [who, tokens] of readers) {
    await test.step(who, async () => {
      const read = await absences.read(salon, tokens, person.id);
      expect(Object.keys(read.leave[0]).sort(), `${who}: exactly the days of the leave`).toEqual(LEAVE_KEYS_WITHOUT_MANAGE);
      expect(read.leave[0]).toMatchObject({ id: leave.id, firstDay: leave.firstDay, lastDay: leave.lastDay });
      const member = await team.get(salon, tokens, person.id) as MemberWithLeave;
      expect(Object.keys(member.nextLeave ?? {}).sort(), `${who}: the member's next leave, its days alone`).toEqual(NEXT_LEAVE_KEYS_WITHOUT_MANAGE);
      const listed = (await team.list(salon, tokens)).find(m => m.id === person.id) as MemberWithLeave;
      expect(Object.keys(listed.nextLeave ?? {}).sort(), `${who}: the list's next leave, its days alone`).toEqual(NEXT_LEAVE_KEYS_WITHOUT_MANAGE);
      for (const [route, path] of readRoutes(person.id)) {
        const answer = await team.call(salon, 'GET', path, tokens);
        expect(answer.status(), `${who}: ${route}`).toBe(200);
        const body = await answer.text();
        expect(body, `${who}, ${route}: the note is nowhere`).not.toContain(note);
        expect(body, `${who}, ${route}: the type is nowhere`).not.toMatch(/sick/i);
        expect(body, `${who}, ${route}: no type field`).not.toContain('"type"');
      }
    });
  }
  await team.dispose();
});

test('TC-F06-S03-022 AC-9: a member\'s next leave is their current leave, else the next, null with none, and it moves on when one ends or is removed', async ({ identity, operator, clock }) => {
  const { today, team, absences, salon, owner, person } = await absenceSalon(identity, operator, clock);
  const nextOf = async (tokens: Tokens) => (await team.get(salon, tokens, person.id) as MemberWithLeave).nextLeave;
  expect(await nextOf(owner.tokens), 'none yet').toBeNull();
  const later = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 10), { type: 'personal' }))).leave;
  expect(await nextOf(owner.tokens), 'the next').toEqual({ firstDay: later.firstDay, lastDay: later.lastDay, type: 'personal' });
  const current = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(today, { lastDay: plusDays(today, 2), type: 'sick' }))).leave;
  expect(await nextOf(owner.tokens), 'one sooner takes its place').toMatchObject({ firstDay: current.firstDay });
  await clock.advance(DAY);
  const after = (await identity.login(salon, owner.account)).tokens;
  expect(await nextOf(after), 'under way, it is still the current one').toMatchObject({ firstDay: current.firstDay, lastDay: current.lastDay });
  await absences.removeLeave(salon, after, person.id, current.id);
  expect(await nextOf(after), 'removed: on to the next').toMatchObject({ firstDay: later.firstDay });
  await clock.advance(10 * DAY);
  const last = (await identity.login(salon, owner.account)).tokens;
  expect(await nextOf(last), 'its last day has ended: none').toBeNull();
  await team.dispose();
});

// ---------------------------------------------------------------- AC-10 roles

test('TC-F06-S03-023 AC-10: each role at every endpoint: the Owner and Manager do all of it; a Receptionist adds and removes blocks but not leave; a Professional does neither; an Accountant, a customer and a signed-out caller are refused everything; each refusal changes nothing', async ({ identity, operator }) => {
  test.setTimeout(240_000);
  const { salon, owner, created, team, absences, person, leave, block, free } = await sickPerson(identity, operator);
  const roles: Role[] = ['MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'];
  const actors: { role: Role; tokens: Tokens }[] = [{ role: 'OWNER', tokens: owner.tokens }];
  for (const role of roles) actors.push({ role, tokens: (await identity.staff(salon, role)).tokens });
  const routes = absenceRoutes(person.id, leave, block, free);
  const allRefused = async (label: string, list: typeof routes.all, status: number, code: string, tokens: Tokens | undefined) => {
    for (const route of list) {
      const before = absenceSnapshot(created.id);
      await refused(await team.call(salon, route.method, route.path, tokens, route.data), status, code, `${label} ${route.label}`);
      expect(absenceSnapshot(created.id), `${label} ${route.label}: nothing changed`).toBe(before);
    }
  };
  /** Adds and removes its own, so that no actor uses up what the next is refused on. */
  const managesLeave = async (tokens: Tokens, label: string) => {
    const mine = (await absences.addLeave(salon, tokens, person.id, leaveInput(free))).leave;
    const preview = await team.call(salon, 'GET', leaveAffectedPath(person.id, free, free), tokens);
    expect(preview.status(), `${label}: previews leave`).toBe(200);
    await absences.removeLeave(salon, tokens, person.id, mine.id);
  };
  const managesBlocks = async (tokens: Tokens, label: string) => {
    const mine = (await absences.addBlock(salon, tokens, person.id, blockInput(free))).block;
    const preview = await team.call(salon, 'GET', blockAffectedPath(person.id, free, '10:00', '11:00'), tokens);
    expect(preview.status(), `${label}: previews a block`).toBe(200);
    await absences.removeBlock(salon, tokens, person.id, mine.id);
  };
  for (const { role, tokens } of actors) {
    await test.step(role, async () => {
      if (role === 'ACCOUNTANT') {
        await refused(await team.call(salon, 'GET', schedulePath(person.id), tokens), 403, 'permission_denied', 'read');
        await allRefused(role, routes.all, 403, 'permission_denied', tokens);
        return;
      }
      expect((await absences.read(salon, tokens, person.id)).blockedTime.map(b => b.id), `${role}: reads the blocks`).toEqual([block.id]);
      if (role === 'OWNER' || role === 'MANAGER') { await managesLeave(tokens, role); await managesBlocks(tokens, role); return; }
      await allRefused(role, routes.leaveRoutes, 403, 'permission_denied', tokens);
      if (role === 'RECEPTIONIST') await managesBlocks(tokens, role);
      else await allRefused(role, routes.blockRoutes, 403, 'permission_denied', tokens);
    });
  }
  await test.step('a customer', async () => {
    const customer = await new Customers(identity).login(salon);
    await refused(await team.call(salon, 'GET', schedulePath(person.id), customer.tokens), 403, 'permission_denied', 'read');
    await allRefused('customer', routes.all, 403, 'permission_denied', customer.tokens);
  });
  await test.step('signed out', async () => {
    await refused(await team.call(salon, 'GET', schedulePath(person.id), undefined), 401, 'invalid_session', 'read');
    await allRefused('signed out', routes.all, 401, 'invalid_session', undefined);
  });
  await team.dispose();
});

test('TC-F06-S03-024 AC-10: a support session reads leave\'s days and the blocks and is refused every change with 403 support_read_only and both previews, changing nothing', async ({ identity, operator }) => {
  const { salon, owner, created, team, absences, person, label, leave, block, free } = await sickPerson(identity, operator);
  const tokens = await supportTokens(operator, created.id, salon);
  const read = await absences.read(salon, tokens, person.id);
  expect(read.leave.map(l => l.id), 'the leave').toEqual([leave.id]);
  expect(read.blockedTime.map(b => b.label), 'the block, with its label').toEqual([label]);
  const routes = absenceRoutes(person.id, leave, block, free);
  for (const route of routes.all) {
    const before = absenceSnapshot(created.id);
    const answer = await team.call(salon, route.method, route.path, tokens, route.data);
    if (route.method === 'GET') {
      expect(answer.status(), `support ${route.label}: refused`).toBe(403);
    } else {
      await refused(answer, 403, 'support_read_only', `support ${route.label}`);
    }
    expect(absenceSnapshot(created.id), `support ${route.label}: nothing changed`).toBe(before);
  }
  expect((await absences.read(salon, owner.tokens, person.id)).leave.map(l => l.id), 'still there').toEqual([leave.id]);
  await team.dispose();
});

test('TC-F06-S03-025 AC-10: a suspended salon answers 403 salon_suspended on every endpoint here, and a read-only salon reads and refuses every change with salon_read_only', async ({ identity, operator, clock }) => {
  test.setTimeout(240_000);
  await clock.set(afterNewestPlan());
  await test.step('suspended', async () => {
    const { admin, salon, owner, created, team, person, leave, block, free } = await sickPerson(identity, operator);
    await operatorJson(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 'salon');
    const before = absenceSnapshot(created.id);
    await refused(await team.call(salon, 'GET', schedulePath(person.id), owner.tokens), 403, 'salon_suspended', 'suspended read');
    for (const route of absenceRoutes(person.id, leave, block, free).all) await refused(await team.call(salon, route.method, route.path, owner.tokens, route.data), 403, 'salon_suspended', `suspended ${route.label}`);
    expect(absenceSnapshot(created.id), 'nothing changed').toBe(before);
    await team.dispose();
  });
  await test.step('read-only', async () => {
    const team = new Team(identity);
    const absences = new Absences(team);
    const { admin, salon, owner, created } = await teamSalon(operator, identity);
    const person = await team.add(salon, owner.tokens);
    const ahead = plusDays(await salonDate(identity), 360);
    const leave = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(ahead))).leave;
    const block = (await absences.addBlock(salon, owner.tokens, person.id, blockInput(ahead))).block;
    await readOnlySalon(operator, clock, admin, created.id);
    const now = await identity.login(salon, owner.account);
    const read = await absences.read(salon, now.tokens, person.id);
    expect(read.leave.map(l => l.id), 'still readable, and still upcoming').toEqual([leave.id]);
    const before = absenceSnapshot(created.id);
    const changes = absenceRoutes(person.id, leave, block, plusDays(await salonDate(identity), 1)).all.filter(r => r.method !== 'GET');
    for (const route of changes) await refused(await team.call(salon, route.method, route.path, now.tokens, route.data), 403, 'salon_read_only', `read-only ${route.label}`);
    expect(absenceSnapshot(created.id), 'nothing changed').toBe(before);
    await team.dispose();
  });
});

test('TC-F06-S03-026 AC-10: neither leave nor blocks appear in the public team list, for a visitor or a signed-in Owner, of a person the list does show', async ({ identity, operator }) => {
  const { salon, owner, team, absences, person, note, label } = await sickPerson(identity, operator);
  const cat = new Catalogue(identity);
  const colour = await cat.category(salon, owner.tokens, 'Colour');
  await cat.create(salon, owner.tokens, serviceInput(colour.id, { name: 'Balayage' }));
  await team.save(salon, owner.tokens, await team.get(salon, owner.tokens, person.id), { services: { categoryIds: [colour.id], serviceIds: [] } });
  await absences.addBlock(salon, owner.tokens, person.id, blockInput(plusDays(await salonDate(identity), 7), { starts: '06:45', finishes: '07:15', label: `${label} second` }));
  const answers = [await team.publicRaw(salon.hostname), await team.call(salon, 'GET', PUBLIC_TEAM, owner.tokens)];
  for (const [n, answer] of answers.entries()) {
    const who = n === 0 ? 'a visitor' : 'a signed-in Owner';
    expect(answer.status(), who).toBe(200);
    const body = await answer.text();
    const items = (JSON.parse(body) as { items: Record<string, unknown>[] }).items;
    expect(items.map(i => i.id), `${who}: the person is listed, or this proves nothing`).toEqual([person.id]);
    expect(Object.keys(items[0]).sort(), `${who}: exactly the public fields`).toEqual(publicKeys);
    for (const word of ['nextLeave', 'leave', 'blockedTime', 'sick', '06:45', note, label]) expect(body.toLowerCase(), `${who}: no trace of "${word}"`).not.toContain(word.toLowerCase());
  }
  await team.dispose();
  await cat.dispose();
});

// ---------------------------------------------------------------- AC-11 tenant isolation

test('TC-F06-S03-027 AC-11: signed in to one salon, another salon\'s person, leave and blocks are "not found" to every read and change exactly as unknown ids are, each is reachable only through its own person, a removed person answers the same, and another salon\'s staff reach nothing', async ({ identity, operator }) => {
  test.setTimeout(180_000);
  const a = await sickPerson(identity, operator);
  const b = await sickPerson(identity, operator);
  const { team } = a;
  const theirs = await b.absences.read(b.salon, b.owner.tokens, b.person.id);
  expect([theirs.leave.length, theirs.blockedTime.length], 'the other salon has a leave and a block to hide').toEqual([1, 1]);
  const aBefore = absenceSnapshot(a.created.id);
  const bBefore = absenceSnapshot(b.created.id);
  await test.step('every route with their ids answers as with unknown ids', async () => {
    const unknown = randomUUID();
    const free = a.free;
    const paired = [
      { label: 'read their schedule', method: 'GET', theirs: schedulePath(b.person.id), none: schedulePath(unknown), data: undefined as unknown },
      { label: 'add leave for them', method: 'POST', theirs: leavePath(b.person.id), none: leavePath(unknown), data: leaveInput(free) },
      { label: 'remove their leave', method: 'DELETE', theirs: oneLeavePath(b.person.id, b.leave.id), none: oneLeavePath(unknown, randomUUID()), data: undefined },
      { label: 'preview their leave', method: 'GET', theirs: leaveAffectedPath(b.person.id, free, free), none: leaveAffectedPath(unknown, free, free), data: undefined },
      { label: 'add a block for them', method: 'POST', theirs: blocksPath(b.person.id), none: blocksPath(unknown), data: blockInput(free) },
      { label: 'remove their block', method: 'DELETE', theirs: oneBlockPath(b.person.id, b.block.id), none: oneBlockPath(unknown, randomUUID()), data: undefined },
      { label: 'preview their block', method: 'GET', theirs: blockAffectedPath(b.person.id, free, '10:00', '11:00'), none: blockAffectedPath(unknown, free, '10:00', '11:00'), data: undefined },
      { label: 'their leave through my person', method: 'DELETE', theirs: oneLeavePath(a.person.id, b.leave.id), none: oneLeavePath(a.person.id, randomUUID()), data: undefined },
      { label: 'their block through my person', method: 'DELETE', theirs: oneBlockPath(a.person.id, b.block.id), none: oneBlockPath(a.person.id, randomUUID()), data: undefined },
    ];
    for (const route of paired) {
      const reached = await team.call(a.salon, route.method, route.theirs, a.owner.tokens, route.data);
      const none = await team.call(a.salon, route.method, route.none, a.owner.tokens, route.data);
      await refused(reached, 404, 'not_found', route.label);
      expect(comparable(await reached.text()), `${route.label}: the same as an unknown id`).toBe(comparable(await none.text()));
    }
  });
  await test.step('lists never show them', async () => {
    const list = await (await team.listRaw(a.salon, a.owner.tokens)).text();
    expect(list).not.toContain(b.person.id);
    const mine = await (await team.call(a.salon, 'GET', schedulePath(a.person.id), a.owner.tokens)).text();
    for (const secret of [b.leave.id, b.block.id, b.note, b.label]) expect(mine).not.toContain(secret);
  });
  await test.step('their Owner on this hostname', async () => {
    await refused(await team.call(a.salon, 'GET', schedulePath(a.person.id), b.owner.tokens), 404, 'not_found', 'their Owner: read');
    for (const route of absenceRoutes(a.person.id, a.leave, a.block, a.free).all) await refused(await team.call(a.salon, route.method, route.path, b.owner.tokens, route.data), 404, 'not_found', `their Owner: ${route.label}`);
  });
  expect(absenceSnapshot(a.created.id), 'this salon changed in no way').toBe(aBefore);
  expect(absenceSnapshot(b.created.id), 'the other salon changed in no way').toBe(bBefore);
  await test.step('a removed person answers as an unknown one, though their rows stay', async () => {
    const gone = await team.add(a.salon, a.owner.tokens);
    const goneLeave = (await a.absences.addLeave(a.salon, a.owner.tokens, gone.id, leaveInput(a.free))).leave;
    const goneBlock = (await a.absences.addBlock(a.salon, a.owner.tokens, gone.id, blockInput(a.free))).block;
    await team.remove(a.salon, a.owner.tokens, await team.get(a.salon, a.owner.tokens, gone.id));
    const before = absenceSnapshot(a.created.id);
    await refused(await team.call(a.salon, 'GET', schedulePath(gone.id), a.owner.tokens), 404, 'not_found', 'removed: read');
    for (const route of absenceRoutes(gone.id, goneLeave, goneBlock, plusDays(a.free, 1)).all) await refused(await team.call(a.salon, route.method, route.path, a.owner.tokens, route.data), 404, 'not_found', `removed: ${route.label}`);
    expect(absenceSnapshot(a.created.id), 'nothing saved for someone who has left').toBe(before);
    const stored = absenceTables().reduce((sum, t) => sum + Number(identitySql(`SELECT count(*) FROM ${t} x WHERE tenant_id = ${sqlLiteral(a.created.id)} AND row_to_json(x)::text LIKE ${sqlLiteral(`%${gone.id}%`)}`)), 0);
    expect(stored, 'removal is a tombstone: their rows stay until salon erasure').toBeGreaterThanOrEqual(2);
  });
  await team.dispose();
});

test('TC-F06-S03-028 AC-11: with no tenant context every new table returns no rows to the application role, a salon\'s context shows only its own, every row is on the salon\'s default branch, and reads for two salons sent together never cross', async ({ identity, operator }) => {
  const a = await sickPerson(identity, operator);
  const b = await sickPerson(identity, operator);
  const rowsOf = (table: string, salonId: string) => Number(identitySql(`SELECT count(*) FROM ${table} WHERE tenant_id = ${sqlLiteral(salonId)}`));
  for (const table of absenceTables()) {
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
    const answers = await Promise.all(order.map(x => x.team.call(x.salon, 'GET', schedulePath(x.person.id), x.owner.tokens)));
    for (const [n, answer] of answers.entries()) {
      expect(answer.status(), `request ${n}`).toBe(200);
      const text = await answer.text();
      const other = order[n] === a ? b : a;
      expect(text, `request ${n}: its own note`).toContain(order[n].note);
      expect(text, `request ${n}: nothing of the other salon`).not.toContain(other.note);
      expect(text).not.toContain(other.label);
    }
  });
  await a.team.dispose();
  await b.team.dispose();
});

// ---------------------------------------------------------------- AC-12 audit

test('TC-F06-S03-029 AC-12: every leave and block added or removed is audited with the actor, the time and the before and after values; a leave\'s stored rows hold its days and hasNote and never its note or type; a block\'s hold its label; a refused change is not audited', async ({ identity, operator }) => {
  const team = new Team(identity);
  const absences = new Absences(team);
  const { salon, owner, created } = await teamSalon(operator, identity);
  const receptionist = await identity.staff(salon, 'RECEPTIONIST');
  const person = await team.add(salon, owner.tokens, memberInput({ name: 'Audit Person' }));
  const today = await salonDate(identity);
  const t0 = await identity.now();
  let seen = auditOf(created.id, null, t0).length;
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
  const note = `Private note ${mark()}`;
  const [first, last] = [plusDays(today, 4), plusDays(today, 6)];
  let leave!: Leave;
  const hasNoType = (details: string, label: string) => {
    expect(details, `${label}: no note`).not.toContain(note);
    expect(details, `${label}: the type's value is nowhere`).not.toMatch(/sick/i);
    expect(details, `${label}: nor a type field`).not.toMatch(/\\*"type\\*"/);
  };
  await test.step('sick leave added: its days and hasNote, not its note or type', async () => {
    const details = await audited('add leave', owner.principal.id, async () => { leave = (await absences.addLeave(salon, owner.tokens, person.id, leaveInput(first, { lastDay: last, type: 'sick', note }))).leave; });
    expect(details, 'its action').toContain(PENDING_F06_S03.audit.leaveAdded);
    expect(details, 'its first day').toContain(first);
    expect(details, 'its last day').toContain(last);
    expect(details, 'only that there is a note').toMatch(/hasNote\\*"?\s*:\s*true/);
    hasNoType(details, 'added');
  });
  await test.step('removed, the same', async () => {
    const details = await audited('remove leave', owner.principal.id, () => absences.removeLeave(salon, owner.tokens, person.id, leave.id));
    expect(details, 'its action').toContain(PENDING_F06_S03.audit.leaveRemoved);
    expect(details, 'its first day').toContain(first);
    hasNoType(details, 'removed');
  });
  await test.step('leave with no note says so', async () => {
    const details = await audited('add leave with no note', owner.principal.id, () => absences.addLeave(salon, owner.tokens, person.id, leaveInput(plusDays(today, 9), { type: 'sick' })));
    expect(details).toMatch(/hasNote\\*"?\s*:\s*false/);
    hasNoType(details, 'no note');
  });
  const label = `Training ${mark()}`;
  let block!: Block;
  await test.step('a block added by a Receptionist, with its label and times', async () => {
    const details = await audited('add a block', receptionist.principal.id, async () => { block = (await absences.addBlock(salon, receptionist.tokens, person.id, blockInput(plusDays(today, 2), { starts: '10:15', finishes: '11:45', label }))).block; });
    expect(details, 'its action').toContain(PENDING_F06_S03.audit.blockAdded);
    for (const words of [label, plusDays(today, 2), '10:15', '11:45']) expect(details, words).toContain(words);
  });
  await test.step('removed by the Owner, with what it held', async () => {
    const details = await audited('remove a block', owner.principal.id, () => absences.removeBlock(salon, owner.tokens, person.id, block.id));
    expect(details, 'its action').toContain(PENDING_F06_S03.audit.blockRemoved);
    expect(details, 'before').toContain(label);
  });
  await test.step('a refused change is not audited', async () => {
    await refused(await absences.addLeaveRaw(salon, owner.tokens, person.id, leaveInput(plusDays(today, 9))), 409, 'conflict', 'overlap');
    await refused(await absences.removeLeaveRaw(salon, owner.tokens, person.id, randomUUID()), 404, 'not_found', 'unknown leave');
    await refused(await absences.addBlockRaw(salon, owner.tokens, person.id, blockInput(today, { label: 'A' })), 400, 'bad_request', 'a label of 1');
    await refused(await absences.addLeaveRaw(salon, receptionist.tokens, person.id, leaveInput(plusDays(today, 30))), 403, 'permission_denied', 'a Receptionist adding leave');
    expect(auditOf(created.id, null, t0).length, 'nothing more audited').toBe(seen);
  });
  await test.step('the stored rows as a whole, and the list a support session can read', async () => {
    expect(auditEntriesLike(created.id, first), 'there are rows about the leave').toBeGreaterThan(0);
    expect(auditEntriesLike(created.id, note), 'no row has its note').toBe(0);
    expect(auditEntriesLike(created.id, 'sick'), 'no row has its type').toBe(0);
    const tokens = await supportTokens(operator, created.id, salon);
    const page = await json<{ items: unknown[] }>(await onSalonHost(identity, salon, 'GET', AUDIT, tokens), 'PageAuditView');
    expect(page.items.length, 'there are entries to read').toBeGreaterThan(0);
    const body = JSON.stringify(page.items);
    expect(body).not.toContain(note);
    expect(body).not.toMatch(/sick/i);
  });
  await team.dispose();
});
