/**
 * F03-S04 support sessions: starting, single-use entry, ending, and separation.
 * Cases TC-F03-S04-001 to -004, -009, -014 (cases.md). The mutation table (-007), credential scope
 * (-008), audit (-011), Owner list (-012) and Admin list (-013) join this file at feature-verify,
 * once the S04 handoff confirms the support credential's response shape.
 *
 * Entry codes are exchanged directly with POST /api/v1/support/entry on a host; the BFF Origin
 * rows are browser cases. The support credential's field name is in PENDING-style `credentialOf`.
 */
import { json, type Tokens } from '../../shared/identity';
import type { components } from '@contracts/schema';
import {
  test, expect, CONSOLE_HOST, fieldsOf, operatorJson, operatorProblem, ownerOf,
  type Operator, type OperatorActor, type SupportStart,
} from '../../shared/operator';
import type { Identity } from '../../shared/identity';
import { PLATFORM_DOMAIN, type Salon } from '../../shared/tenants';

const id = (n: number) => `TC-F03-S04-${String(n).padStart(3, '0')}`;
const REASON = 'QA support reason text';

/** The bearer a support exchange returns (shape confirmed against the S04 handoff). */
function credentialOf(body: Record<string, unknown>): Tokens {
  const access = (body.accessToken ?? (body.credentials as Record<string, unknown> | undefined)?.accessToken) as string | undefined;
  if (!access) throw new Error('ENVIRONMENT: the support exchange returned no recognisable access credential (confirm its shape in the S04 handoff)');
  return { accessToken: access } as Tokens;
}

async function start(operator: Operator, actor: OperatorActor, salonId: string, data: Record<string, unknown> = { reason: REASON }) {
  return operatorJson<SupportStart>(await operator.startSupport(actor, salonId, data), 'supportStart', 201);
}

async function enter(operator: Operator, code: string, salon: Salon): Promise<Tokens> {
  return credentialOf(await operatorJson(await operator.exchange(code, salon.hostname), 'supportCredential'));
}

async function issuesNothing(operator: Operator, code: string, host: string, label: string) {
  const r = await operator.exchange(code, host);
  expect(r.status(), `${label}: refused`).toBeGreaterThanOrEqual(400);
  expect(await r.text(), `${label}: no credential`).not.toMatch(/accessToken|"token"/);
}

function reads(identity: Identity, salon: Salon, tokens: Tokens) {
  return identity.call(salon, 'GET', '/staff?size=1', undefined, tokens);
}

async function ownerTokens(identity: Identity, salon: Salon, input: Parameters<typeof ownerOf>[0]) {
  const challenge = await identity.signIn(salon, ownerOf(input));
  const enrollment = await json<components['schemas']['Enrollment']>(await identity.call(salon, 'POST', '/staff/mfa/enroll',
    { challengeId: challenge.challengeId, challengeToken: challenge.challengeToken }), 'Enrollment');
  return identity.verify(salon, challenge, enrollment.secret!);
}

test(`${id(1)} A Support operator starts a bounded session for an accessible salon`, async ({ operator, clock }) => {
  await clock.set('2026-11-03T09:00:00Z');
  const admin = await operator.actor();
  const support = await operator.actor('OPERATOR_SUPPORT');
  const { created } = await operator.salon(admin, (await operator.plan(admin)).id);
  for (const [data, minutes] of [[{ reason: 'r'.repeat(10) }, 30], [{ reason: 'r'.repeat(500), durationMinutes: 120 }, 120], [{ reason: REASON, durationMinutes: 5 }, 5]] as const) {
    const before = Date.parse(await operator.now());
    const session = await start(operator, support, created.id, data);
    expect(Object.keys(session).sort()).toEqual(['entryCode', 'entryUrl', 'expiresAt', 'id']);
    const lasts = Date.parse(session.expiresAt) - before;
    expect(lasts, `${minutes} minutes`).toBeGreaterThanOrEqual(minutes * 60_000 - 1_000);
    expect(lasts).toBeLessThanOrEqual(minutes * 60_000 + 2_000);
    expect(session.entryUrl).toBe(`https://${created.hostname}/workspace/support#${session.entryCode}`);
    expect((await operator.call('DELETE', `/support-sessions/${session.id}`, { tokens: support.tokens })).status()).toBe(204);
  }
  for (const [data, field] of [[{ reason: 'r'.repeat(9) }, 'reason'], [{ reason: 'r'.repeat(501) }, 'reason'], [{ reason: REASON, durationMinutes: 4 }, 'durationMinutes'],
    [{ reason: REASON, durationMinutes: 121 }, 'durationMinutes'], [{ reason: REASON, durationMinutes: 30.5 }, 'durationMinutes'], [{ reason: REASON, durationMinutes: '30' }, 'durationMinutes']] as const) {
    expect(fieldsOf(await operatorProblem(await operator.startSupport(support, created.id, data), 400, 'bad_request'))).toContain(field);
  }
  const list = await operator.call('GET', `/support-sessions?salonId=${created.id}&state=active`, { tokens: support.tokens });
  expect(list.status()).toBe(200);
  expect((await list.json()).total, 'no active session from refused starts').toBe(0);
});

test(`${id(2)} Suspended or closed salons, a second own session and an Admin cannot start a session`, async ({ operator }) => {
  const admin = await operator.actor();
  const a = await operator.actor('OPERATOR_SUPPORT');
  const b = await operator.actor('OPERATOR_SUPPORT');
  const plan = await operator.plan(admin);
  const active = await operator.salon(admin, plan.id);
  const suspended = await operator.salon(admin, plan.id);
  const closed = await operator.salon(admin, plan.id);
  expect((await operator.suspend(admin, suspended.created.id, await operator.version(admin, suspended.created.id))).status()).toBe(200);
  expect((await operator.close(admin, closed.created.id, await operator.version(admin, closed.created.id), closed.created.slug)).status()).toBe(200);
  for (const target of [suspended, closed]) {
    const r = await operator.startSupport(a, target.created.id);
    expect(r.status(), `${target.created.slug}: refused`).toBeGreaterThanOrEqual(400);
    expect(await r.text()).not.toContain('entryCode');
  }
  await start(operator, a, active.created.id);
  await operatorProblem(await operator.startSupport(a, active.created.id), 409, 'support_session_active');
  await start(operator, b, active.created.id);
  await operatorProblem(await operator.startSupport(admin, active.created.id), 403, 'permission_denied');
});

test(`${id(3)} An entry code works once, within 60 seconds, on its own salon's host only`, async ({ operator, clock }) => {
  const admin = await operator.actor();
  const support = await operator.actor('OPERATOR_SUPPORT');
  const plan = await operator.plan(admin);
  const x = await operator.salon(admin, plan.id);
  const y = await operator.salon(admin, plan.id);
  const sx = await start(operator, support, x.created.id);
  const sy = await start(operator, support, y.created.id);
  await issuesNothing(operator, `${sx.entryCode}x`, x.salon.hostname, 'altered');
  await issuesNothing(operator, sx.entryCode, y.salon.hostname, 'other salon host');
  await issuesNothing(operator, sx.entryCode, CONSOLE_HOST, 'console host');
  await issuesNothing(operator, sx.entryCode, PLATFORM_DOMAIN, 'bare host');
  const tokens = await enter(operator, sx.entryCode, x.salon);
  expect((await reads(operator.identity, x.salon, tokens)).status()).toBe(200);
  await issuesNothing(operator, sx.entryCode, x.salon.hostname, 'reused');
  const fresh = await operatorJson<{ entryCode: string }>(await operator.call('POST', `/support-sessions/${sy.id}/entry-codes`, { tokens: support.tokens }), 'entryCode', 201);
  await clock.advance(61_000);
  await issuesNothing(operator, fresh.entryCode, y.salon.hostname, 'expired');
});

test(`${id(4)} Simultaneous exchanges of one code issue exactly one support credential`, async ({ operator }) => {
  const admin = await operator.actor();
  const support = await operator.actor('OPERATOR_SUPPORT');
  const { created, salon } = await operator.salon(admin, (await operator.plan(admin)).id);
  const session = await start(operator, support, created.id);
  const outcomes = await Promise.all(Array.from({ length: 8 }, () => operator.exchange(session.entryCode, salon.hostname)));
  expect(outcomes.filter(r => r.status() < 300).length, 'one credential').toBe(1);
  expect(outcomes.filter(r => r.status() >= 400).length, 'seven refusals').toBe(7);
});

test(`${id(9)} Expiry, operator end, Admin revoke and Owner revoke each end support on the next request`, async ({ operator, identity, clock }) => {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const target = await operator.salon(admin, plan.id);
  const owner = await ownerTokens(identity, target.salon, target.input);
  const endings: [string, (session: SupportStart, support: OperatorActor) => Promise<unknown>][] = [
    ['operator end', async (s, support) => expect((await operator.call('DELETE', `/support-sessions/${s.id}`, { tokens: support.tokens })).status()).toBe(204)],
    ['admin revoke', async s => expect((await operator.call('DELETE', `/support-sessions/${s.id}`, { tokens: admin.tokens })).status()).toBe(204)],
    ['owner revoke', async s => expect((await operator.call('DELETE', `/api/v1/salon/support-sessions/${s.id}`, { host: target.salon.hostname, tokens: owner })).status()).toBeLessThan(300)],
    ['expiry', async () => clock.advance(5 * 60_000 + 1_000)],
  ];
  for (const [label, end] of endings) {
    await test.step(label, async () => {
      const support = await operator.actor('OPERATOR_SUPPORT');
      const session = await start(operator, support, target.created.id, { reason: REASON, durationMinutes: 5 });
      const spare = await operatorJson<{ entryCode: string }>(await operator.call('POST', `/support-sessions/${session.id}/entry-codes`, { tokens: support.tokens }), 'entryCode', 201);
      const tokens = await enter(operator, session.entryCode, target.salon);
      expect((await reads(identity, target.salon, tokens)).status()).toBe(200);
      await end(session, support);
      await operatorProblem(await reads(identity, target.salon, tokens), 401, 'invalid_session');
      const again = await operator.call('POST', `/support-sessions/${session.id}/entry-codes`, { tokens: support.tokens });
      expect(again.status(), 'no new code for an ended session').toBeGreaterThanOrEqual(400);
      await issuesNothing(operator, spare.entryCode, target.salon.hostname, `${label}: earlier unused code`);
      await clock.real();
    });
  }
});

test(`${id(14)} Simultaneous sessions stay separate per operator and per salon`, async ({ operator, identity }) => {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const x = await operator.salon(admin, plan.id);
  const y = await operator.salon(admin, plan.id);
  const a = await operator.actor('OPERATOR_SUPPORT');
  const b = await operator.actor('OPERATOR_SUPPORT');
  const ax = await start(operator, a, x.created.id);
  const bx = await start(operator, b, x.created.id);
  const ay = await start(operator, a, y.created.id);
  const creds = { ax: await enter(operator, ax.entryCode, x.salon), bx: await enter(operator, bx.entryCode, x.salon), ay: await enter(operator, ay.entryCode, y.salon) };
  for (const [name, salon, other] of [['ax', x.salon, y.salon], ['bx', x.salon, y.salon], ['ay', y.salon, x.salon]] as const) {
    expect((await reads(identity, salon, creds[name])).status(), `${name} reads its salon`).toBe(200);
    await operatorProblem(await reads(identity, other, creds[name]), 404, 'not_found');
  }
  expect((await operator.call('DELETE', `/support-sessions/${ax.id}`, { tokens: a.tokens })).status()).toBe(204);
  await operatorProblem(await reads(identity, x.salon, creds.ax), 401, 'invalid_session');
  expect((await reads(identity, x.salon, creds.bx)).status(), 'B on X unaffected').toBe(200);
  expect((await reads(identity, y.salon, creds.ay)).status(), 'A on Y unaffected').toBe(200);
});
