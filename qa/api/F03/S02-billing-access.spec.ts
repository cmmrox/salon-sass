/**
 * F03-S02 subscription creation, an unbilled legacy salon, and operator billing authority.
 * Cases TC-F03-S02-001, -002, -003, -022 (cases.md).
 *
 * Written against the S02 spec and operator-console.md, "Subscriptions, invoices and entitlements",
 * with the unbilled-salon hook of the S02 handoff. No real charge or message is involved.
 */
import { randomUUID } from 'node:crypto';

import { Customers } from '../../shared/customer-identity';
import { ROLES, json, syntheticAccount, type Tokens } from '../../shared/identity';
import {
  test, expect, alpha, DAY, WRONG_HOSTS, contractJson, fieldsOf, operatorJson, operatorProblem, salonInput,
  type Entitlements, type OperatorActor, type Operator, type Subscription,
} from '../../shared/operator';

const id = (n: number) => `TC-F03-S02-${String(n).padStart(3, '0')}`;
const ENTITLEMENTS = '/api/v1/salon/entitlements';

function billingActions(salonId: string, invoiceId: string, planId: string, version: number) {
  return [
    { name: 'subscription read', method: 'GET', route: `/salons/${salonId}/subscription` },
    { name: 'invoice list', method: 'GET', route: `/salons/${salonId}/invoices` },
    { name: 'invoice detail', method: 'GET', route: `/invoices/${invoiceId}` },
    { name: 'start subscription', method: 'POST', route: `/salons/${salonId}/subscription`, data: { monthlyAmount: 1000, paymentMethod: 'card' }, key: true },
    { name: 'change subscription', method: 'PATCH', route: `/salons/${salonId}/subscription`, data: { version, monthlyAmount: 1 } },
    { name: 'move plan', method: 'PUT', route: `/salons/${salonId}/plan`, data: { version, planId } },
    { name: 'record transfer', method: 'POST', route: `/invoices/${invoiceId}/payments`, data: { reference: 'QA-REF', amount: 1, receivedOn: '2026-01-01' }, key: true },
  ];
}

async function state(operator: Operator, admin: OperatorActor, salonId: string) {
  const subscription = await operator.subscription(admin, salonId);
  const invoices = await operator.invoices(admin, salonId);
  return { subscription, invoices: invoices.total, plan: (await operator.salonDetail(admin, salonId)).plan };
}

test(`${id(1)} A salon created with a subscription is trialing until exactly 14 days after creation`, async ({ operator, clock }) => {
  await clock.set('2026-11-02T08:15:30Z');
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  for (const subscription of [{ monthlyAmount: 0, paymentMethod: 'card' }, { monthlyAmount: 100_000_000, paymentMethod: 'bank_transfer' }] as const) {
    const { created } = await operator.salon(admin, plan.id, { subscription });
    const read = await operator.subscription(admin, created.id);
    expect(read).toMatchObject({ state: 'trialing', monthlyAmount: subscription.monthlyAmount, currency: 'LKR', paymentMethod: subscription.paymentMethod });
    expect(Date.parse(read.trialEndsAt) - Date.parse(created.createdAt), 'trial ends exactly 14 days after creation').toBe(14 * DAY);
    expect(typeof read.version).toBe('number');
    expect(read.plan).toEqual({ id: plan.id, key: plan.key, name: plan.name });
    expect(read.readOnlyAt).toBeNull();
  }
});

test(`${id(2)} A missing or invalid monthly amount or method creates no salon`, async ({ operator }) => {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const before = (await operator.salonPage(admin, '?size=1')).total;
  const rows: [string, unknown, string][] = [
    ['no subscription', undefined, 'subscription'],
    ['amount missing', { paymentMethod: 'card' }, 'subscription.monthlyAmount'],
    ['amount -1', { monthlyAmount: -1, paymentMethod: 'card' }, 'subscription.monthlyAmount'],
    ['amount 1.5', { monthlyAmount: 1.5, paymentMethod: 'card' }, 'subscription.monthlyAmount'],
    ['amount "100"', { monthlyAmount: '100', paymentMethod: 'card' }, 'subscription.monthlyAmount'],
    ['amount 100000001', { monthlyAmount: 100_000_001, paymentMethod: 'card' }, 'subscription.monthlyAmount'],
    ['method missing', { monthlyAmount: 100 }, 'subscription.paymentMethod'],
    ['method paypal', { monthlyAmount: 100, paymentMethod: 'paypal' }, 'subscription.paymentMethod'],
    ['unknown field', { monthlyAmount: 100, paymentMethod: 'card', discount: 10 }, 'subscription.discount'],
  ];
  for (const [label, subscription, field] of rows) {
    await test.step(label, async () => {
      const body = salonInput(plan.id) as Record<string, unknown>;
      if (subscription === undefined) delete body.subscription; else body.subscription = subscription;
      const problem = await operatorProblem(await operator.createSalonRaw(admin, body), 400, 'bad_request');
      expect(fieldsOf(problem).some(f => f === field || f.endsWith(field.split('.').pop()!)), `${label}: ${fieldsOf(problem)}`).toBe(true);
      expect((await operator.identity.api.getOn(`${body.slug}.localhost`, '/api/v1/salon')).status()).toBe(404);
    });
  }
  expect((await operator.salonPage(admin, '?size=1')).total, 'no salon created').toBe(before);
});

test(`${id(22)} Support, staff and customers are refused every operator billing action on any host`, async ({ operator, identity, clock }) => {
  const admin = await operator.actor();
  const support = await operator.actor('OPERATOR_SUPPORT');
  const plan = await operator.plan(admin);
  const other = await operator.plan(admin, { staffSeats: 10 });
  const { created } = await operator.salon(admin, plan.id);
  const version = await operator.version(admin, created.id);
  // An open invoice exists, so refusals are not explained by an unknown id (trial end crossed).
  await clock.set((await operator.subscription(admin, created.id)).trialEndsAt);
  await operator.runBilling();
  await operator.renew(admin);
  const invoices = await operator.invoices(admin, created.id);
  expect(invoices.total, 'the salon has an invoice').toBe(1);
  const actions = billingActions(created.id, invoices.items[0].id, other.id, version);
  const before = await state(operator, admin, created.id);
  const tenant: { name: string; tokens: Tokens }[] = [];
  for (const role of ROLES) tenant.push({ name: role, tokens: (await identity.staff(alpha, role)).tokens });
  tenant.push({ name: 'customer', tokens: (await new Customers(identity).login(alpha)).tokens });
  await operator.renew(support);
  for (const action of actions) {
    const send = (tokens: Tokens | undefined, host?: string) => operator.call(action.method, action.route, {
      tokens, data: action.data, host, headers: action.key ? { 'Idempotency-Key': randomUUID() } : {} });
    await test.step(action.name, async () => {
      await operatorProblem(await send(support.tokens), 403, 'permission_denied');
      for (const actor of tenant) await operatorProblem(await send(actor.tokens), 401, 'invalid_session');
      await operatorProblem(await send(undefined), 401, 'invalid_session');
      for (const host of WRONG_HOSTS) await operatorProblem(await send(admin.tokens, host), 404, 'not_found');
    });
  }
  expect(await state(operator, admin, created.id), 'denied calls changed nothing').toEqual(before);
});

test(`${id(3)} An unbilled legacy salon works without a subscription until an Admin starts one, once`, async ({ operator, identity, clock }) => {
  const start = '2026-11-10T09:00:00.000Z';
  await clock.set(start);
  const admin = await operator.actor();
  const legacy = await operator.unbilledSalon();
  await operatorProblem(await operator.subscriptionRaw(admin, legacy.id), 404, 'not_found');
  const detail = await operator.salonDetail(admin, legacy.id);
  expect({ plan: detail.plan, currency: detail.currency, status: detail.status }, 'a legacy salon: no plan, no currency').toEqual({ plan: null, currency: null, status: 'active' });
  const owner = await identity.staff(legacy, 'OWNER');
  const entitlements = await contractJson<Entitlements>(await identity.api.send('GET', ENTITLEMENTS, { headers: { Host: legacy.hostname, Authorization: `Bearer ${owner.tokens.accessToken}` } }), 'Entitlements');
  expect(entitlements.subscription, 'no subscription').toBeNull();
  expect((await identity.call(legacy, 'POST', '/staff', syntheticAccount('MANAGER'), owner.tokens)).status(), 'the salon works normally').toBe(201);
  // A salon without a currency must be given one when billing starts (decision 051).
  const body = { monthlyAmount: 150000, paymentMethod: 'bank_transfer' };
  expect(fieldsOf(await operatorProblem(await operator.startSubscription(admin, legacy.id, body), 400, 'bad_request'))).toContain('currency');
  await operatorProblem(await operator.subscriptionRaw(admin, legacy.id), 404, 'not_found');
  const key = randomUUID();
  const started = await operatorJson<Subscription>(await operator.startSubscription(admin, legacy.id, { ...body, currency: 'LKR' }, key), 'subscription', 201);
  expect(started).toMatchObject({ state: 'trialing', monthlyAmount: 150000, currency: 'LKR', paymentMethod: 'bank_transfer' });
  expect(Date.parse(started.trialEndsAt) - Date.parse(start), 'its own 14-day trial from the start').toBeGreaterThanOrEqual(14 * DAY);
  expect(Date.parse(started.trialEndsAt) - Date.parse(start), 'its own 14-day trial from the start').toBeLessThan(14 * DAY + 60_000);
  const replay = await operatorJson<Subscription>(await operator.startSubscription(admin, legacy.id, { ...body, currency: 'LKR' }, key), 'subscription', 201);
  expect(replay, 'the same key replays the original').toEqual(started);
  await operatorProblem(await operator.startSubscription(admin, legacy.id, { ...body, currency: 'LKR' }), 409, 'invalid_state');
  expect(await operator.subscription(admin, legacy.id), 'the second start changed nothing').toEqual(started);
});
