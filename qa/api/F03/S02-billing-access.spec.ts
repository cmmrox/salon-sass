/**
 * F03-S02 subscription creation and operator billing authority.
 * Cases TC-F03-S02-001, -002, -022 (cases.md). TC-F03-S02-003 joins this file at feature-verify,
 * when the unbilled legacy salon hook's path is in the S02 handoff.
 *
 * Prepared during the F03 build against the S02 spec and operator-console.md, "Subscriptions,
 * invoices and entitlements". No real charge or message is involved.
 */
import { randomUUID } from 'node:crypto';

import { Customers } from '../../shared/customer-identity';
import { ROLES, type Tokens } from '../../shared/identity';
import {
  test, expect, alpha, DAY, NON_CONSOLE_HOSTS, fieldsOf, operatorJson, operatorProblem, salonInput,
  type OperatorActor, type Operator,
} from '../../shared/operator';

const id = (n: number) => `TC-F03-S02-${String(n).padStart(3, '0')}`;
type Subscription = { version: number; state: string; monthlyAmount: number; currency: string; paymentMethod: string; trialEndsAt: string };

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
  const subscription = await operatorJson<Subscription>(await operator.subscription(admin, salonId), 'subscription');
  const invoices = await operatorJson<{ total: number }>(await operator.call('GET', `/salons/${salonId}/invoices`, { tokens: admin.tokens }), 'invoicePage');
  return { subscription, invoices: invoices.total, plan: (await operator.salonDetail(admin, salonId)).plan };
}

test(`${id(1)} A salon created with a subscription is trialing until exactly 14 days after creation`, async ({ operator, clock }) => {
  await clock.set('2026-11-02T08:15:30Z');
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  for (const subscription of [{ monthlyAmount: 0, paymentMethod: 'card' }, { monthlyAmount: 100_000_000, paymentMethod: 'bank_transfer' }] as const) {
    const { created } = await operator.salon(admin, plan.id, { subscription });
    const read = await operatorJson<Subscription>(await operator.subscription(admin, created.id), 'subscription');
    expect(read).toMatchObject({ state: 'trialing', monthlyAmount: subscription.monthlyAmount, currency: 'LKR', paymentMethod: subscription.paymentMethod });
    expect(Date.parse(read.trialEndsAt) - Date.parse(created.createdAt), 'trial ends exactly 14 days after creation').toBe(14 * DAY);
    expect(typeof read.version).toBe('number');
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

test(`${id(22)} Support, staff and customers are refused every operator billing action on any host`, async ({ operator, identity }) => {
  const admin = await operator.actor();
  const support = await operator.actor('OPERATOR_SUPPORT');
  const plan = await operator.plan(admin);
  const other = await operator.plan(admin, { staffSeats: 10 });
  const { created } = await operator.salon(admin, plan.id);
  const version = await operator.version(admin, created.id);
  // An invoice id that exists is not needed for a refusal; an unknown one proves no lookup happens first.
  const actions = billingActions(created.id, randomUUID(), other.id, version);
  const before = await state(operator, admin, created.id);
  const tenant: { name: string; tokens: Tokens }[] = [];
  for (const role of ROLES) tenant.push({ name: role, tokens: (await identity.staff(alpha, role)).tokens });
  tenant.push({ name: 'customer', tokens: (await new Customers(identity).login(alpha)).tokens });
  for (const action of actions) {
    const send = (tokens: Tokens | undefined, host?: string) => operator.call(action.method, action.route, {
      tokens, data: action.data, host, headers: action.key ? { 'Idempotency-Key': randomUUID() } : {} });
    await test.step(action.name, async () => {
      await operatorProblem(await send(support.tokens), 403, 'permission_denied');
      for (const actor of tenant) await operatorProblem(await send(actor.tokens), 401, 'invalid_session');
      await operatorProblem(await send(undefined), 401, 'invalid_session');
      for (const host of NON_CONSOLE_HOSTS) await operatorProblem(await send(admin.tokens, host), 404, 'not_found');
    });
  }
  expect(await state(operator, admin, created.id), 'denied calls changed nothing').toEqual(before);
});
