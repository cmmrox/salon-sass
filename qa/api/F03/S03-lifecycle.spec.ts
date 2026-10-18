/**
 * F03-S03 suspension, reactivation, closure and lifecycle authority.
 * Cases TC-F03-S03-001, -003, -004, -005, -013 (cases.md).
 *
 * Every salon whose lifecycle changes is provisioned here: closure is irreversible and retires the
 * slug, so alpha, beta and gamma are only the salons that must stay unaffected. Hooks (run-job,
 * background state, email capture, identity provider control) are those of the S02/S03 handoffs.
 */
import { randomUUID } from 'node:crypto';

import { billAt, crossBoundary, firstInvoice } from '../../shared/billing';
import { Customers } from '../../shared/customer-identity';
import { ROLES, syntheticAccount, type Identity, type Tokens } from '../../shared/identity';
import {
  test, expect, alpha, DAY, SECOND, WRONG_HOSTS, comparable, countRows, fieldsOf, operatorJson, operatorProblem, ownerSession, plusMs,
  salonInput, sqlLiteral,
  type Operator, type OperatorActor, type SalonDetail, type TestClock,
} from '../../shared/operator';
import type { Salon } from '../../shared/tenants';

const id = (n: number) => `TC-F03-S03-${String(n).padStart(3, '0')}`;
const liveSessions = (salonId: string) => countRows('identity_session', `tenant_id = ${sqlLiteral(salonId)} AND revoked_at IS NULL`);

async function provisioned(operator: Operator, clock: TestClock, createdAt: string, method: 'card' | 'bank_transfer' = 'bank_transfer') {
  await clock.set(createdAt);
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  return { admin, plan, ...(await operator.salon(admin, plan.id, { subscription: { monthlyAmount: 200000, paymentMethod: method } })) };
}

async function suspend(operator: Operator, admin: OperatorActor, salonId: string, reason = 'r'.repeat(10)) {
  return operatorJson<SalonDetail>(await operator.suspend(admin, salonId, await operator.version(admin, salonId), reason), 'salon');
}

/** Salon endpoints the Owner and a customer use, all refused while suspended. */
async function expectSuspended(identity: Identity, salon: Salon, owner: Tokens, customer: Tokens, name: string) {
  for (const route of ['/me', '/staff', '/audit', '/sessions']) {
    const problem = await operatorProblem(await identity.call(salon, 'GET', route, undefined, owner), 403, 'salon_suspended');
    expect(JSON.stringify(problem), 'nothing of the salon').not.toContain(name);
  }
  await operatorProblem(await identity.call(salon, 'GET', '/customers/me', undefined, customer), 403, 'salon_suspended');
  await operatorProblem(await identity.call(salon, 'POST', '/staff', syntheticAccount('MANAGER'), owner), 403, 'salon_suspended');
  await operatorProblem(await identity.api.getOn(salon.hostname, '/api/v1/salon'), 403, 'salon_suspended');
}

test(`${id(1)} Suspension refuses every salon endpoint on the next request without revoking sessions`, async ({ operator, identity, clock }) => {
  test.setTimeout(180_000);
  const customers = new Customers(identity);
  const rows = [
    { state: 'active', createdAt: '2026-12-01T07:00:00Z', drive: async () => undefined },
    { state: 'past_due', createdAt: '2026-12-02T07:00:00Z', drive: async (o: Operator, c: TestClock, a: OperatorActor, s: string) =>
      crossBoundary(o, c, a, await firstInvoice(o, c, a, s), 'due') },
    { state: 'read_only', createdAt: '2026-12-03T07:00:00Z', drive: async (o: Operator, c: TestClock, a: OperatorActor, s: string) =>
      crossBoundary(o, c, a, await firstInvoice(o, c, a, s), 'read_only') },
  ];
  for (const row of rows) {
    await test.step(row.state, async () => {
      const { admin, created, input, salon } = await provisioned(operator, clock, row.createdAt);
      await row.drive(operator, clock, admin, created.id);
      if (row.state !== 'active') expect((await operator.subscription(admin, created.id)).state).toBe(row.state);
      const owner = await identity.login(salon, (await ownerSession(identity, salon, input)).account);
      const customer = await customers.login(salon);
      expect((await identity.call(salon, 'GET', '/me', undefined, owner.tokens)).status()).toBe(200);
      const sessions = liveSessions(created.id);
      expect(sessions, 'live staff and customer sessions').toBeGreaterThanOrEqual(2);
      const suspended = await suspend(operator, admin, created.id);
      expect(suspended.status).toBe('suspended');
      await expectSuspended(identity, salon, owner.tokens, customer.tokens, input.name);
      expect(liveSessions(created.id), 'sessions refused, not revoked').toBe(sessions);
      expect((await identity.api.meta()).status(), 'meta still answers').toBe(200);
    });
  }
  const { admin, created } = await provisioned(operator, clock, '2026-12-04T07:00:00Z');
  for (const reason of ['r'.repeat(9), 'r'.repeat(501), '']) {
    const problem = await operatorProblem(await operator.suspend(admin, created.id, await operator.version(admin, created.id), reason), 400, 'bad_request');
    expect(fieldsOf(problem)).toContain('reason');
  }
  expect((await operator.salonDetail(admin, created.id)).status, 'bad reasons changed nothing').toBe('active');
  const alphaOwner = await identity.staff(alpha, 'OWNER');
  expect((await identity.call(alpha, 'GET', '/staff', undefined, alphaOwner.tokens)).status(), 'alpha unaffected').toBe(200);
});

test(`${id(3)} Reactivation restores the billing-implied state and the same sessions work again`, async ({ operator, identity, clock }) => {
  test.setTimeout(120_000);
  const { admin, created, input, salon } = await provisioned(operator, clock, '2026-12-05T07:00:00Z');
  const owner = await ownerSession(identity, salon, input);
  await operatorProblem(await operator.reactivate(admin, created.id, await operator.version(admin, created.id)), 409, 'invalid_state');
  const stale = await operator.version(admin, created.id);
  await suspend(operator, admin, created.id);
  await operatorProblem(await operator.suspend(admin, created.id, await operator.version(admin, created.id)), 409, 'invalid_state');
  await operatorProblem(await operator.reactivate(admin, created.id, stale), 409, 'conflict');
  const events = (await operator.lifecycle(admin, created.id)).total;
  const restored = await operatorJson<SalonDetail>(await operator.reactivate(admin, created.id, await operator.version(admin, created.id)), 'salon');
  expect(restored.status).toBe('active');
  expect((await operator.lifecycle(admin, created.id)).total, 'one reactivation event; the refused calls added none').toBe(events + 1);
  expect((await identity.call(salon, 'GET', '/me', undefined, owner.tokens)).status(), 'the pre-suspension session works again').toBe(200);
  expect((await identity.call(salon, 'POST', '/staff', syntheticAccount('MANAGER'), owner.tokens)).status(), 'full access').toBe(201);

  // A salon whose invoice became 14 days overdue while suspended comes back read-only.
  const late = await provisioned(operator, clock, '2026-12-06T07:00:00Z');
  const lateOwner = await ownerSession(identity, late.salon, late.input);
  const invoice = await firstInvoice(operator, clock, late.admin, late.created.id);
  await suspend(operator, late.admin, late.created.id);
  await crossBoundary(operator, clock, late.admin, invoice, 'read_only');
  const back = await operatorJson<SalonDetail>(await operator.reactivate(late.admin, late.created.id, await operator.version(late.admin, late.created.id)), 'salon');
  expect(back.status).toBe('active');
  expect((await operator.subscription(late.admin, late.created.id)).state).toBe('read_only');
  const tokens = (await identity.login(late.salon, lateOwner.account)).tokens;
  await operatorProblem(await identity.call(late.salon, 'POST', '/staff', syntheticAccount('MANAGER'), tokens), 403, 'salon_read_only');
  expect((await identity.call(late.salon, 'GET', '/staff', undefined, tokens)).status(), 'reads work').toBe(200);
});

test(`${id(4)} While suspended, billing continues and messages and tenant jobs wait until reactivation`, async ({ operator, clock }) => {
  test.setTimeout(300_000);
  const { admin, created } = await provisioned(operator, clock, '2026-12-07T07:00:00Z', 'card');
  const active = await operator.salon(admin, (await operator.salonDetail(admin, created.id)).plan!.id);
  await suspend(operator, admin, created.id);
  // Billing continues: the invoice is issued and its card charge request waits in the salon's outbox.
  const subscription = await operator.subscription(admin, created.id);
  await billAt(operator, clock, subscription.trialEndsAt);
  await operator.renew(admin);
  const invoices = await operator.invoices(admin, created.id);
  expect(invoices.total, 'the invoice is issued while suspended').toBe(1);
  const waiting = await operator.background(created.id);
  expect(waiting.status).toBe('suspended');
  expect(waiting.outbox.waiting, 'the charge request waits').toBeGreaterThan(0);
  expect(waiting.outbox.delivered, 'nothing delivered').toBe(0);
  await operator.pay(admin, invoices.items[0]);
  expect((await operator.invoice(admin, invoices.items[0].id)).status, 'a transfer is recorded while suspended').toBe('paid');
  // A due tenant job is skipped for the suspended salon and run for an active one.
  const before = { suspended: (await operator.background(created.id)).jobs, active: (await operator.background(active.created.id)).jobs };
  await clock.advance(2 * 3_600_000);
  await expect.poll(async () => (await operator.background(created.id)).jobs.skipped, { timeout: 120_000, intervals: [1000, 2000] })
    .toBeGreaterThan(before.suspended.skipped);
  await expect.poll(async () => (await operator.background(active.created.id)).jobs.run, { timeout: 120_000, intervals: [1000, 2000] })
    .toBeGreaterThan(before.active.run);
  const skipped = await operator.background(created.id);
  expect(skipped.jobs.run, 'no tenant job ran for the suspended salon').toBe(before.suspended.run);
  expect(skipped.outbox.delivered, 'still nothing delivered').toBe(0);
  await operator.renew(admin);
  await operatorJson(await operator.reactivate(admin, created.id, await operator.version(admin, created.id)), 'salon');
  await expect.poll(async () => (await operator.background(created.id)).outbox.waiting, { timeout: 30_000, intervals: [500, 1000] }).toBe(0);
  expect((await operator.background(created.id)).outbox.delivered, 'the waiting messages are delivered').toBeGreaterThan(0);
  const runsBefore = (await operator.background(created.id)).jobs.run;
  await clock.advance(2 * 3_600_000);
  await expect.poll(async () => (await operator.background(created.id)).jobs.run, { timeout: 120_000, intervals: [1000, 2000] }).toBeGreaterThan(runsBefore);
});

test(`${id(5)} Closure needs the exact slug and a reason and then removes the salon from its hostname`, async ({ operator, identity, clock }) => {
  const { admin, created, input, salon } = await provisioned(operator, clock, '2026-12-08T07:00:00Z');
  const owner = await ownerSession(identity, salon, input);
  // A customer code whose delivery keeps failing stays pending until closure cancels it.
  const customers = new Customers(identity);
  await customers.control(salon, 'sms', 50, 0);
  await customers.challenge(salon);
  await expect.poll(async () => (await operator.background(created.id)).outbox.waiting, { timeout: 10_000 }).toBeGreaterThan(0);
  const version = () => operator.version(admin, created.id);
  for (const confirmSlug of ['wrong-slug', created.slug.toUpperCase()]) {
    expect(fieldsOf(await operatorProblem(await operator.close(admin, created.id, await version(), confirmSlug), 400, 'bad_request'))).toContain('confirmSlug');
  }
  const noReason = await operator.call('POST', `/salons/${created.id}/closure`, { tokens: admin.tokens, data: { version: await version(), confirmSlug: created.slug } });
  expect(fieldsOf(await operatorProblem(noReason, 400, 'bad_request'))).toContain('reason');
  expect((await operator.salonDetail(admin, created.id)).status, 'refusals changed nothing').toBe('active');
  const closed = await operatorJson<SalonDetail>(await operator.close(admin, created.id, await version(), created.slug), 'salon');
  expect(closed.status).toBe('closed');
  expect(Date.parse(closed.deleteAt!) - Date.parse(closed.closedAt!), 'deletion 30 days after closure').toBe(30 * DAY);
  await operatorProblem(await operator.close(admin, created.id, await version(), created.slug), 409, 'invalid_state');
  const unknown = await identity.api.getOn(`qa-unknown-${randomUUID().slice(0, 8)}.localhost`, '/api/v1/salon');
  const gone = await identity.api.getOn(salon.hostname, '/api/v1/salon');
  expect(gone.status()).toBe(404);
  expect(comparable(await gone.text()), 'the same answer as an unknown salon').toBe(comparable(await unknown.text()));
  await operatorProblem(await identity.call(salon, 'GET', '/me', undefined, owner.tokens), 404, 'not_found');
  expect(liveSessions(created.id), 'every session revoked').toBe(0);
  const background = await operator.background(created.id);
  expect(background.outbox.waiting, 'pending messages cancelled').toBe(0);
  expect(background.outbox.cancelled).toBeGreaterThan(0);
  // No invoice when the trial end passes; jobs neither run nor are skipped.
  const jobs = background.jobs;
  await billAt(operator, clock, plusMs(closed.createdAt, 14 * DAY + SECOND));
  await operator.renew(admin);
  expect((await operator.invoices(admin, created.id)).total, 'billing stopped').toBe(0);
  const after = await operator.background(created.id);
  expect({ run: after.jobs.run, skipped: after.jobs.skipped }, 'jobs stopped').toEqual({ run: jobs.run, skipped: jobs.skipped });
  await operatorProblem(await operator.createSalonRaw(admin, salonInput(created.planId, { slug: created.slug })), 409, 'slug_taken');
});

test(`${id(13)} Support, staff and customers are refused every lifecycle and export action on any host`, async ({ operator, identity }) => {
  const admin = await operator.actor();
  const support = await operator.actor('OPERATOR_SUPPORT');
  const { created } = await operator.salon(admin, (await operator.plan(admin)).id);
  const version = await operator.version(admin, created.id);
  // Lifecycle history needs operator.salon.read, which Support holds from S04 (operator-console.md).
  const actions = [
    { name: 'suspend', method: 'POST', route: `/salons/${created.id}/suspension`, data: { version, reason: 'QA reason text' }, support: 403 },
    { name: 'reactivate', method: 'DELETE', route: `/salons/${created.id}/suspension`, data: { version, reason: 'QA reason text' }, support: 403 },
    { name: 'close', method: 'POST', route: `/salons/${created.id}/closure`, data: { version, confirmSlug: created.slug, reason: 'QA reason text' }, support: 403 },
    { name: 'lifecycle', method: 'GET', route: `/salons/${created.id}/lifecycle`, support: 200 },
    { name: 'export status', method: 'GET', route: `/salons/${created.id}/export`, support: 403 },
    { name: 'export archive', method: 'GET', route: `/salons/${created.id}/export/archive`, support: 403 },
  ];
  const tenant: Tokens[] = [];
  for (const role of ROLES) tenant.push((await identity.staff(alpha, role)).tokens);
  tenant.push((await new Customers(identity).login(alpha)).tokens);
  expect(tenant.length, 'five staff roles and a customer').toBe(6);
  const history = (await operator.lifecycle(admin, created.id)).total;
  for (const action of actions) {
    await test.step(action.name, async () => {
      const send = (tokens?: Tokens, host?: string) => operator.call(action.method, action.route, { tokens, data: action.data, host });
      if (action.support === 403) await operatorProblem(await send(support.tokens), 403, 'permission_denied');
      else await operatorJson(await send(support.tokens), 'lifecyclePage');
      for (const tokens of tenant) await operatorProblem(await send(tokens), 401, 'invalid_session');
      await operatorProblem(await send(undefined), 401, 'invalid_session');
      for (const host of WRONG_HOSTS) await operatorProblem(await send(admin.tokens, host), 404, 'not_found');
    });
  }
  const after = await operator.salonDetail(admin, created.id);
  expect(after.status, 'still active').toBe('active');
  expect(after.version, 'unchanged').toBe(version);
  expect((await operator.lifecycle(admin, created.id)).total, 'no lifecycle event').toBe(history);
});
