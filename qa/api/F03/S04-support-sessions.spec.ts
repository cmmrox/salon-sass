/**
 * F03-S04 support sessions: starting, single-use entry, the read-only support principal, its
 * boundaries, ending, the salon audit, the Owner's and Admin's session lists, and separation.
 * Cases TC-F03-S04-001 to -004, -007 to -009, -011 to -014 (cases.md).
 *
 * Routes and codes come from the S04 spec's API table and acceptance criteria (qa/shared/operator.ts
 * SUPPORT); the generated schema names, the exchanged credential's shape and the list filter names
 * come from the S04 handoff (PENDING_S04).
 * Entry codes are exchanged directly with the API here; the BFF Origin rows are browser cases.
 */
import { randomUUID } from 'node:crypto';

import { ROLES, json, syntheticAccount, type Identity, type Role, type Tokens } from '../../shared/identity';
import {
  test, expect, CONSOLE_HOST, PLATFORM_HOST, SUPPORT, fieldsOf, onSalonHost, operatorProblem, ownerSession, platformAudit,
  supportJson, tenantMutationRoutes,
  type Operator, type OperatorActor, type SupportSession, type SupportStart,
} from '../../shared/operator';
import { webUrl } from '../../shared/env';
import { salonUrl, type Salon } from '../../shared/tenants';

const id = (n: number) => `TC-F03-S04-${String(n).padStart(3, '0')}`;
const REASON = 'QA support reason text';
const STAFF_READ = '/api/v1/identity/staff?size=1';

async function start(operator: Operator, actor: OperatorActor, salonId: string, data: Record<string, unknown> = { reason: REASON }) {
  return supportJson<SupportStart>(await operator.startSupport(actor, salonId, data), 'supportStart', 201);
}

async function enter(operator: Operator, code: string, salon: Salon): Promise<Tokens> {
  return operator.supportCredential(await operator.exchange(code, salon.hostname));
}

/** A refused exchange issues no credential and no support cookie (S04 AC-2). */
async function refusedEntry(operator: Operator, code: string, host: string, status: number, problem: string, label: string) {
  const r = await operator.exchange(code, host);
  await test.step(label, async () => {
    await operatorProblem(r, status, problem);
    expect(r.headers()['set-cookie'] ?? '', `${label}: no support cookie`).not.toMatch(/salon_support_/);
  });
}

const reads = (identity: Identity, salon: Salon, tokens: Tokens) => onSalonHost(identity, salon, 'GET', STAFF_READ, tokens);

async function arrange(operator: Operator, count = 1) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const salons = [];
  for (let n = 0; n < count; n++) salons.push(await operator.salon(admin, plan.id));
  return { admin, plan, salons };
}

test(`${id(1)} A Support operator starts a bounded session for an accessible salon`, async ({ operator, clock }) => {
  await clock.set('2026-11-03T09:00:00Z');
  const { salons: [target] } = await arrange(operator);
  const support = await operator.actor('OPERATOR_SUPPORT');
  for (const [data, minutes] of [[{ reason: 'r'.repeat(10) }, 30], [{ reason: 'r'.repeat(500), durationMinutes: 120 }, 120], [{ reason: REASON, durationMinutes: 5 }, 5]] as const) {
    const before = Date.parse(await operator.now());
    const session = await start(operator, support, target.created.id, data);
    // The spec's four fields and the code's own expiry, which the S04 handoff adds.
    expect(Object.keys(session).sort()).toEqual(['codeExpiresAt', 'entryCode', 'entryUrl', 'expiresAt', 'id']);
    const codeLasts = Date.parse(session.codeExpiresAt) - before;
    expect(codeLasts > 0 && codeLasts <= 61_000, 'the entry code lasts 60 seconds').toBe(true);
    const lasts = Date.parse(session.expiresAt) - before;
    expect(lasts, `${minutes} minutes`).toBeGreaterThanOrEqual(minutes * 60_000 - 1_000);
    expect(lasts).toBeLessThanOrEqual(minutes * 60_000 + 2_000);
    // SALON_SUPPORT_LINK_ORIGIN: https://<host> outside local, http://<host>:3100 locally (S04 handoff).
    expect(session.entryUrl).toBe(`${salonUrl(target.salon, webUrl)}/workspace/support#${session.entryCode}`);
    expect((await operator.endSupport(support, session.id)).status()).toBe(204);
  }
  for (const [data, field] of [[{ reason: 'r'.repeat(9) }, 'reason'], [{ reason: 'r'.repeat(501) }, 'reason'], [{ reason: REASON, durationMinutes: 4 }, 'durationMinutes'],
    [{ reason: REASON, durationMinutes: 121 }, 'durationMinutes'], [{ reason: REASON, durationMinutes: 30.5 }, 'durationMinutes'], [{ reason: REASON, durationMinutes: '30' }, 'durationMinutes']] as const) {
    expect(fieldsOf(await operatorProblem(await operator.startSupport(support, target.created.id, data), 400, 'bad_request'))).toContain(field);
  }
  const active = await supportJson<{ total: number }>(await operator.supportList(support, { salonId: target.created.id, state: 'active' }), 'supportSessionPage');
  expect(active.total, 'no active session from refused starts').toBe(0);
});

test(`${id(2)} Suspended or closed salons, a second own session and an Admin cannot start a session`, async ({ operator }) => {
  const { admin, salons: [active, suspended, closed] } = await arrange(operator, 3);
  const a = await operator.actor('OPERATOR_SUPPORT');
  const b = await operator.actor('OPERATOR_SUPPORT');
  expect((await operator.suspend(admin, suspended.created.id, await operator.version(admin, suspended.created.id))).status()).toBe(200);
  expect((await operator.close(admin, closed.created.id, await operator.version(admin, closed.created.id), closed.created.slug)).status()).toBe(200);
  for (const target of [suspended, closed]) await operatorProblem(await operator.startSupport(a, target.created.id), 409, 'invalid_state');
  await start(operator, a, active.created.id);
  await operatorProblem(await operator.startSupport(a, active.created.id), 409, 'support_session_active');
  await start(operator, b, active.created.id);
  await operatorProblem(await operator.startSupport(admin, active.created.id), 403, 'permission_denied');
  const all = await supportJson<{ items: SupportSession[] }>(await operator.supportList(admin), 'supportSessionPage');
  expect(all.items.filter(s => [suspended.created.id, closed.created.id].includes(s.salonId ?? '')), 'no session for refused salons').toEqual([]);
});

test(`${id(3)} An entry code works once, within 60 seconds, on its own salon's host only`, async ({ operator, identity, clock }) => {
  const { salons: [x, y] } = await arrange(operator, 2);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const sx = await start(operator, support, x.created.id);
  const sy = await start(operator, support, y.created.id);
  await refusedEntry(operator, `${sx.entryCode}x`, x.salon.hostname, 401, 'invalid_code', 'altered');
  await refusedEntry(operator, sx.entryCode, y.salon.hostname, 401, 'invalid_code', 'other salon');
  await refusedEntry(operator, sx.entryCode, CONSOLE_HOST, 404, 'not_found', 'console host');
  await refusedEntry(operator, sx.entryCode, PLATFORM_HOST, 404, 'not_found', 'bare host');
  const tokens = await enter(operator, sx.entryCode, x.salon);
  expect((await reads(identity, x.salon, tokens)).status(), 'the refused attempts left the single use intact').toBe(200);
  await refusedEntry(operator, sx.entryCode, x.salon.hostname, 401, 'invalid_code', 'reused');
  const fresh = await supportJson<{ entryCode: string }>(await operator.newEntryCode(support, sy.id), 'entryCode', 201);
  await clock.advance(61_000);
  await refusedEntry(operator, fresh.entryCode, y.salon.hostname, 401, 'invalid_code', 'expired');
});

test(`${id(4)} Simultaneous exchanges of one code issue exactly one support credential`, async ({ operator }) => {
  const { salons: [target] } = await arrange(operator);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await start(operator, support, target.created.id);
  const outcomes = await Promise.all(Array.from({ length: 8 }, () => operator.exchange(session.entryCode, target.salon.hostname)));
  expect(outcomes.filter(r => r.status() < 300).length, 'one credential').toBe(1);
  for (const loser of outcomes.filter(r => r.status() >= 300)) await operatorProblem(loser, 401, 'invalid_code');
});

test(`${id(7)} Every mutation in the support view answers support_read_only and changes nothing`, async ({ operator, identity }) => {
  const { salons: [target] } = await arrange(operator);
  const owner = await ownerSession(identity, target.salon, target.input);
  await identity.seed(target.salon, [syntheticAccount('RECEPTIONIST')]);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const tokens = await enter(operator, (await start(operator, support, target.created.id)).entryCode, target.salon);
  const me = await json<{ kind: string }>(await onSalonHost(identity, target.salon, 'GET', '/api/v1/identity/me', tokens), 'Principal');
  expect(me.kind, 'a support principal').toBe('support');
  const staffBefore = await json<unknown>(await onSalonHost(identity, target.salon, 'GET', '/api/v1/identity/staff?size=100', owner.tokens), 'PageStaffView');
  const victim = randomUUID();
  for (const m of tenantMutationRoutes()) {
    await test.step(`${m.method} ${m.path}`, async () => {
      const body = m.path.endsWith('/staff') ? syntheticAccount('MANAGER') : {};
      await operatorProblem(await onSalonHost(identity, target.salon, m.method, m.path.replace('{id}', victim), tokens, body), 403, 'support_read_only');
    });
  }
  expect(await json<unknown>(await onSalonHost(identity, target.salon, 'GET', '/api/v1/identity/staff?size=100', owner.tokens), 'PageStaffView'), 'nothing changed').toEqual(staffBefore);
});

test(`${id(8)} The support credential and operator or staff credentials stay inside their own boundaries`, async ({ operator, identity }) => {
  const { salons: [a, b] } = await arrange(operator, 2);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const tokens = await enter(operator, (await start(operator, support, a.created.id)).entryCode, a.salon);
  expect((await reads(identity, a.salon, tokens)).status(), 'the credential works on its salon').toBe(200);
  await operatorProblem(await operator.call('GET', '/me', { tokens }), 401, 'invalid_session');
  await operatorProblem(await operator.call('GET', '/salons', { tokens }), 401, 'invalid_session');
  // Customer endpoints refuse a staff-kind principal with 403 (S04 handoff, AC-4 checked).
  await operatorProblem(await onSalonHost(identity, a.salon, 'GET', '/api/v1/identity/customers/me', tokens), 403, 'permission_denied');
  await operatorProblem(await reads(identity, b.salon, tokens), 404, 'not_found');
  await operatorProblem(await operator.call('GET', '/api/v1/identity/me', { tokens }), 404, 'not_found');
  const owner = await ownerSession(identity, a.salon, a.input);
  await operatorProblem(await operator.startSupport({ tokens: owner.tokens } as OperatorActor, a.created.id), 401, 'invalid_session');
  await operatorProblem(await operator.call('GET', SUPPORT.list, { tokens: owner.tokens }), 401, 'invalid_session');
  await operatorProblem(await onSalonHost(identity, a.salon, 'GET', '/api/v1/identity/me', support.tokens), 401, 'invalid_session');
});

test(`${id(9)} Expiry, operator end, Admin revoke and Owner revoke each end support on the next request`, async ({ operator, identity, clock }) => {
  test.setTimeout(120_000);
  const { admin, salons: [target] } = await arrange(operator);
  const owner = await ownerSession(identity, target.salon, target.input);
  const endings: [string, (session: SupportStart, support: OperatorActor) => Promise<void>][] = [
    ['operator end', async (s, support) => { expect((await operator.endSupport(support, s.id)).status()).toBe(204); }],
    ['admin revoke', async s => { expect((await operator.endSupport(await operator.renew(admin), s.id)).status()).toBe(204); }],
    ['owner revoke', async s => {
      // The Owner's first session is still valid (a second sign-in in the same TOTP step is a refused replay).
      expect((await onSalonHost(identity, target.salon, 'DELETE', `${SUPPORT.salonSessions}/${s.id}`, owner.tokens)).status(), 'Owner revoke').toBe(204);
    }],
    ['expiry', async () => { await clock.advance(5 * 60_000 + 1_000); }],
  ];
  for (const [label, end] of endings) {
    await test.step(label, async () => {
      const support = await operator.actor('OPERATOR_SUPPORT');
      const session = await start(operator, support, target.created.id, { reason: REASON, durationMinutes: 5 });
      const spare = await supportJson<{ entryCode: string }>(await operator.newEntryCode(support, session.id), 'entryCode', 201);
      const tokens = await enter(operator, session.entryCode, target.salon);
      expect((await reads(identity, target.salon, tokens)).status()).toBe(200);
      await end(session, support);
      await operatorProblem(await reads(identity, target.salon, tokens), 401, 'invalid_session');
      await operator.renew(support);
      const again = await operator.newEntryCode(support, session.id);
      expect(again.status(), 'no new code for an ended session').toBeGreaterThanOrEqual(400);
      await refusedEntry(operator, spare.entryCode, target.salon.hostname, 401, 'invalid_code', `${label}: earlier unused code`);
      await clock.real();
    });
  }
});

test(`${id(11)} The salon audit shows start, every request and end to Owner and Manager only`, async ({ operator, identity }) => {
  const { salons: [target, other] } = await arrange(operator, 2);
  const owner = await ownerSession(identity, target.salon, target.input);
  const otherOwner = await ownerSession(identity, other.salon, other.input);
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await start(operator, support, target.created.id);
  const tokens = await enter(operator, session.entryCode, target.salon);
  for (let n = 0; n < 3; n++) expect((await reads(identity, target.salon, tokens)).status()).toBe(200);
  await operatorProblem(await onSalonHost(identity, target.salon, 'POST', '/api/v1/identity/staff', tokens, syntheticAccount('MANAGER')), 403, 'support_read_only');
  expect((await onSalonHost(identity, target.salon, 'DELETE', `${SUPPORT.salonSessions}/${session.id}`, owner.tokens)).status(), 'Owner revoke').toBe(204);
  const audit = async (tokens: Tokens, salon: Salon) => onSalonHost(identity, salon, 'GET', '/api/v1/identity/audit?size=100', tokens);
  const readers: { role: Role; tokens: Tokens }[] = [{ role: 'OWNER', tokens: owner.tokens }];
  for (const role of ROLES.filter(r => r !== 'OWNER')) readers.push({ role, tokens: (await identity.staff(target.salon, role)).tokens });
  for (const reader of readers) {
    await test.step(reader.role, async () => {
      const r = await audit(reader.tokens, target.salon);
      if (reader.role !== 'OWNER' && reader.role !== 'MANAGER') { await operatorProblem(r, 403, 'permission_denied'); return; }
      const page = await json<{ items: { actorKind: string }[] }>(r, 'PageAuditView');
      const text = JSON.stringify(page.items);
      expect(page.items.filter(e => e.actorKind === 'support').length, 'three reads and a refused mutation').toBeGreaterThanOrEqual(4);
      expect(text, 'the start names the operator').toContain(support.account.displayName);
      expect(text, 'the reason').toContain(REASON);
      expect(text, 'no operator email').not.toContain(support.account.email);
      expect(text, 'no entry code').not.toContain(session.entryCode);
    });
  }
  const foreign = JSON.stringify((await json<{ items: unknown[] }>(await audit(otherOwner.tokens, other.salon), 'PageAuditView')).items);
  expect(foreign, 'never in another salon').not.toContain(session.id);
  expect(foreign).not.toContain(REASON);
});

test(`${id(12)} The Owner lists and revokes the salon's support sessions and other roles cannot`, async ({ operator, identity }) => {
  const { salons: [target] } = await arrange(operator);
  const owner = await ownerSession(identity, target.salon, target.input);
  const a = await operator.actor('OPERATOR_SUPPORT');
  const b = await operator.actor('OPERATOR_SUPPORT');
  const ended = await start(operator, a, target.created.id);
  expect((await operator.endSupport(a, ended.id)).status()).toBe(204);
  const active = await start(operator, b, target.created.id);
  const tokens = await enter(operator, active.entryCode, target.salon);
  for (const role of ROLES.filter(r => r !== 'OWNER')) {
    const staff = await identity.staff(target.salon, role);
    await operatorProblem(await onSalonHost(identity, target.salon, 'GET', SUPPORT.salonSessions, staff.tokens), 403, 'permission_denied');
    await operatorProblem(await onSalonHost(identity, target.salon, 'DELETE', `${SUPPORT.salonSessions}/${active.id}`, staff.tokens), 403, 'permission_denied');
  }
  await operatorProblem(await onSalonHost(identity, target.salon, 'DELETE', `${SUPPORT.salonSessions}/${active.id}`, tokens), 403, 'support_read_only');
  expect((await reads(identity, target.salon, tokens)).status(), 'refused revocations revoked nothing').toBe(200);
  const listed = await supportJson<{ items: (SupportSession & Record<string, unknown>)[] }>(
    await onSalonHost(identity, target.salon, 'GET', SUPPORT.salonSessions, owner.tokens), 'salonSupportSessionPage');
  expect(listed.items.map(s => s.id).sort()).toEqual([ended.id, active.id].sort());
  const text = JSON.stringify(listed.items);
  for (const value of [a.account.displayName, b.account.displayName, REASON]) expect(text).toContain(value);
  for (const value of [a.account.email, b.account.email]) expect(text, 'no operator email').not.toContain(value);
  expect((await onSalonHost(identity, target.salon, 'DELETE', `${SUPPORT.salonSessions}/${active.id}`, owner.tokens)).status(), 'Owner revoke').toBe(204);
  await operatorProblem(await reads(identity, target.salon, tokens), 401, 'invalid_session');
});

test(`${id(13)} Support finds salons; Admin sees and revokes every session; platform audit records each`, async ({ operator, identity }) => {
  const { admin, salons: [x, y] } = await arrange(operator, 2);
  const a = await operator.actor('OPERATOR_SUPPORT');
  const b = await operator.actor('OPERATOR_SUPPORT');
  expect((await operator.salonPage(a, `?q=${x.created.slug}`)).items.map(s => s.id), 'Support finds the salon').toEqual([x.created.id]);
  expect((await operator.salonDetail(a, x.created.id)).id).toBe(x.created.id);
  const ax = await start(operator, a, x.created.id);
  const by = await start(operator, b, y.created.id);
  const byTokens = await enter(operator, by.entryCode, y.salon);
  const own = await supportJson<{ items: SupportSession[] }>(await operator.supportList(a), 'supportSessionPage');
  expect(own.items.map(s => s.id), 'Support sees only its own').toEqual([ax.id]);
  const forX = await supportJson<{ items: SupportSession[] }>(await operator.supportList(admin, { salonId: x.created.id }), 'supportSessionPage');
  expect(forX.items.map(s => s.id)).toEqual([ax.id]);
  const activeAll = await supportJson<{ items: SupportSession[] }>(await operator.supportList(admin, { state: 'active' }), 'supportSessionPage');
  expect(activeAll.items.map(s => s.id).sort()).toEqual([ax.id, by.id].sort());
  expect(JSON.stringify(activeAll.items)).toContain(REASON);
  await operatorProblem(await operator.endSupport(a, by.id), 403, 'permission_denied');
  expect((await operator.endSupport(admin, by.id)).status(), 'Admin revokes any').toBe(204);
  await operatorProblem(await reads(identity, y.salon, byTokens), 401, 'invalid_session');
  expect(platformAudit(ax.id).length, 'the start is audited').toBeGreaterThanOrEqual(1);
  expect(platformAudit(by.id).length, 'start and revocation are audited').toBeGreaterThanOrEqual(2);
  for (const row of [...platformAudit(ax.id), ...platformAudit(by.id)]) expect(row.traceId).toMatch(/^[0-9a-f]{32}$/);
});

test(`${id(14)} Simultaneous sessions stay separate per operator and per salon`, async ({ operator, identity }) => {
  const { salons: [x, y] } = await arrange(operator, 2);
  const a = await operator.actor('OPERATOR_SUPPORT');
  const b = await operator.actor('OPERATOR_SUPPORT');
  const [ax, bx, ay] = await Promise.all([start(operator, a, x.created.id), start(operator, b, x.created.id), start(operator, a, y.created.id)]);
  const creds = { ax: await enter(operator, ax.entryCode, x.salon), bx: await enter(operator, bx.entryCode, x.salon), ay: await enter(operator, ay.entryCode, y.salon) };
  for (const [name, salon, other] of [['ax', x.salon, y.salon], ['bx', x.salon, y.salon], ['ay', y.salon, x.salon]] as const) {
    expect((await reads(identity, salon, creds[name])).status(), `${name} reads its salon`).toBe(200);
    await operatorProblem(await reads(identity, other, creds[name]), 404, 'not_found');
  }
  expect((await operator.endSupport(a, ax.id)).status()).toBe(204);
  await operatorProblem(await reads(identity, x.salon, creds.ax), 401, 'invalid_session');
  expect((await reads(identity, x.salon, creds.bx)).status(), 'B on X unaffected').toBe(200);
  expect((await reads(identity, y.salon, creds.ay)).status(), 'A on Y unaffected').toBe(200);
});

