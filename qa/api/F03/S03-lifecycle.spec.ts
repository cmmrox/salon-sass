/**
 * F03-S03 suspension, reactivation, closure and lifecycle authority.
 * Cases TC-F03-S03-001, -003, -005, -013 (cases.md). TC-F03-S03-004 joins this file at
 * feature-verify, when the background-state hook's path is in the S03 handoff.
 *
 * Every salon whose lifecycle changes is provisioned here: closure is irreversible and retires the
 * slug, so alpha, beta and gamma are only the salons that must stay unaffected.
 */
import { randomUUID } from 'node:crypto';

import { Customers } from '../../shared/customer-identity';
import { json, ROLES, type Tokens } from '../../shared/identity';
import type { components } from '@contracts/schema';
import {
  test, expect, alpha, DAY, NON_CONSOLE_HOSTS, fieldsOf, operatorJson, operatorProblem, ownerOf, salonInput,
  type Operator, type OperatorActor, type SalonDetail, type SalonInput,
} from '../../shared/operator';
import type { Identity } from '../../shared/identity';
import type { Salon } from '../../shared/tenants';

const id = (n: number) => `TC-F03-S03-${String(n).padStart(3, '0')}`;

async function owner(identity: Identity, salon: Salon, input: SalonInput): Promise<Tokens> {
  const challenge = await identity.signIn(salon, ownerOf(input));
  const enrollment = await json<components['schemas']['Enrollment']>(await identity.call(salon, 'POST', '/staff/mfa/enroll',
    { challengeId: challenge.challengeId, challengeToken: challenge.challengeToken }), 'Enrollment');
  return identity.verify(salon, challenge, enrollment.secret!);
}

async function provisioned(operator: Operator, admin: OperatorActor) {
  const plan = await operator.plan(admin);
  return operator.salon(admin, plan.id);
}

test(`${id(1)} Suspension refuses every salon endpoint on the next request without revoking sessions`, async ({ operator, identity }) => {
  const admin = await operator.actor();
  const { created, input, salon } = await provisioned(operator, admin);
  const tokens = await owner(identity, salon, input);
  expect((await identity.call(salon, 'GET', '/me', undefined, tokens)).status()).toBe(200);
  for (const reason of ['r'.repeat(9), 'r'.repeat(501)]) {
    const problem = await operatorProblem(await operator.suspend(admin, created.id, await operator.version(admin, created.id), reason), 400, 'bad_request');
    expect(fieldsOf(problem)).toContain('reason');
  }
  const suspended = await operatorJson<SalonDetail>(await operator.suspend(admin, created.id, await operator.version(admin, created.id), 'r'.repeat(10)), 'salon');
  expect(suspended.status).toBe('suspended');
  for (const route of ['/me', '/staff', '/audit', '/sessions']) {
    const r = await identity.call(salon, 'GET', route, undefined, tokens);
    const problem = await operatorProblem(r, 403, 'salon_suspended');
    expect(JSON.stringify(problem)).not.toContain(input.name);
  }
  expect((await operator.identity.api.getOn(salon.hostname, '/api/v1/salon')).status()).toBe(403);
  expect((await operator.identity.api.meta()).status(), 'meta still answers').toBe(200);
  const alphaOwner = await identity.staff(alpha, 'OWNER');
  expect((await identity.call(alpha, 'GET', '/staff', undefined, alphaOwner.tokens)).status(), 'alpha unaffected').toBe(200);
  // Past-due and read-only suspension rows join at feature-verify with the run-job hook (they need billing state).
});

test(`${id(3)} Reactivation restores the billing-implied state and the same sessions work again`, async ({ operator, identity }) => {
  const admin = await operator.actor();
  const { created, input, salon } = await provisioned(operator, admin);
  const tokens = await owner(identity, salon, input);
  await operatorProblem(await operator.reactivate(admin, created.id, await operator.version(admin, created.id)), 409, 'invalid_state');
  const stale = await operator.version(admin, created.id);
  expect((await operator.suspend(admin, created.id, stale)).status()).toBe(200);
  await operatorProblem(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 409, 'invalid_state');
  await operatorProblem(await operator.reactivate(admin, created.id, stale), 409, 'conflict');
  const history = async () => (await operatorJson<{ total: number }>(await operator.call('GET', `/salons/${created.id}/lifecycle`, { tokens: admin.tokens }), 'lifecyclePage')).total;
  const events = await history();
  const restored = await operatorJson<SalonDetail>(await operator.reactivate(admin, created.id, await operator.version(admin, created.id)), 'salon');
  expect(restored.status).toBe('active');
  expect(await history(), 'one reactivation event; the refused calls added none').toBe(events + 1);
  expect((await identity.call(salon, 'GET', '/me', undefined, tokens)).status(), 'the pre-suspension session works again').toBe(200);
  // The read_only restoration row (invoice 14+ days overdue) joins at feature-verify with the run-job hook.
});

test(`${id(5)} Closure needs the exact slug and a reason and then removes the salon from its hostname`, async ({ operator, identity }) => {
  const admin = await operator.actor();
  const { created, input, salon } = await provisioned(operator, admin);
  const tokens = await owner(identity, salon, input);
  const version = () => operator.version(admin, created.id);
  for (const confirmSlug of ['wrong-slug', created.slug.toUpperCase()]) {
    expect(fieldsOf(await operatorProblem(await operator.close(admin, created.id, await version(), confirmSlug), 400, 'bad_request'))).toContain('confirmSlug');
  }
  const noReason = await operator.call('POST', `/salons/${created.id}/closure`, { tokens: admin.tokens, data: { version: await version(), confirmSlug: created.slug } });
  expect(fieldsOf(await operatorProblem(noReason, 400, 'bad_request'))).toContain('reason');
  expect((await operator.salonDetail(admin, created.id)).status, 'refusals changed nothing').toBe('active');
  const closed = await operatorJson<SalonDetail & { closedAt: string; deleteAt: string }>(await operator.close(admin, created.id, await version(), created.slug), 'salon');
  expect(closed.status).toBe('closed');
  expect(Date.parse(closed.deleteAt) - Date.parse(closed.closedAt), 'deletion 30 days after closure').toBe(30 * DAY);
  await operatorProblem(await operator.close(admin, created.id, await version(), created.slug), 409, 'invalid_state');
  const unknown = await operator.identity.api.getOn(`qa-unknown-${randomUUID().slice(0, 8)}.localhost`, '/api/v1/salon');
  const gone = await operator.identity.api.getOn(salon.hostname, '/api/v1/salon');
  expect(gone.status()).toBe(404);
  expect((await gone.text()).replace(/"(traceId|instance)":"[^"]*"/g, ''), 'same answer as an unknown salon').toBe((await unknown.text()).replace(/"(traceId|instance)":"[^"]*"/g, ''));
  await operatorProblem(await identity.call(salon, 'GET', '/me', undefined, tokens), 404, 'not_found');
  await operatorProblem(await operator.createSalonRaw(admin, salonInput(created.planId, { slug: created.slug })), 409, 'slug_taken');
  // Cancelled messages and "no further invoice" join at feature-verify with the background-state and run-job hooks.
});

test(`${id(13)} Support, staff and customers are refused every lifecycle and export action on any host`, async ({ operator, identity }) => {
  const admin = await operator.actor();
  const support = await operator.actor('OPERATOR_SUPPORT');
  const { created } = await provisioned(operator, admin);
  const version = await operator.version(admin, created.id);
  const actions = [
    { name: 'suspend', method: 'POST', route: `/salons/${created.id}/suspension`, data: { version, reason: 'QA reason text' } },
    { name: 'reactivate', method: 'DELETE', route: `/salons/${created.id}/suspension`, data: { version, reason: 'QA reason text' } },
    { name: 'close', method: 'POST', route: `/salons/${created.id}/closure`, data: { version, confirmSlug: created.slug, reason: 'QA reason text' } },
    { name: 'lifecycle', method: 'GET', route: `/salons/${created.id}/lifecycle` },
    { name: 'export status', method: 'GET', route: `/salons/${created.id}/export` },
    { name: 'export archive', method: 'GET', route: `/salons/${created.id}/export/archive` },
  ];
  const tenant: Tokens[] = [];
  for (const role of ROLES) tenant.push((await identity.staff(alpha, role)).tokens);
  tenant.push((await new Customers(identity).login(alpha)).tokens);
  for (const action of actions) {
    await test.step(action.name, async () => {
      const send = (tokens?: Tokens, host?: string) => operator.call(action.method, action.route, { tokens, data: action.data, host });
      await operatorProblem(await send(support.tokens), 403, 'permission_denied');
      for (const tokens of tenant) await operatorProblem(await send(tokens), 401, 'invalid_session');
      await operatorProblem(await send(undefined), 401, 'invalid_session');
      for (const host of NON_CONSOLE_HOSTS) await operatorProblem(await send(admin.tokens, host), 404, 'not_found');
    });
  }
  const after = await operator.salonDetail(admin, created.id);
  expect(after.status, 'still active').toBe('active');
  expect(after.version, 'unchanged').toBe(version);
});
