/**
 * F03-S01 runtime: ceilings and plans across an API restart, database outages, and the exposure of
 * the local operator hooks. Cases TC-F03-S01-014, -017, -037, -039 (cases.md).
 *
 * These tests stop and start local services through infra/scripts/local-service.sh and always
 * restore them. Prepared during the F03 build; never run outside the recorded F03 feature pass.
 */
import os from 'node:os';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';

import { apiUrl } from '../../shared/env';
import { localService, mark } from '../../shared/identity-runtime';
import { committedContract } from '../../shared/openapi';
import {
  test, expect, alpha, beta, gamma, CONTRACT, operatorAccount, operatorJson, operatorProblem, planInput, salonInput,
  type Plan,
} from '../../shared/operator';

const id = (n: number) => `TC-F03-S01-${String(n).padStart(3, '0')}`;
const LIMIT_MS = 5_000;

function restartApi() {
  localService('stop', 'api');
  localService('start', 'api');
}

function databaseAcceptsConnections(): boolean {
  try { execFileSync('docker', ['exec', 'salon-postgres', 'pg_isready', '-q'], { timeout: 3_000 }); return true; } catch { return false; }
}

test(`${id(14)} Email ceiling counts known and unknown operators and survives a restart`, async ({ operator, clock }) => {
  const known = operatorAccount('OPERATOR_ADMIN');
  await operator.seed(known);
  const unknown = { email: `absent-${randomUUID()}@example.invalid`, password: 'QA wrong password value' };
  const bodies: string[] = [];
  for (let n = 0; n < 10; n++) {
    const r = await operator.signInRaw({ email: n % 2 ? known.email.toUpperCase() : known.email, password: 'QA wrong password value' });
    await operatorProblem(r, 401, 'invalid_credentials');
    bodies.push((await r.text()).replace(/"(traceId|instance)":"[^"]*"/g, ''));
  }
  const limited = await operator.signInRaw({ email: known.email, password: 'QA wrong password value' });
  await operatorProblem(limited, 429, 'rate_limited');
  expect(Number(limited.headers()['retry-after'])).toBeGreaterThan(0);
  restartApi();
  await operator.identity.api.reconnect();
  await operatorProblem(await operator.signInRaw({ email: known.email, password: known.password }), 429, 'rate_limited');
  for (let n = 0; n < 10; n++) {
    const r = await operator.signInRaw(unknown);
    await operatorProblem(r, 401, 'invalid_credentials');
    expect((await r.text()).replace(/"(traceId|instance)":"[^"]*"/g, ''), 'unknown and known look the same').toBe(bodies[0]);
  }
  await operatorProblem(await operator.signInRaw(unknown), 429, 'rate_limited');
  await clock.advance(15 * 60_000 + 1_000);
  await operatorProblem(await operator.signInRaw(unknown), 401, 'invalid_credentials');
});

test(`${id(17)} An Admin creates a plan whose exact values persist and can be assigned`, async ({ operator }) => {
  const admin = await operator.actor();
  const input = planInput({ staffSeats: 1, branches: 1_000_000, messageAllowance: 0, customDomain: true, dedicatedApp: true });
  const created = await operatorJson<Plan>(await operator.createPlanRaw(admin, input), 'plan', 201);
  expect(created).toMatchObject(input);
  const read = async () => operatorJson<Plan>(await operator.call('GET', `/plans/${created.id}`, { tokens: admin.tokens }), 'plan');
  expect(await read()).toEqual(created);
  expect((await operator.planPage(admin, `?q=${input.key}`)).items).toEqual([created]);
  restartApi();
  await operator.identity.api.reconnect();
  expect(await read(), 'identical after restart').toEqual(created);
  const { created: salon } = await operator.salon(admin, created.id);
  expect((await operator.salonDetail(admin, salon.id)).plan).toEqual({ id: created.id, key: created.key, name: created.name });
});

test(`${id(37)} A stopped or silent database is reported within five seconds and recovers without restart`, async ({ operator }) => {
  test.setTimeout(240_000);
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  for (const mode of ['down', 'silent'] as const) {
    await test.step(mode, async () => {
      const input = salonInput(plan.id);
      const key = randomUUID();
      try {
        const inFlight = operator.salonPage(admin).catch(() => undefined);
        mark(`f03-db-${mode}-begin`);
        localService(mode === 'down' ? 'stop' : 'pause', 'postgres');
        await inFlight;
        for (const send of [() => operator.call('GET', '/salons', { tokens: admin.tokens }), () => operator.createSalonRaw(admin, input, key)]) {
          const started = Date.now();
          const r = await send();
          expect(Date.now() - started, `${mode}: answered within the outage deadline`).toBeLessThanOrEqual(LIMIT_MS);
          await operatorProblem(r, 503, 'service_unavailable');
        }
      } finally {
        localService(mode === 'down' ? 'start' : 'resume', 'postgres');
      }
      await expect.poll(databaseAcceptsConnections, { timeout: 60_000, intervals: [100] }).toBe(true);
      const back = mark(`f03-db-${mode}-accepting`);
      await expect.poll(async () => (await operator.call('GET', '/salons', { tokens: admin.tokens })).status(), { timeout: LIMIT_MS, intervals: [100] }).toBe(200);
      expect(Date.now() - back, `${mode}: recovered within five seconds of the database`).toBeLessThanOrEqual(LIMIT_MS);
      const retried = await operator.createSalonRaw(admin, input, key);
      expect(retried.status(), 'the uncertain create retried with its key').toBe(201);
      expect((await operator.salonPage(admin, `?q=${input.slug}`)).total, 'exactly one salon').toBe(1);
    });
  }
});

test(`${id(39)} Local operator hooks are loopback-only, absent from the contract and absent outside local`, async ({ operator }) => {
  const paths = Object.keys(committedContract().paths);
  expect(paths.length, 'the contract has paths').toBeGreaterThan(0);
  expect(paths.filter(p => p.startsWith('/local')), 'no local hook in the contract').toEqual([]);
  const external = Object.values(os.networkInterfaces()).flat().find(a => a && a.family === 'IPv4' && !a.internal)?.address;
  if (!external) throw new Error('ENVIRONMENT: no non-loopback IPv4 address to call the hooks through');
  const port = new URL(apiUrl).port;
  for (const hook of Object.values(CONTRACT.hooks)) {
    const url = `http://${external}:${port}${hook.path.replace(/\{[a-z]+\}/g, randomUUID())}`;
    const status = await fetch(url, { method: hook.method, headers: { Host: 'console.localhost', 'Content-Type': 'application/json' },
      body: hook.method === 'GET' ? undefined : '{}', signal: AbortSignal.timeout(5_000) }).then(r => r.status, () => 'unreachable');
    expect(['unreachable', 404], `${hook.method} ${hook.path} through ${external}`).toContain(status);
  }
  // Local restart keeps fixture identities and the fixed salons.
  const account = operatorAccount('OPERATOR_SUPPORT');
  await operator.seed(account);
  const fixed = await Promise.all([alpha, beta, gamma].map(async s => (await operator.identity.api.salon(s)).json()));
  restartApi();
  await operator.identity.api.reconnect();
  expect((await operator.login(account)).principal.role, 'fixture operator survives a local restart').toBe('OPERATOR_SUPPORT');
  expect(await Promise.all([alpha, beta, gamma].map(async s => (await operator.identity.api.salon(s)).json()))).toEqual(fixed);
  // The non-local start (hooks 404, no default operator created) is the second half of this case,
  // in qa/checks/F03/S01-database-boundary.sh, which starts a separate API process.
});
