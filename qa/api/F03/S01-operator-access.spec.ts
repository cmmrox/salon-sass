/**
 * F03-S01 operator access: MFA sign-in, host binding, operator and tenant authority, sessions and
 * password ceilings. Cases TC-F03-S01-001 to -009, -011 to -013, -015, -016 (cases.md).
 *
 * Prepared during the F03 build against the S01 spec and operator-console.md; fixture-hook paths
 * and schema names live in qa/shared/operator.ts PENDING and are confirmed at feature-verify.
 */
import { randomUUID } from 'node:crypto';

import { Customers } from '../../shared/customer-identity';
import { apiUrl } from '../../shared/env';
import { ROLES, type Tokens } from '../../shared/identity';
import {
  test, expect, alpha, CONSOLE_HOST, DAY, FORGED_FORWARDING, NON_CONSOLE_HOSTS, OPERATOR_PERMISSIONS, PRINCIPAL_KEYS,
  Operator, operatorAccount, operatorProblem, planInput, salonInput, expectNoStore, type OperatorActor,
} from '../../shared/operator';

const id = (n: number) => `TC-F03-S01-${String(n).padStart(3, '0')}`;
const idempotency = (method: string): Record<string, string> => (method === 'POST' ? { 'Idempotency-Key': randomUUID() } : {});
const challengeBody = (c: { challengeId: string; challengeToken: string }) => ({ challengeId: c.challengeId, challengeToken: c.challengeToken });

/** Every protected operator action of the S01 role table, with a body where it needs one. */
function protectedActions(planId: string, salonId: string) {
  return [
    { name: 'me', method: 'GET', route: '/me', admin: 200, support: 200 },
    { name: 'salon list', method: 'GET', route: '/salons', admin: 200, support: 200 },
    { name: 'salon detail', method: 'GET', route: `/salons/${salonId}`, admin: 200, support: 200 },
    { name: 'salon create', method: 'POST', route: '/salons', admin: 201, support: 403, body: () => salonInput(planId) },
    { name: 'plan list', method: 'GET', route: '/plans', admin: 200, support: 403 },
    { name: 'plan detail', method: 'GET', route: `/plans/${planId}`, admin: 200, support: 403 },
    { name: 'plan create', method: 'POST', route: '/plans', admin: 201, support: 403, body: () => planInput() },
  ] as const;
}

async function totals(operator: Operator, admin: OperatorActor) {
  return { plans: (await operator.planPage(admin, '?size=1')).total, salons: (await operator.salonPage(admin, '?size=1')).total };
}

test(`${id(1)} An enrolled operator obtains console access only after password and TOTP`, async ({ operator }) => {
  const account = operatorAccount('OPERATOR_ADMIN');
  await operator.seed(account);
  const signIn = await operator.signInRaw(account);
  expectNoStore(signIn, 'sign-in');
  const challenge = await operator.signIn(account);
  expect(challenge.state).toBe('verify_mfa');
  await operatorProblem(await operator.call('GET', '/me', { headers: { Authorization: `Bearer ${challenge.challengeToken}` } }), 401, 'invalid_session');
  const verify = await operator.verifyRaw(challenge, await operator.code(account.totpSecret!));
  expectNoStore(verify, 'verify');
  const tokens = await operator.tokens(verify);
  const principal = await operator.me(tokens);
  expect(Object.keys(principal).sort()).toEqual(PRINCIPAL_KEYS);
  expect(principal).toMatchObject({ kind: 'operator', role: 'OPERATOR_ADMIN', mfaEnabled: true });
  expect([...principal.permissions].sort()).toEqual([...OPERATOR_PERMISSIONS.OPERATOR_ADMIN].sort());
});

test(`${id(2)} A first-time operator must enroll MFA before any session exists`, async ({ operator }) => {
  const support = operatorAccount('OPERATOR_SUPPORT', false);
  const admin = operatorAccount('OPERATOR_ADMIN');
  await operator.seed(support, admin);
  const enroll = await operator.signIn(support);
  expect(enroll.state).toBe('enroll_mfa');
  await operatorProblem(await operator.call('GET', '/me', { headers: { Authorization: `Bearer ${enroll.challengeToken}` } }), 401, 'invalid_session');
  const setup = await operator.enrollment(enroll);
  expect(setup.secret).toMatch(/^[A-Z2-7]{32}$/);
  expect(setup.otpauthUri.startsWith('otpauth://totp/')).toBe(true);
  // Setup belongs to enrollment only: a verification challenge yields no secret.
  const verifyChallenge = await operator.signIn(admin);
  const refused = await operator.call('POST', '/mfa/challenges', { data: challengeBody(verifyChallenge) });
  expect(refused.status(), 'setup refused for a verification challenge').toBeGreaterThanOrEqual(400);
  expect(await refused.text()).not.toMatch(/otpauth|"secret"/);
  const tokens = await operator.tokens(await operator.verifyRaw(enroll, await operator.code(setup.secret)));
  expect((await operator.me(tokens)).mfaEnabled).toBe(true);
});

test(`${id(3)} Wrong, expired, consumed, exhausted or replayed MFA challenges issue no session`, async ({ operator, clock }) => {
  const account = operatorAccount('OPERATOR_ADMIN');
  await operator.seed(account);
  const secret = account.totpSecret!;
  const noSession = async (response: Awaited<ReturnType<Operator['verifyRaw']>>) => {
    await operatorProblem(response, 401, 'invalid_credentials');
    expect(response.headers()['set-cookie'] ?? '').not.toContain('salon_operator_access');
  };
  const altered = await operator.signIn(account);
  await noSession(await operator.verifyRaw({ ...altered, challengeToken: `${altered.challengeToken}x` }, await operator.code(secret)));
  const exhausted = await operator.signIn(account);
  for (let n = 0; n < 5; n++) await noSession(await operator.verifyRaw(exhausted, await operator.wrongCode(secret)));
  await noSession(await operator.verifyRaw(exhausted, await operator.code(secret)));
  const expired = await operator.signIn(account);
  await clock.advance(5 * 60_000 + 1_000);
  await noSession(await operator.verifyRaw(expired, await operator.code(secret)));
  const consumed = await operator.signIn(account);
  const code = await operator.code(secret);
  const tokens = await operator.tokens(await operator.verifyRaw(consumed, code));
  await noSession(await operator.verifyRaw(consumed, code));
  const replay = await operator.signIn(account);
  await noSession(await operator.verifyRaw(replay, code));
  expect((await operator.me(tokens)).role).toBe('OPERATOR_ADMIN');
});

test(`${id(4)} Simultaneous verification of one challenge creates exactly one operator session`, async ({ operator }) => {
  const account = operatorAccount('OPERATOR_ADMIN');
  await operator.seed(account);
  const challenge = await operator.signIn(account);
  const code = await operator.code(account.totpSecret!);
  const outcomes = await Promise.all(Array.from({ length: 8 }, () => operator.verifyRaw(challenge, code)));
  expect(outcomes.filter(r => r.status() === 200).length, 'one session').toBe(1);
  expect(outcomes.filter(r => r.status() === 401).length, 'seven refusals').toBe(7);
  const later = await operator.signIn(account);
  await operatorProblem(await operator.verifyRaw(later, code), 401, 'invalid_credentials');
});

test(`${id(5)} Operator API routes on any other host answer 404 before side effects`, async ({ operator }) => {
  const admin = await operator.actor('OPERATOR_ADMIN');
  const key = `${planInput().key}`;
  for (const host of NON_CONSOLE_HOSTS) {
    for (const forged of FORGED_FORWARDING) {
      const label = `${host} ${JSON.stringify(forged)}`;
      await test.step(label, async () => {
        await operatorProblem(await operator.call('GET', '/me', { host, tokens: admin.tokens, headers: forged }), 404, 'not_found');
        await operatorProblem(await operator.createPlanRaw(admin, planInput({ key }), randomUUID(), { host, headers: forged }), 404, 'not_found');
        await operatorProblem(await operator.signInRaw(admin.account, { host, headers: forged }), 404, 'not_found');
      });
    }
  }
  expect((await operator.planPage(admin, `?q=${key}`)).total, 'no plan created on a wrong host').toBe(0);
  // Positive controls: case and port do not matter on the console; status and salons still work.
  expect((await operator.call('GET', '/me', { host: CONSOLE_HOST.toUpperCase(), tokens: admin.tokens })).status()).toBe(200);
  expect((await operator.call('GET', '/me', { host: `${CONSOLE_HOST}:${new URL(apiUrl).port}`, tokens: admin.tokens })).status()).toBe(200);
  expect((await operator.identity.api.meta()).status()).toBe(200);
  expect((await operator.identity.api.salon(alpha)).status()).toBe(200);
});

test(`${id(6)} Admin and Support receive exactly their documented operator permissions`, async ({ operator }) => {
  const admin = await operator.actor('OPERATOR_ADMIN');
  const support = await operator.actor('OPERATOR_SUPPORT');
  const plan = await operator.plan(admin);
  const { created } = await operator.salon(admin, plan.id);
  const actions = protectedActions(plan.id, created.id);
  const before = await totals(operator, admin);
  for (const action of actions) {
    const send = (tokens?: Tokens) => operator.call(action.method, action.route, {
      tokens, data: 'body' in action ? action.body() : undefined, headers: idempotency(action.method) });
    const asSupport = await send(support.tokens);
    if (action.support === 403) await operatorProblem(asSupport, 403, 'permission_denied');
    else expect(asSupport.status(), `Support ${action.name}`).toBe(action.support);
    await operatorProblem(await send(undefined), 401, 'invalid_session');
  }
  expect(await totals(operator, admin), 'denied calls changed nothing').toEqual(before);
  for (const action of actions) {
    const r = await operator.call(action.method, action.route, {
      tokens: admin.tokens, data: 'body' in action ? action.body() : undefined, headers: idempotency(action.method) });
    expect(r.status(), `Admin ${action.name}`).toBe(action.admin);
  }
  for (const route of ['/sessions/refresh', '/sessions/sign-out']) {
    const actor = route.endsWith('refresh') ? support : await operator.actor('OPERATOR_SUPPORT');
    const r = route.endsWith('refresh') ? await operator.refreshRaw(actor.tokens.refreshToken) : await operator.signOut(actor.tokens);
    expect(r.status(), `Support ${route}`).toBe(route.endsWith('refresh') ? 200 : 204);
  }
});

test(`${id(7)} Every salon staff role and the customer principal are refused every operator action`, async ({ operator, identity }) => {
  const admin = await operator.actor('OPERATOR_ADMIN');
  const plan = await operator.plan(admin);
  const { created } = await operator.salon(admin, plan.id);
  const before = await totals(operator, admin);
  const tenantActors: { name: string; tokens: Tokens }[] = [];
  for (const role of ROLES) tenantActors.push({ name: role, tokens: (await identity.staff(alpha, role)).tokens });
  tenantActors.push({ name: 'customer', tokens: (await new Customers(identity).login(alpha)).tokens });
  expect(tenantActors.length, 'five staff roles and a customer').toBe(6);
  for (const actor of tenantActors) {
    for (const action of protectedActions(plan.id, created.id)) {
      const data = 'body' in action ? action.body() : undefined;
      const headers = idempotency(action.method);
      await operatorProblem(await operator.call(action.method, action.route, { tokens: actor.tokens, data, headers }), 401, 'invalid_session');
      await operatorProblem(await operator.call(action.method, action.route, { tokens: actor.tokens, data, headers, host: alpha.hostname }), 404, 'not_found');
    }
  }
  expect(await totals(operator, admin), 'nothing created by tenant credentials').toEqual(before);
});

test(`${id(8)} An operator credential authenticates no tenant identity or workspace`, async ({ operator, identity }) => {
  const admin = await operator.actor('OPERATOR_ADMIN');
  const owner = await identity.staff(alpha, 'OWNER');
  const auditBefore = (await identity.audit(owner)).total;
  for (const route of ['/me', '/staff', '/audit', '/sessions']) {
    for (const host of [alpha.hostname, null]) {
      await operatorProblem(await identity.call(host ? alpha : null, 'GET', route, undefined, admin.tokens), 401, 'invalid_session');
    }
  }
  const audit = await identity.audit(owner);
  expect(audit.total, 'no tenant audit entry for the operator').toBe(auditBefore);
  expect(JSON.stringify(audit.items)).not.toContain(admin.principal.id);
});

test(`${id(9)} Matching operator and staff email and password grant no extra authority`, async ({ operator, identity }) => {
  const shared = operatorAccount('OPERATOR_ADMIN');
  await operator.seed(shared);
  const staff = { email: shared.email, mobile: '', password: shared.password, role: 'MANAGER' as const };
  await identity.seed(alpha, [staff]);
  const asStaff = await identity.login(alpha, staff);
  const asOperator = await operator.login(shared);
  expect(asStaff.principal.kind).toBe('staff');
  expect(asOperator.principal.kind).toBe('operator');
  await operatorProblem(await operator.call('GET', '/me', { tokens: asStaff.tokens }), 401, 'invalid_session');
  await operatorProblem(await operator.call('GET', '/plans', { tokens: asStaff.tokens }), 401, 'invalid_session');
  await operatorProblem(await identity.call(alpha, 'GET', '/me', undefined, asOperator.tokens), 401, 'invalid_session');
});

test(`${id(11)} Expired, signed-out, revoked and disabled operator sessions authorize nothing`, async ({ operator, clock }) => {
  const expired = await operator.actor('OPERATOR_ADMIN');
  await clock.advance(5 * 60_000 + 1_000);
  await operatorProblem(await operator.call('GET', '/me', { tokens: expired.tokens }), 401, 'invalid_session');
  await clock.real();
  const signedOut = await operator.actor('OPERATOR_ADMIN');
  expect((await operator.signOut(signedOut.tokens)).status()).toBe(204);
  await operatorProblem(await operator.call('GET', '/me', { tokens: signedOut.tokens }), 401, 'invalid_session');
  await operatorProblem(await operator.refreshRaw(signedOut.tokens.refreshToken), 401, 'invalid_session');
  const absolute = await operator.actor('OPERATOR_ADMIN');
  await clock.advance(7 * DAY + 1_000);
  await operatorProblem(await operator.refreshRaw(absolute.tokens.refreshToken), 401, 'invalid_session');
  await clock.real();
  const disabled = await operator.actor('OPERATOR_SUPPORT');
  const r = await operator.hook('disableOperator', { disabled: true }, { id: disabled.principal.id });
  if (r.status() === 404) throw new Error('ENVIRONMENT: the disable-operator hook is not available (confirm its path in the S02 handoff)');
  expect(r.status()).toBeLessThan(300);
  await operatorProblem(await operator.call('GET', '/me', { tokens: disabled.tokens }), 401, 'invalid_session');
});

test(`${id(12)} Refresh rotates once and reuse revokes the whole family`, async ({ operator }) => {
  const admin = await operator.actor('OPERATOR_ADMIN');
  const second = await operator.tokens(await operator.refreshRaw(admin.tokens.refreshToken));
  expect(second.refreshToken).not.toBe(admin.tokens.refreshToken);
  await operatorProblem(await operator.refreshRaw(admin.tokens.refreshToken), 401, 'invalid_session');
  await operatorProblem(await operator.refreshRaw(second.refreshToken), 401, 'invalid_session');
  await operatorProblem(await operator.call('GET', '/me', { tokens: second }), 401, 'invalid_session');
});

test(`${id(13)} Concurrent renewals never leave two usable refresh families`, async ({ operator }) => {
  const admin = await operator.actor('OPERATOR_ADMIN');
  const outcomes = await Promise.all(Array.from({ length: 8 }, () => operator.refreshRaw(admin.tokens.refreshToken)));
  const winners = outcomes.filter(r => r.status() === 200);
  expect(winners.length, 'at most one renewal').toBeLessThanOrEqual(1);
  const issued = await Promise.all(winners.map(r => operator.tokens(r)));
  const stillUsable = [];
  for (const tokens of issued) if ((await operator.refreshRaw(tokens.refreshToken)).status() === 200) stillUsable.push(tokens);
  expect(stillUsable.length, 'never two usable families').toBeLessThanOrEqual(1);
});

test(`${id(15)} Address ceiling holds across distinct emails and forged forwarding headers`, async ({ operator }) => {
  const attempt = (n: number, extra: Record<string, string> = {}) => operator.signInRaw(
    { email: `absent-${n}-${randomUUID()}@example.invalid`, password: 'QA wrong password value' },
    { headers: { 'X-Forwarded-For': `192.0.2.${(n % 250) + 1}`, ...extra } });
  for (let n = 0; n < 30; n++) await operatorProblem(await attempt(n), 401, 'invalid_credentials');
  // A forged staff assertion (identity-and-access.md, `x-salon-client-assertion`) cannot choose the operator bucket.
  for (const extra of [{}, { 'X-Forwarded-For': '198.51.100.7' }, { 'x-salon-client-assertion': 'v1.forged.assertion' }] as Record<string, string>[]) {
    const r = await attempt(99, extra);
    await operatorProblem(r, 429, 'rate_limited');
    expect(Number(r.headers()['retry-after'])).toBeGreaterThan(0);
  }
});

test(`${id(16)} Concurrent failures cannot exceed the ceilings or reset MFA attempts`, async ({ operator }) => {
  const email = `absent-${randomUUID()}@example.invalid`;
  const outcomes = await Promise.all(Array.from({ length: 20 }, () => operator.signInRaw({ email, password: 'QA wrong password value' })));
  expect(outcomes.filter(r => r.status() === 401).length, 'ten counted failures').toBe(10);
  expect(outcomes.filter(r => r.status() === 429).length, 'the rest limited').toBe(10);
  const account = operatorAccount('OPERATOR_ADMIN');
  await operator.seed(account);
  const challenge = await operator.signIn(account);
  const wrong = await operator.wrongCode(account.totpSecret!);
  const failures = await Promise.all(Array.from({ length: 5 }, () => operator.verifyRaw(challenge, wrong)));
  expect(failures.every(r => r.status() === 401), 'five failures').toBe(true);
  await operatorProblem(await operator.verifyRaw(challenge, await operator.code(account.totpSecret!)), 401, 'invalid_credentials');
});

