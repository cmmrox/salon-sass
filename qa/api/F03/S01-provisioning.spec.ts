/**
 * F03-S01 plans and salon provisioning: isolation of new salons, plan and salon validation,
 * provisioning, the first Owner, races, idempotent replay, atomic rollback and directories.
 * Cases TC-F03-S01-010, -018 to -026 (cases.md). TC-F03-S01-017 (restart) is in S01-runtime.spec.ts.
 *
 * Prepared during the F03 build against the S01 spec and operator-console.md. The final contract
 * includes S02's required `subscription` on salon creation, which `salonInput` supplies.
 */
import { randomUUID } from 'node:crypto';

import { answerOf, expectNothingOfOtherSalons } from '../../shared/answers';
import { json, type Tokens } from '../../shared/identity';
import type { components } from '@contracts/schema';
import {
  test, expect, alpha, beta, gamma, PLAN_KEYS, SALON_CREATED_KEYS, SALON_DETAIL_KEYS,
  Operator, bootstrapRows, fieldsOf, operatorJson, operatorProblem, ownerOf, planInput, salonInput, uniqueSlug,
  type OperatorActor, type Plan, type SalonCreated, type SalonInput,
} from '../../shared/operator';
import type { Identity } from '../../shared/identity';
import { RESERVED_SLUGS as RESERVED, type Salon } from '../../shared/tenants';

const id = (n: number) => `TC-F03-S01-${String(n).padStart(3, '0')}`;

/** The provisioned Owner's first sign-in at its own salon: enrollment, then a session. */
async function signInOwner(identity: Identity, salon: Salon, input: SalonInput): Promise<Tokens> {
  const owner = ownerOf(input);
  const challenge = await identity.signIn(salon, owner);
  expect(challenge.state, 'a new Owner must enroll MFA first').toBe('enroll_mfa');
  expect(challenge.credentials ?? null, 'no session before enrollment').toBeNull();
  const enrollment = await json<components['schemas']['Enrollment']>(await identity.call(salon, 'POST', '/staff/mfa/enroll',
    { challengeId: challenge.challengeId, challengeToken: challenge.challengeToken }), 'Enrollment');
  return identity.verify(salon, challenge, enrollment.secret!);
}

async function brandOf(operator: Operator, salon: Salon) {
  const response = await operator.identity.api.salon(salon);
  expect(response.status(), `brand of ${salon.slug}`).toBe(200);
  return response.json() as Promise<{ slug: string; name: string; brandColour: string; timeZone: string }>;
}

test(`${id(10)} Two newly provisioned salons keep isolated staff and brand data`, async ({ operator, identity }) => {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const before = { alpha: await brandOf(operator, alpha), beta: await brandOf(operator, beta) };
  const a = await operator.salon(admin, plan.id, { timeZone: 'Asia/Colombo' });
  const b = await operator.salon(admin, plan.id, { timeZone: 'Europe/London' });
  const tokenA = await signInOwner(identity, a.salon, a.input);
  const tokenB = await signInOwner(identity, b.salon, b.input);
  for (const [{ salon, input }, tokens] of [[a, tokenA], [b, tokenB]] as const) {
    const staff = await json<{ items: { email?: string }[]; total: number }>(await identity.call(salon, 'GET', '/staff?size=100', undefined, tokens), 'PageStaffView');
    expect(staff.total, `${salon.slug} has exactly its Owner`).toBe(1);
    expect(staff.items[0].email).toBe(input.owner.email.toLowerCase());
    const brand = await brandOf(operator, salon);
    expect(brand).toMatchObject({ slug: salon.slug, name: input.name, timeZone: input.timeZone });
  }
  await operatorProblem(await identity.call(b.salon, 'POST', '/staff/sign-in', { identifier: a.input.owner.email, password: a.input.owner.password, deviceLabel: 'QA' }), 401, 'invalid_credentials');
  await operatorProblem(await identity.call(b.salon, 'GET', '/staff', undefined, tokenA), 404, 'not_found');
  expect(await brandOf(operator, alpha)).toEqual(before.alpha);
  expect(await brandOf(operator, beta)).toEqual(before.beta);
});

test(`${id(18)} Invalid plan definitions are refused with field errors and create nothing`, async ({ operator }) => {
  const admin = await operator.actor();
  const taken = await operator.plan(admin);
  const before = (await operator.planPage(admin, '?size=1')).total;
  const valid = () => planInput();
  const without = (field: keyof ReturnType<typeof planInput>) => { const body: Record<string, unknown> = valid(); delete body[field]; return body; };
  const rows: [string, Record<string, unknown>, string][] = [
    ...(['key', 'name', 'staffSeats', 'branches', 'messageAllowance', 'customDomain', 'dedicatedApp'] as const).map(f => [`missing ${f}`, without(f), f] as [string, Record<string, unknown>, string]),
    ['unknown field', { ...valid(), unlimited: true }, 'unlimited'],
    ...['"5"', '1.5', 'null', 'true'].map(v => [`staffSeats ${v}`, { ...valid(), staffSeats: JSON.parse(v === '"5"' ? '"5"' : v) }, 'staffSeats'] as [string, Record<string, unknown>, string]),
    ['staffSeats 0', { ...valid(), staffSeats: 0 }, 'staffSeats'],
    ['staffSeats 1000001', { ...valid(), staffSeats: 1_000_001 }, 'staffSeats'],
    ['branches 0', { ...valid(), branches: 0 }, 'branches'],
    ['branches 1000001', { ...valid(), branches: 1_000_001 }, 'branches'],
    ['messageAllowance -1', { ...valid(), messageAllowance: -1 }, 'messageAllowance'],
    ['messageAllowance 1000001', { ...valid(), messageAllowance: 1_000_001 }, 'messageAllowance'],
    ['customDomain "true"', { ...valid(), customDomain: 'true' }, 'customDomain'],
    ['key of 2', { ...valid(), key: 'ab' }, 'key'],
    ['key of 31', { ...valid(), key: `k${'a'.repeat(30)}` }, 'key'],
    ['key with underscore', { ...valid(), key: 'qa_plan' }, 'key'],
    ['key with leading hyphen', { ...valid(), key: '-qaplan' }, 'key'],
    ['empty name', { ...valid(), name: '' }, 'name'],
    ['blank name', { ...valid(), name: '   ' }, 'name'],
    ['name of 121', { ...valid(), name: 'n'.repeat(121) }, 'name'],
  ];
  expect(rows.length).toBeGreaterThan(20);
  for (const [label, body, field] of rows) {
    await test.step(label, async () => {
      const problem = await operatorProblem(await operator.createPlanRaw(admin, body), 400, 'bad_request');
      expect(fieldsOf(problem), `${label} names ${field}`).toContain(field);
      if (typeof body.key === 'string') expect((await operator.planPage(admin, `?q=${encodeURIComponent(body.key)}`)).total).toBe(0);
    });
  }
  await operatorProblem(await operator.createPlanRaw(admin, planInput({ key: taken.key })), 409, 'plan_key_taken');
  expect((await operator.planPage(admin, '?size=1')).total, 'no plan created').toBe(before);
});

test(`${id(19)} An Admin provisions a salon whose profile, plan and hostname are correct`, async ({ operator }) => {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const slug = uniqueSlug();
  const input = salonInput(plan.id, { slug: slug.toUpperCase().replace('QA', 'Qa') });
  const response = await operator.createSalonRaw(admin, input);
  const text = await response.text();
  expect(text, 'no initial password in the response').not.toContain(input.owner.password);
  const created = await operatorJson<SalonCreated>(response, 'salonCreated', 201);
  expect(Object.keys(created).sort()).toEqual(SALON_CREATED_KEYS);
  expect(created.slug).toBe(slug);
  expect(created.hostname).toBe(`${slug}.localhost`);
  expect(created.planId).toBe(plan.id);
  const detail = await operator.salonDetail(admin, created.id);
  expect(Object.keys(detail).filter(k => k !== 'version').sort(), 'only allowlisted detail fields').toEqual(SALON_DETAIL_KEYS);
  expect(detail).toMatchObject({
    id: created.id, name: input.name, slug, hostname: created.hostname, status: 'active', country: 'LK', currency: 'LKR',
    timeZone: input.timeZone, defaultLanguage: 'en', contactEmail: input.contactEmail, address: input.address,
    plan: { id: plan.id, key: plan.key, name: plan.name }, ownerEmail: input.owner.email.toLowerCase(),
  });
  expect(detail.contactPhone, 'phone kept').not.toBeNull();
  expect(JSON.stringify(detail)).not.toContain(input.owner.password);
  expect((await operator.salonPage(admin, `?q=${slug}`)).items.map(s => s.id)).toEqual([created.id]);
  const brand = await brandOf(operator, { ...alpha, slug, hostname: created.hostname, id: created.id });
  expect(brand).toMatchObject({ slug, name: input.name, timeZone: input.timeZone });
  expect(brand.brandColour).toMatch(/^#[0-9A-Fa-f]{6}$/);
});

test(`${id(20)} The provisioned Owner signs in only at its salon and must enroll MFA`, async ({ operator, identity }) => {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, salon } = await operator.salon(admin, plan.id);
  await operatorProblem(await identity.call(alpha, 'POST', '/staff/sign-in', { identifier: input.owner.email, password: input.owner.password, deviceLabel: 'QA' }), 401, 'invalid_credentials');
  await operatorProblem(await operator.signInRaw({ email: input.owner.email, password: input.owner.password }), 401, 'invalid_credentials');
  const tokens = await signInOwner(identity, salon, input);
  const principal = await identity.principal(salon, tokens);
  expect(principal).toMatchObject({ kind: 'staff', role: 'OWNER', mfaEnabled: true });
  for (const permission of ['staff.list', 'staff.manage', 'audit.list']) expect(principal.permissions).toContain(permission);
  expect((await identity.call(salon, 'GET', '/staff', undefined, tokens)).status()).toBe(200);
  await operatorProblem(await identity.call(salon, 'GET', '/me', undefined, admin.tokens), 401, 'invalid_session');
});

test(`${id(21)} Invalid salon input or a missing plan creates no tenant, Owner, brand or audit`, async ({ operator }) => {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const before = (await operator.salonPage(admin, '?size=1')).total;
  const row = (label: string, change: (body: SalonInput & Record<string, unknown>) => void, field: string) => {
    const body = salonInput(plan.id) as SalonInput & Record<string, unknown>;
    change(body);
    return { label, body, field };
  };
  const rows = [
    ...RESERVED.map(slug => row(`reserved ${slug}`, b => { b.slug = slug; }, 'slug')),
    row('slug of 2', b => { b.slug = 'ab'; }, 'slug'),
    row('slug of 31', b => { b.slug = `s${'a'.repeat(30)}`; }, 'slug'),
    row('slug with underscore', b => { b.slug = 'qa_salon'; }, 'slug'),
    row('slug leading hyphen', b => { b.slug = '-qasalon'; }, 'slug'),
    row('slug trailing hyphen', b => { b.slug = 'qasalon-'; }, 'slug'),
    row('country XX', b => { b.country = 'XX'; }, 'country'),
    row('currency ABC', b => { b.currency = 'ABC'; }, 'currency'),
    row('language fr', b => { b.defaultLanguage = 'fr'; }, 'defaultLanguage'),
    row('zone Mars/Base', b => { b.timeZone = 'Mars/Base'; }, 'timeZone'),
    row('empty name', b => { b.name = ''; }, 'name'),
    row('name of 121', b => { b.name = 'n'.repeat(121); }, 'name'),
    row('address of 501', b => { b.address = 'a'.repeat(501); }, 'address'),
    row('contact email of 255', b => { b.contactEmail = `${'e'.repeat(243)}@example.com`; }, 'contactEmail'),
    row('malformed contact email', b => { b.contactEmail = 'not-an-email'; }, 'contactEmail'),
    row('phone not Sri Lankan mobile', b => { b.contactPhone = '0112345678'; }, 'contactPhone'),
    row('empty owner name', b => { b.owner.name = ''; }, 'owner.name'),
    row('malformed owner email', b => { b.owner.email = 'nope'; }, 'owner.email'),
    row('owner password of 14', b => { b.owner.password = 'p'.repeat(14); }, 'owner.password'),
    row('unknown top-level field', b => { b.tenantId = alpha.id; }, 'tenantId'),
    row('unknown owner field', b => { (b.owner as Record<string, unknown>).role = 'OPERATOR_ADMIN'; }, 'owner.role'),
  ];
  for (const { label, body, field } of rows) {
    await test.step(label, async () => {
      const problem = await operatorProblem(await operator.createSalonRaw(admin, body), 400, 'bad_request');
      expect(fieldsOf(problem).some(f => f === field || f.endsWith(field)), `${label} names ${field}: ${fieldsOf(problem)}`).toBe(true);
      if (/^[a-z0-9-]{3,30}$/.test(body.slug)) expect(bootstrapRows(body.slug), `${label}: nothing stored`).toEqual({ tenant: 0, staff: 0, brand: 0 });
    });
  }
  for (const header of [{}, { 'Idempotency-Key': 'not-a-uuid' }] as Record<string, string>[]) {
    const r = await operator.call('POST', '/salons', { data: salonInput(plan.id), tokens: admin.tokens, headers: header });
    const problem = await operatorProblem(r, 400, 'bad_request');
    expect(fieldsOf(problem).join(' ')).toMatch(/Idempotency-Key/i);
  }
  const missingPlan = salonInput(randomUUID());
  await operatorProblem(await operator.createSalonRaw(admin, missingPlan), 404, 'not_found');
  expect(bootstrapRows(missingPlan.slug)).toEqual({ tenant: 0, staff: 0, brand: 0 });
  expect((await operator.salonPage(admin, '?size=1')).total, 'no salon from a rejected row').toBe(before);
  // The 3- and 30-character boundaries are accepted.
  for (const slug of [`q${randomUUID().slice(0, 2)}`, `q${randomUUID().replace(/-/g, '').slice(0, 29)}`]) {
    await operatorJson(await operator.createSalonRaw(admin, salonInput(plan.id, { slug })), 'salonCreated', 201);
  }
  expect((await operator.salonPage(admin, '?size=1')).total).toBe(before + 2);
});

test(`${id(22)} Simultaneous creations for one slug create one salon and cannot replace a fixed salon`, async ({ operator }) => {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const slug = uniqueSlug();
  const fixedBefore = await Promise.all([alpha, beta, gamma].map(s => brandOf(operator, s)));
  const outcomes = await Promise.all(Array.from({ length: 10 }, () => operator.createSalonRaw(admin, salonInput(plan.id, { slug }))));
  expect(outcomes.filter(r => r.status() === 201).length, 'one winner').toBe(1);
  for (const loser of outcomes.filter(r => r.status() !== 201)) await operatorProblem(loser, 409, 'slug_taken');
  expect(bootstrapRows(slug), 'one tenant, one Owner, one brand').toEqual({ tenant: 1, staff: 1, brand: 1 });
  for (const fixed of [alpha, beta, gamma]) await operatorProblem(await operator.createSalonRaw(admin, salonInput(plan.id, { slug: fixed.slug })), 409, 'slug_taken');
  expect(await Promise.all([alpha, beta, gamma].map(s => brandOf(operator, s))), 'fixed salons unchanged').toEqual(fixedBefore);
});

test(`${id(23)} Simultaneous creations for one plan key create exactly one plan`, async ({ operator }) => {
  const admin = await operator.actor();
  const key = uniqueSlug('plan');
  const outcomes = await Promise.all(Array.from({ length: 10 }, () => operator.createPlanRaw(admin, planInput({ key }))));
  expect(outcomes.filter(r => r.status() === 201).length, 'one winner').toBe(1);
  for (const loser of outcomes.filter(r => r.status() !== 201)) await operatorProblem(loser, 409, 'plan_key_taken');
  expect((await operator.planPage(admin, `?q=${key}`)).total).toBe(1);
});

test(`${id(24)} Repeated creation with one key replays the original result and one record`, async ({ operator }) => {
  const admin = await operator.actor();
  const other = await operator.actor();
  const kinds = [
    { name: 'plan', input: planInput(), send: (a: OperatorActor | null, body: unknown, key: string) => operator.createPlanRaw(a, body, key),
      count: async (body: { key?: string }) => (await operator.planPage(admin, `?q=${body.key}`)).total, change: (b: Record<string, unknown>) => ({ ...b, name: `${b.name} changed` }) },
    { name: 'salon', input: salonInput((await operator.plan(admin)).id), send: (a: OperatorActor | null, body: unknown, key: string) => operator.createSalonRaw(a, body, key),
      count: async (body: { slug?: string }) => (await operator.salonPage(admin, `?q=${body.slug}`)).total, change: (b: Record<string, unknown>) => ({ ...b, name: `${b.name} changed` }) },
  ];
  for (const kind of kinds) {
    await test.step(kind.name, async () => {
      const key = randomUUID();
      const first = await kind.send(admin, kind.input, key);
      expect(first.status()).toBe(201);
      const original = await first.json();
      const sequential = await kind.send(admin, kind.input, key);
      expect(sequential.status()).toBe(201);
      expect(await sequential.json()).toEqual(original);
      const together = await Promise.all(Array.from({ length: 8 }, () => kind.send(admin, kind.input, key)));
      for (const r of together) { expect(r.status()).toBe(201); expect(await r.json()).toEqual(original); }
      expect(await kind.count(kind.input as never), 'one record').toBe(1);
      await operatorProblem(await kind.send(admin, kind.change(kind.input as Record<string, unknown>), key), 409, 'idempotency_conflict');
      const anonymous = await kind.send(null, kind.input, key);
      await operatorProblem(anonymous, 401, 'invalid_session');
      expect(await anonymous.text()).not.toContain(original.id);
      const scoped = await kind.send(other, kind.input, key);
      expect(scoped.status(), 'another operator does not receive this receipt').not.toBe(201);
      expect(await scoped.text()).not.toContain(original.id);
      expect(await kind.count(kind.input as never), 'still one record').toBe(1);
    });
  }
});

test(`${id(25)} A failed Owner or brand bootstrap leaves nothing and a retry with the original key succeeds`, async ({ operator }) => {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const fixedBefore = await Promise.all([alpha, beta, gamma].map(s => brandOf(operator, s)));
  for (const step of ['owner', 'brand']) {
    await test.step(step, async () => {
      const arm = await operator.hook('bootstrapFailure', { step, armed: true });
      if (arm.status() === 404) throw new Error('ENVIRONMENT: the bootstrap-failure hook is not available (confirm its path in the S01 handoff)');
      expect(arm.status()).toBeLessThan(300);
      const input = salonInput(plan.id);
      const key = randomUUID();
      const failed = await operator.createSalonRaw(admin, input, key);
      expect(failed.status(), 'bootstrap failure is an error').toBeGreaterThanOrEqual(500);
      expect(bootstrapRows(input.slug), 'nothing of the salon stored').toEqual({ tenant: 0, staff: 0, brand: 0 });
      expect((await operator.identity.api.getOn(`${input.slug}.localhost`, '/api/v1/salon')).status(), 'unresolvable').toBe(404);
      expect((await operator.hook('bootstrapFailure', { step, armed: false })).status()).toBeLessThan(300);
      const retried = await operatorJson<SalonCreated>(await operator.createSalonRaw(admin, input, key), 'salonCreated', 201);
      expect(bootstrapRows(retried.slug)).toEqual({ tenant: 1, staff: 1, brand: 1 });
    });
  }
  expect(await Promise.all([alpha, beta, gamma].map(s => brandOf(operator, s)))).toEqual(fixedBefore);
});

test(`${id(26)} Directories paginate, search literally and expose only allowlisted metadata`, async ({ operator }) => {
  const admin = await operator.actor();
  const fragment = `Zq${randomUUID().slice(0, 6)}`;
  const plans: Plan[] = [];
  for (let n = 0; n < 5; n++) plans.push(await operator.plan(admin, { name: `${fragment} plan ${n}${n === 4 ? ' 100%_off' : ''}` }));
  for (let n = 0; n < 3; n++) await operator.salon(admin, plans[0].id, { name: `${fragment} salon ${n}` });
  const all: Plan[] = [];
  for (let page = 0; page < 3; page++) {
    const result = await operator.planPage(admin, `?q=${fragment.toLowerCase()}&size=2&page=${page}`);
    expect(result).toMatchObject({ page, size: 2, total: 5 });
    all.push(...result.items);
  }
  expect(new Set(all.map(p => p.id)), 'every plan once across pages').toEqual(new Set(plans.map(p => p.id)));
  expect(all.length, 'no duplicates').toBe(5);
  for (let n = 1; n < all.length; n++) expect(Date.parse(all[n - 1].createdAt), 'creation time descending').toBeGreaterThanOrEqual(Date.parse(all[n].createdAt));
  for (const item of all) expect(Object.keys(item).sort()).toEqual(PLAN_KEYS);
  expect((await operator.planPage(admin, `?q=${encodeURIComponent('100%_off')}`)).total, 'literal % and _').toBe(1);
  expect((await operator.planPage(admin, `?q=${encodeURIComponent('%')}&size=100`)).items.every(p => p.name.includes('%'))).toBe(true);
  const salons = await operator.salonPage(admin, `?q=${fragment.toUpperCase()}`);
  expect(salons.total).toBe(3);
  for (const s of salons.items) expect(JSON.stringify(s)).not.toMatch(/password|token|hash|secret/i);
  for (const [query, field] of [['?page=-1', 'page'], ['?page=100001', 'page'], ['?size=0', 'size'], ['?size=101', 'size'], [`?q=${'q'.repeat(121)}`, 'q']]) {
    for (const route of ['/plans', '/salons']) {
      const problem = await operatorProblem(await operator.call('GET', `${route}${query}`, { tokens: admin.tokens }), 400, 'bad_request');
      expect(fieldsOf(problem)).toContain(field);
    }
  }
  for (const route of ['/plans', '/salons']) {
    await operatorProblem(await operator.call('GET', `${route}/${randomUUID()}`, { tokens: admin.tokens }), 404, 'not_found');
    await operatorProblem(await operator.call('GET', `${route}/not-an-id`, { tokens: admin.tokens }), 400, 'bad_request');
  }
  const legacy = await operator.salonDetail(admin, alpha.id);
  expect(legacy.plan, 'legacy salon has no plan').toBeNull();
  const answer = await answerOf(await operator.call('GET', `/salons/${alpha.id}`, { tokens: admin.tokens }));
  expectNothingOfOtherSalons(answer, alpha, 'alpha detail');
});
