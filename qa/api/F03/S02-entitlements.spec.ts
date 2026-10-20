/**
 * F03-S02 entitlements and read-only enforcement on the salon side: what a read-only salon still
 * allows, isolation of billing state between salons, staff-seat limits and their race, and who
 * reads entitlements and invoices. Cases TC-F03-S02-014, -015, -017, -018, -020 (cases.md).
 *
 * The read-only allowlist is the one operator-console.md ("As built", decision 051) documents:
 * staff sign-in, MFA, renewal, revoking one's own sessions, password change, customer sign-in and
 * the customer's own profile, export and deletion. Every other mutating tenant route is taken from
 * the committed contract, so a route added later is covered without editing this file.
 */
import { randomUUID } from 'node:crypto';

import { billAt, crossBoundary, firstInvoice, readOnlySalon } from '../../shared/billing';
import { Customers } from '../../shared/customer-identity';
import { Profiles } from '../../shared/customer-profile';
import { ROLES, json, syntheticAccount, type Identity, type Role, type Tokens } from '../../shared/identity';
import {
  test, expect, alpha, SECOND, fieldsOf, onSalonHost, operatorProblem, ownerSession, plusMs, tenantMutationRoutes,
  type Entitlements, type Operator, type TestClock,
} from '../../shared/operator';
import type { Salon } from '../../shared/tenants';

const id = (n: number) => `TC-F03-S02-${String(n).padStart(3, '0')}`;
const ENTITLEMENTS = '/api/v1/salon/entitlements';
const INVOICES = '/api/v1/salon/invoices';

/** The read-only allowlist (decision 051), as method and contract path. */
const EXEMPT = new Set([
  'POST /api/v1/identity/staff/sign-in', 'POST /api/v1/identity/staff/mfa/challenges', 'POST /api/v1/identity/staff/mfa/enroll',
  'POST /api/v1/identity/staff/mfa/verify', 'POST /api/v1/identity/sessions/refresh', 'DELETE /api/v1/identity/sessions',
  'DELETE /api/v1/identity/sessions/{id}', 'PUT /api/v1/identity/staff/password', 'POST /api/v1/identity/customers/challenges',
  'POST /api/v1/identity/customers/sign-in', 'PUT /api/v1/identity/customers/me', 'DELETE /api/v1/identity/customers/me',
  // Decision 053 (F03-S04): an Owner may revoke support access even while the salon is read-only.
  'DELETE /api/v1/salon/support-sessions/{id}',
]);

async function provisioned(operator: Operator, clock: TestClock, createdAt: string, staffSeats = 5) {
  await clock.set(createdAt);
  const admin = await operator.actor();
  const plan = await operator.plan(admin, { staffSeats });
  return { admin, plan, ...(await operator.salon(admin, plan.id)) };
}

async function usage(identity: Identity, salon: Salon, tokens: Tokens) {
  return (await json<Entitlements>(await onSalonHost(identity, salon, 'GET', ENTITLEMENTS, tokens), 'Entitlements')).usage.staffSeats;
}

test(`${id(14)} A read-only salon keeps reads and exempt identity actions and refuses every other mutation`, async ({ operator, identity, clock }) => {
  test.setTimeout(180_000);
  const { admin, created, input, salon } = await provisioned(operator, clock, '2026-11-14T07:00:00Z');
  const owner = await ownerSession(identity, salon, input);
  const manager = syntheticAccount('MANAGER');
  const victim = syntheticAccount('RECEPTIONIST');
  await identity.seed(salon, [manager, victim]);
  const customers = new Customers(identity);
  await readOnlySalon(operator, clock, admin, created.id);
  // Sign-in and MFA after the salon became read-only.
  const ownerNow = await identity.login(salon, owner.account);
  const managerNow = await identity.login(salon, manager);
  const staffBefore = await identity.list(ownerNow);
  const target = staffBefore.find(s => s.email === victim.email.toLowerCase())!;
  expect(target, 'the staff member to change exists').toBeTruthy();
  await test.step('every read still works', async () => {
    for (const route of ['/api/v1/identity/me', '/api/v1/identity/staff', '/api/v1/identity/audit', '/api/v1/identity/sessions', ENTITLEMENTS, INVOICES]) {
      expect((await onSalonHost(identity, salon, 'GET', route, ownerNow.tokens)).status(), route).toBe(200);
    }
    expect((await identity.api.salon(salon)).status(), 'the public salon read').toBe(200);
  });
  await test.step('every other tenant mutation is refused before any change', async () => {
    const bodies: Record<string, unknown> = {
      'POST /api/v1/identity/staff': syntheticAccount('MANAGER'),
      'PATCH /api/v1/identity/staff/{id}': { version: target.version, role: 'ACCOUNTANT' },
    };
    const refused = tenantMutationRoutes().filter(m => !EXEMPT.has(`${m.method} ${m.path}`));
    expect(refused.length, 'the contract has mutating tenant routes to refuse').toBeGreaterThan(0);
    for (const m of refused) {
      const route = m.path.replace('{id}', target.id);
      for (const tokens of [ownerNow.tokens, undefined]) {
        await operatorProblem(await onSalonHost(identity, salon, m.method, route, tokens, bodies[`${m.method} ${m.path}`]), 403, 'salon_read_only');
      }
    }
    // Refused by default, even where no handler exists (decision 051).
    await operatorProblem(await onSalonHost(identity, salon, 'POST', '/api/v1/identity/qa-unhandled', ownerNow.tokens, {}), 403, 'salon_read_only');
    expect(await identity.list(ownerNow), 'nothing changed').toEqual(staffBefore);
  });
  await test.step('exempt staff actions work', async () => {
    const renewed = await onSalonHost(identity, salon, 'POST', '/api/v1/identity/sessions/refresh', undefined, { refreshToken: managerNow.tokens.refreshToken });
    expect(renewed.status(), 'renewal').toBe(200);
    const fresh = await renewed.json() as Tokens;
    const password = { oldPassword: manager.password, newPassword: syntheticAccount().password };
    expect((await onSalonHost(identity, salon, 'PUT', '/api/v1/identity/staff/password', fresh, password)).status(), 'password change').toBe(200);
    const signedOut = await onSalonHost(identity, salon, 'DELETE', `/api/v1/identity/sessions/${ownerNow.tokens.sessionId}`, ownerNow.tokens);
    expect(signedOut.status(), 'sign-out of the own session').toBe(204);
  });
  await test.step('customer privacy rights work', async () => {
    const customer = await customers.login(salon);
    const profiles = new Profiles(identity);
    const current = await profiles.read(customer);
    expect((await identity.call(salon, 'PUT', '/customers/me', Profiles.updateBody(current, { name: 'QA Read Only Customer' }), customer.tokens)).status(), 'profile update').toBe(200);
    expect((await profiles.export(customer)).status(), 'export').toBe(200);
    expect((await profiles.delete(customer)).status(), 'deletion').toBe(204);
  });
});

test(`${id(15)} One salon's billing state, entitlements and invoices never reach another salon`, async ({ operator, identity, clock }) => {
  test.setTimeout(180_000);
  const a = await provisioned(operator, clock, '2026-11-15T07:00:00Z');
  const b = await operator.salon(a.admin, a.plan.id);
  const ownerA = await ownerSession(identity, a.salon, a.input);
  const ownerB = await ownerSession(identity, b.salon, b.input);
  const invoiceA = await firstInvoice(operator, clock, a.admin, a.created.id);
  await billAt(operator, clock, plusMs((await operator.subscription(a.admin, b.created.id)).trialEndsAt, SECOND));
  await operator.renew(a.admin);
  const invoiceB = (await operator.invoices(a.admin, b.created.id)).items[0];
  expect(invoiceB, 'salon B has an invoice').toBeTruthy();
  await operator.pay(a.admin, invoiceB);
  await crossBoundary(operator, clock, a.admin, invoiceA, 'read_only');
  expect((await operator.subscription(a.admin, a.created.id)).state).toBe('read_only');
  expect((await operator.subscription(a.admin, b.created.id)).state).toBe('active');
  const tokensA = (await identity.login(a.salon, ownerA.account)).tokens;
  const tokensB = (await identity.login(b.salon, ownerB.account)).tokens;
  await operatorProblem(await identity.call(a.salon, 'POST', '/staff', syntheticAccount('MANAGER'), tokensA), 403, 'salon_read_only');
  expect((await identity.call(b.salon, 'POST', '/staff', syntheticAccount('MANAGER'), tokensB)).status(), 'B unaffected').toBe(201);
  const alphaOwner = await identity.staff(alpha, 'OWNER');
  expect((await identity.call(alpha, 'POST', '/staff', syntheticAccount('MANAGER'), alphaOwner.tokens)).status(), 'alpha unaffected').toBe(201);
  const listA = await json<{ items: { id: string }[]; total: number }>(await onSalonHost(identity, a.salon, 'GET', INVOICES, tokensA), 'InvoicePage');
  const listB = await json<{ items: { id: string }[]; total: number }>(await onSalonHost(identity, b.salon, 'GET', INVOICES, tokensB), 'InvoicePage');
  expect(listA.total, 'A has invoices').toBeGreaterThan(0);
  expect(listB.total, 'B has invoices').toBeGreaterThan(0);
  expect(listA.items.map(i => i.id)).toEqual([invoiceA.id]);
  expect(listB.items.map(i => i.id)).toEqual([invoiceB.id]);
  await operatorProblem(await onSalonHost(identity, b.salon, 'GET', `${INVOICES}/${invoiceA.id}`, tokensB), 404, 'not_found');
  await operatorProblem(await onSalonHost(identity, a.salon, 'GET', `${INVOICES}/${invoiceA.id}`, tokensB), 404, 'not_found');
  await operatorProblem(await onSalonHost(identity, alpha, 'GET', `${INVOICES}/${invoiceB.id}`, alphaOwner.tokens), 404, 'not_found');
  const entA = await json<Entitlements>(await onSalonHost(identity, a.salon, 'GET', ENTITLEMENTS, tokensA), 'Entitlements');
  const entB = await json<Entitlements>(await onSalonHost(identity, b.salon, 'GET', ENTITLEMENTS, tokensB), 'Entitlements');
  expect(entA.subscription!.state).toBe('read_only');
  expect(entB.subscription!.state).toBe('active');
  await operatorProblem(await onSalonHost(identity, a.salon, 'GET', ENTITLEMENTS, tokensB), 404, 'not_found');
});

test(`${id(17)} Staff creation beyond the seat limit is refused and a removal frees a seat`, async ({ operator, identity, clock }) => {
  const { created, input, salon } = await provisioned(operator, clock, '2026-11-16T07:00:00Z', 2);
  const owner = await ownerSession(identity, salon, input);
  expect(await usage(identity, salon, owner.tokens), 'the Owner holds one seat').toBe(1);
  const first = await json<{ id: string }>(await identity.call(salon, 'POST', '/staff', syntheticAccount('MANAGER'), owner.tokens), 'StaffView', 201);
  expect(await usage(identity, salon, owner.tokens)).toBe(2);
  const extra = syntheticAccount('RECEPTIONIST');
  const refused = await operatorProblem(await identity.call(salon, 'POST', '/staff', extra, owner.tokens), 409, 'entitlement_exceeded');
  expect(fieldsOf(refused), 'names the limit').toContain('staffSeats');
  const listed = await identity.list({ salon, tokens: owner.tokens, principal: await identity.principal(salon, owner.tokens), account: owner.account });
  expect(listed.some(s => s.email === extra.email.toLowerCase()), 'nothing created').toBe(false);
  expect(await usage(identity, salon, owner.tokens)).toBe(2);
  expect((await identity.call(salon, 'DELETE', `/staff/${first.id}`, undefined, owner.tokens)).status(), 'removal').toBe(204);
  expect(await usage(identity, salon, owner.tokens), 'the removal freed a seat').toBe(1);
  expect((await identity.call(salon, 'POST', '/staff', extra, owner.tokens)).status(), 'the freed seat is usable').toBe(201);
  expect(await usage(identity, salon, owner.tokens)).toBe(2);
  expect(created.id).toBeTruthy();
});

test(`${id(18)} Two simultaneous creations for the last seat create exactly one staff member`, async ({ operator, identity, clock }) => {
  const { input, salon } = await provisioned(operator, clock, '2026-11-17T07:00:00Z', 3);
  const owner = await ownerSession(identity, salon, input);
  await identity.seed(salon, [syntheticAccount('MANAGER')]);
  expect(await usage(identity, salon, owner.tokens), 'one seat left').toBe(2);
  const outcomes = await Promise.all(Array.from({ length: 16 }, (_, n) =>
    identity.call(salon, 'POST', '/staff', syntheticAccount(n % 2 ? 'RECEPTIONIST' : 'ACCOUNTANT'), owner.tokens)));
  expect(outcomes.filter(r => r.status() === 201).length, 'exactly one creation').toBe(1);
  for (const loser of outcomes.filter(r => r.status() !== 201)) await operatorProblem(loser, 409, 'entitlement_exceeded');
  expect(await usage(identity, salon, owner.tokens), 'active staff equals the limit').toBe(3);
});

test(`${id(20)} Every staff role reads its own entitlements and only the Owner reads invoices`, async ({ operator, identity, clock }) => {
  const { admin, created, input, salon, plan } = await provisioned(operator, clock, '2026-11-18T07:00:00Z', 9);
  const invoice = await firstInvoice(operator, clock, admin, created.id);
  const owner = await ownerSession(identity, salon, input);
  const actors: { role: Role; tokens: Tokens }[] = [{ role: 'OWNER', tokens: owner.tokens }];
  for (const role of ROLES.filter(r => r !== 'OWNER')) actors.push({ role, tokens: (await identity.staff(salon, role)).tokens });
  expect(actors.length, 'five staff roles').toBe(5);
  for (const actor of actors) {
    await test.step(actor.role, async () => {
      const ent = await json<Entitlements>(await onSalonHost(identity, salon, 'GET', ENTITLEMENTS, actor.tokens), 'Entitlements');
      expect(ent.plan).toEqual({ key: plan.key, name: plan.name });
      expect(ent.limits).toEqual({ staffSeats: plan.staffSeats, branches: plan.branches, messageAllowance: plan.messageAllowance });
      expect(ent.features).toEqual({ customDomain: plan.customDomain, dedicatedApp: plan.dedicatedApp });
      expect(ent.usage.staffSeats, 'the Owner and the four seeded roles').toBe(5);
      expect(ent.subscription!.state).toBe((await operator.subscription(await operator.renew(admin), created.id)).state);
      const list = await onSalonHost(identity, salon, 'GET', INVOICES, actor.tokens);
      const detail = await onSalonHost(identity, salon, 'GET', `${INVOICES}/${invoice.id}`, actor.tokens);
      if (actor.role === 'OWNER') {
        expect((await json<{ total: number }>(list, 'InvoicePage')).total).toBe(1);
        expect((await json<{ id: string }>(detail, 'InvoiceDetail')).id).toBe(invoice.id);
      } else {
        await operatorProblem(list, 403, 'permission_denied');
        await operatorProblem(detail, 403, 'permission_denied');
      }
    });
  }
  const customer = await new Customers(identity).login(salon);
  for (const route of [ENTITLEMENTS, INVOICES, `${INVOICES}/${invoice.id}`]) {
    await operatorProblem(await onSalonHost(identity, salon, 'GET', route, customer.tokens), 403, 'permission_denied');
  }
  const alphaOwner = await identity.staff(alpha, 'OWNER');
  for (const route of [ENTITLEMENTS, INVOICES, `${INVOICES}/${invoice.id}`]) {
    await operatorProblem(await onSalonHost(identity, salon, 'GET', route, alphaOwner.tokens), 404, 'not_found');
  }
  await operatorProblem(await onSalonHost(identity, salon, 'GET', `${INVOICES}/${randomUUID()}`, owner.tokens), 404, 'not_found');
});

