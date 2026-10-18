/**
 * F03 operator console fixtures: operator accounts, sign-in with MFA, plans and salons, the test
 * clock without F02's three-salon guard, and the operator tables every F03 test shares.
 *
 * Prepared during the F03 build against the approved specs and docs/architecture/operator-console.md.
 * Routes, bodies and codes come from those documents. What the documents leave to the stage
 * handoffs (fixture-hook paths and bodies, generated schema names) is kept in PENDING below and is
 * confirmed at feature-verify; nothing else in a test spells one out.
 *
 * Credentials and TOTP seeds are generated in memory and never logged or written to evidence.
 */
import fs from 'node:fs';
import path from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import type { APIResponse } from '@playwright/test';

import { answerOf, expectProblem } from './answers';
import { reportDir } from './env';
import { test as identityTest, expect, totp, syntheticAccount, type Identity, type SeedAccount, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { schemaValidator } from './openapi';
import { PLATFORM_DOMAIN, alpha, beta, gamma, type Salon } from './tenants';

/** The console's exact hostname (operator-console.md, "Authority and hostname"). */
export const CONSOLE_HOST = `console.${PLATFORM_DOMAIN}`;
export const OPERATOR = '/api/v1/operator';

/**
 * Awaiting the stage handoffs. The policy fixes that these hooks exist under /local/operator, are
 * loopback- and local-only and are outside OpenAPI; their exact paths and bodies are named by the
 * developer (S01: seed, reset, bootstrap failure; S02/S03: the rest).
 */
export const PENDING = {
  hooks: {
    seed: { method: 'POST', path: '/local/operator/seed' },
    reset: { method: 'DELETE', path: '/local/operator' },
    bootstrapFailure: { method: 'PUT', path: '/local/operator/bootstrap-failure' },
    disableOperator: { method: 'PUT', path: '/local/operator/accounts/{id}/disabled' },
    unbilledSalon: { method: 'POST', path: '/local/operator/unbilled-salons' },
    runJob: { method: 'POST', path: '/local/operator/jobs/{job}/run' },
    interruptJob: { method: 'PUT', path: '/local/operator/jobs/{job}/interrupt' },
    providerOutcome: { method: 'PUT', path: '/local/operator/billing-provider/outcome' },
    providerSign: { method: 'POST', path: '/local/operator/billing-provider/sign' },
    emails: { method: 'GET', path: '/local/operator/emails' },
    clearEmails: { method: 'DELETE', path: '/local/operator/emails' },
    backgroundState: { method: 'GET', path: '/local/operator/salons/{id}/background' },
    localReset: { method: 'POST', path: '/local/operator/reset-to-baseline' },
  },
  /** Generated contract schema names; `schemaValidator` fails loudly if a name is wrong. */
  schemas: {
    signIn: 'OperatorSignIn',
    enrollment: 'OperatorEnrollment',
    tokens: 'OperatorTokens',
    principal: 'OperatorPrincipal',
    plan: 'Plan',
    planPage: 'PagePlanSummary',
    salonCreated: 'SalonCreated',
    salon: 'OperatorSalon',
    salonPage: 'PageSalonSummary',
    subscription: 'Subscription',
    invoicePage: 'PageInvoiceSummary',
    lifecyclePage: 'PageLifecycleEvent',
    supportStart: 'SupportSessionStarted',
    supportCredential: 'SupportCredential',
    entryCode: 'SupportEntryCode',
  },
  /** Platform tables added by S01-S04 migrations, for read-only database observations. */
  tables: { platformAudit: 'platform_audit_entry', receipts: 'platform_idempotency_receipt', plans: 'platform_plan' },
  /** The S02 provider-event signature header name. */
  signatureHeader: 'X-Billing-Signature',
} as const;

export type OperatorRole = 'OPERATOR_ADMIN' | 'OPERATOR_SUPPORT';
export type OperatorAccount = { email: string; password: string; role: OperatorRole; displayName: string; totpSecret?: string };
export type OperatorSignIn = { state: string; challengeId: string; challengeToken: string; expiresAt?: string; credentials?: Tokens | null };
export type OperatorPrincipal = { id: string; kind: string; role: OperatorRole; permissions: string[]; mfaEnabled: boolean };
export type OperatorActor = { account: OperatorAccount; tokens: Tokens; principal: OperatorPrincipal };
export type Plan = { id: string; key: string; name: string; staffSeats: number; branches: number; messageAllowance: number;
  customDomain: boolean; dedicatedApp: boolean; createdAt: string; updatedAt: string };
export type PlanInput = Omit<Plan, 'id' | 'createdAt' | 'updatedAt'>;
export type Page<T> = { items: T[]; page: number; size: number; total: number };
export type SalonInput = {
  name: string; slug: string; country: string; currency: string; timeZone: string; defaultLanguage: string;
  contactEmail?: string | null; contactPhone?: string | null; address?: string | null; planId: string;
  owner: { name: string; email: string; password: string };
  subscription?: { monthlyAmount: number; paymentMethod: 'card' | 'bank_transfer' };
};
export type SalonCreated = { id: string; slug: string; hostname: string; planId: string; createdAt: string };
export type SalonDetail = {
  id: string; name: string; slug: string; hostname: string; status: string; country: string | null; currency: string | null;
  timeZone: string | null; defaultLanguage: string | null; contactEmail: string | null; contactPhone: string | null;
  address: string | null; plan: { id: string; key: string; name: string } | null; ownerEmail: string | null;
  createdAt: string; updatedAt: string; version?: number;
};

/** The exact key sets the contract promises (S01 API table); a field outside them is a failure. */
export const SALON_CREATED_KEYS = ['createdAt', 'hostname', 'id', 'planId', 'slug'];
export const SALON_DETAIL_KEYS = ['address', 'contactEmail', 'contactPhone', 'country', 'createdAt', 'currency', 'defaultLanguage',
  'hostname', 'id', 'name', 'ownerEmail', 'plan', 'slug', 'status', 'timeZone', 'updatedAt'];
export const PLAN_KEYS = ['branches', 'createdAt', 'customDomain', 'dedicatedApp', 'id', 'key', 'messageAllowance', 'name', 'staffSeats', 'updatedAt'];
export const PRINCIPAL_KEYS = ['id', 'kind', 'mfaEnabled', 'permissions', 'role'];

/** Permissions after F03-S04 (operator-console.md permission tables). */
export const OPERATOR_PERMISSIONS: Record<OperatorRole, string[]> = {
  OPERATOR_ADMIN: ['operator.session.manage', 'operator.salon.read', 'operator.salon.create', 'operator.plan.read', 'operator.plan.create'],
  OPERATOR_SUPPORT: ['operator.session.manage', 'operator.salon.read'],
};

/** Hosts that must never reach operator authority (S01 AC-2). The console host itself is the control. */
export const NON_CONSOLE_HOSTS = [
  alpha.hostname, PLATFORM_DOMAIN, '127.0.0.1', `api.${PLATFORM_DOMAIN}`, `admin.${PLATFORM_DOMAIN}`, `support.${PLATFORM_DOMAIN}`,
  `www.${PLATFORM_DOMAIN}`, `a.${CONSOLE_HOST}`, `${CONSOLE_HOST}.example.com`, `x${CONSOLE_HOST}`, `console-x.${PLATFORM_DOMAIN}`,
];
export const FORGED_FORWARDING: Record<string, string>[] = [
  {}, { 'X-Forwarded-Host': CONSOLE_HOST }, { Forwarded: `host=${CONSOLE_HOST}` },
];

const hex = (bytes: number) => randomBytes(bytes).toString('hex');
/** A unique slug or plan key in the 3-30 character grammar, never reserved, never reused. */
export const uniqueSlug = (prefix = 'qa') => `${prefix}-${hex(6)}`;
export const strongPassword = () => `QA ${hex(20)}`;

export function base32Secret(): string {
  return syntheticAccount('OWNER', true).totpSecret!;
}

export function operatorAccount(role: OperatorRole = 'OPERATOR_ADMIN', enrolled = true): OperatorAccount {
  return { email: `qa-op-${randomUUID()}@example.invalid`, password: strongPassword(), role, displayName: `QA ${role} ${hex(3)}`,
    ...(enrolled ? { totpSecret: base32Secret() } : {}) };
}

export function planInput(overrides: Partial<PlanInput> = {}): PlanInput {
  return { key: uniqueSlug('plan'), name: `QA plan ${hex(3)}`, staffSeats: 5, branches: 1, messageAllowance: 100,
    customDomain: false, dedicatedApp: false, ...overrides };
}

export function salonInput(planId: string, overrides: Partial<SalonInput> = {}): SalonInput {
  const slug = overrides.slug ?? uniqueSlug();
  return {
    name: `QA Salon ${slug}`, slug, country: 'LK', currency: 'LKR', timeZone: 'Asia/Colombo', defaultLanguage: 'en',
    contactEmail: `contact-${hex(4)}@example.invalid`, contactPhone: '0771234567', address: `${hex(3)} QA Road, Colombo`,
    planId, owner: { name: 'QA Owner', email: `owner-${randomUUID()}@example.invalid`, password: strongPassword() },
    subscription: { monthlyAmount: 250000, paymentMethod: 'bank_transfer' },
    ...overrides,
  };
}

/** The owner of a provisioned salon as an F02 staff fixture, for identity calls on its hostname. */
export function ownerOf(input: SalonInput): SeedAccount {
  return { email: input.owner.email, mobile: '', password: input.owner.password, role: 'OWNER' };
}

/** A provisioned salon in the shape `qa/shared/tenants.ts` uses, so identity helpers can address it. */
export function provisionedSalon(created: SalonCreated, input: SalonInput): Salon {
  return { slug: created.slug, id: created.id, name: input.name, brandColour: '', hostname: created.hostname, timeZone: input.timeZone };
}

/**
 * Read-only observations as the owning database role (the same observer F02 uses). Each value is
 * passed through a literal, never interpolated raw.
 */
export const sqlLiteral = (value: string) => `'${value.replace(/'/g, "''")}'`;
export function countRows(table: string, where: string): number {
  if (!/^[a-z_]+$/.test(table)) throw new Error(`not a table name: ${table}`);
  return Number(identitySql(`SELECT count(*) FROM ${table} WHERE ${where}`));
}
/** Rows a salon owns in the S01 bootstrap tables: the registry, its Owner and its brand. */
export function bootstrapRows(slug: string) {
  const tenant = countRows('tenant', `slug = ${sqlLiteral(slug)}`);
  const sub = `(SELECT id FROM tenant WHERE slug = ${sqlLiteral(slug)})`;
  return { tenant, staff: countRows('identity_staff', `tenant_id IN ${sub}`), brand: countRows('salon_brand', `tenant_id IN ${sub}`) };
}

export async function operatorJson<T>(response: APIResponse, schema: keyof typeof PENDING.schemas, status = 200): Promise<T> {
  const answer = await answerOf(response);
  expect(answer.status, `${schema}: HTTP status (body ${answer.status >= 400 ? answer.body.slice(0, 200) : 'withheld'})`).toBe(status);
  expect(answer.contentType, `${schema}: content type`).toBe('application/json');
  const body = JSON.parse(answer.body);
  const validate = schemaValidator(PENDING.schemas[schema]);
  // A credential-bearing body is never printed; only whether it matched.
  expect(Boolean(validate(body)), `${schema}: generated contract shape`).toBe(true);
  return body as T;
}

/** Problem details with a stable code. Codes are strings here because S01-S04 register new ones. */
export async function operatorProblem(response: APIResponse, status: number, code: string) {
  const answer = await answerOf(response);
  expect(answer.status, `HTTP status for ${code} (body ${answer.body.slice(0, 200)})`).toBe(status);
  const problem = expectProblem(answer, code);
  expect(problem.code).toBe(code);
  return problem as typeof problem & { errors?: { field?: string; name?: string }[] };
}

/** The field names a 400 names, whichever of the documented field-error shapes is used. */
export function fieldsOf(problem: { errors?: { field?: string; name?: string }[] } & Record<string, unknown>): string[] {
  const errors = (problem.errors ?? (problem as { fieldErrors?: { field?: string }[] }).fieldErrors ?? []) as { field?: string; name?: string }[];
  return errors.map(error => error.field ?? error.name ?? '').filter(Boolean);
}

export function expectNoStore(response: APIResponse, label: string) {
  expect(response.headers()['cache-control'] ?? '', `${label}: no-store`).toContain('no-store');
}

export class Operator {
  constructor(readonly identity: Identity) {}

  /** A request on the API with an explicit Host: the console unless another is named. */
  call(method: string, route: string, options: { data?: unknown; tokens?: Tokens; host?: string; headers?: Record<string, string>; raw?: string } = {}) {
    const url = route.startsWith('/local/') || route.startsWith('/api/') ? route : `${OPERATOR}${route}`;
    const body = options.raw ?? (options.data === undefined ? undefined : JSON.stringify(options.data));
    return this.identity.api.send(method, url, {
      headers: { Host: options.host ?? CONSOLE_HOST, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(options.tokens ? { Authorization: `Bearer ${options.tokens.accessToken}` } : {}), ...options.headers },
      data: body,
    });
  }

  hook(name: keyof typeof PENDING.hooks, data?: unknown, params: Record<string, string> = {}) {
    const hook = PENDING.hooks[name];
    const route = Object.entries(params).reduce<string>((p, [k, v]) => p.replace(`{${k}}`, encodeURIComponent(v)), hook.path);
    return this.call(hook.method, route, { data });
  }

  /**
   * Back to the synthetic baseline (operator-console.md, "Local test hooks", Local reset): removes
   * every salon created after the baseline, resets hook-created operators, returns the clock to real
   * time and reschedules pushed-ahead jobs. Alpha, Beta and Gamma are untouched, so F01/F02 guards
   * run unchanged afterwards. Called in every F03 test's teardown.
   */
  async resetToBaseline() {
    const r = await this.hook('localReset');
    if (r.status() === 404) throw new Error('ENVIRONMENT: the F03 local reset hook is not available (confirm its path in the S03 handoff)');
    expect([200, 204], 'local reset to the synthetic baseline').toContain(r.status());
  }

  async reset() {
    const r = await this.hook('reset');
    if (r.status() === 404) throw new Error('ENVIRONMENT: the operator fixture reset hook is not available (confirm its path in the S01 handoff)');
    expect(r.status()).toBe(204);
  }

  async seed(...accounts: OperatorAccount[]) {
    const r = await this.hook('seed', { accounts });
    if (r.status() === 404) throw new Error('ENVIRONMENT: the operator seed hook is not available (confirm its path in the S01 handoff)');
    expect(r.status(), 'operator fixtures seeded').toBe(200);
  }

  async now(): Promise<string> { return this.identity.now(); }

  signInRaw(account: Pick<OperatorAccount, 'email' | 'password'>, extra: { host?: string; headers?: Record<string, string> } = {}) {
    return this.call('POST', '/sign-in', { data: { email: account.email, password: account.password, deviceLabel: 'QA console' }, ...extra });
  }

  async signIn(account: OperatorAccount): Promise<OperatorSignIn> {
    const challenge = await operatorJson<OperatorSignIn>(await this.signInRaw(account), 'signIn');
    expect(challenge.credentials ?? null, 'no session before MFA').toBeNull();
    expect(challenge.challengeId).toMatch(/^[0-9a-f-]{36}$/);
    return challenge;
  }

  async enrollment(challenge: OperatorSignIn): Promise<{ secret: string; otpauthUri: string }> {
    return operatorJson(await this.call('POST', '/mfa/challenges', { data: { challengeId: challenge.challengeId, challengeToken: challenge.challengeToken } }), 'enrollment');
  }

  verifyRaw(challenge: OperatorSignIn, code: string) {
    return this.call('POST', '/mfa/verify', { data: { challengeId: challenge.challengeId, challengeToken: challenge.challengeToken, code } });
  }

  async code(secret: string, offsetMs = 0) { return totp(secret, new Date(Date.parse(await this.now()) + offsetMs).toISOString()); }

  /** A six-digit code that is wrong for every step the server accepts (one step either side). */
  async wrongCode(secret: string) {
    const used = await Promise.all([-30000, 0, 30000].map(d => this.code(secret, d)));
    return ['999999', '000000', '111111', '222222', '333333'].find(c => !used.includes(c))!;
  }

  async tokens(response: APIResponse): Promise<Tokens> {
    const tokens = await operatorJson<Tokens>(response, 'tokens');
    expect(Boolean(tokens.accessToken && tokens.refreshToken && tokens.sessionId), 'operator credentials exist').toBe(true);
    return tokens;
  }

  /** Signs in through password and MFA, enrolling when the account has no seed yet. */
  async login(account: OperatorAccount): Promise<OperatorActor> {
    const challenge = await this.signIn(account);
    if (!account.totpSecret) {
      expect(challenge.state).toBe('enroll_mfa');
      account.totpSecret = (await this.enrollment(challenge)).secret;
    } else {
      expect(challenge.state).toBe('verify_mfa');
    }
    const tokens = await this.tokens(await this.verifyRaw(challenge, await this.code(account.totpSecret)));
    return { account, tokens, principal: await this.me(tokens) };
  }

  async me(tokens: Tokens): Promise<OperatorPrincipal> { return operatorJson(await this.call('GET', '/me', { tokens }), 'principal'); }

  async actor(role: OperatorRole = 'OPERATOR_ADMIN'): Promise<OperatorActor> {
    const account = operatorAccount(role);
    await this.seed(account);
    return this.login(account);
  }

  refreshRaw(refreshToken: string) { return this.call('POST', '/sessions/refresh', { data: { refreshToken } }); }
  signOut(tokens: Tokens) { return this.call('POST', '/sessions/sign-out', { tokens }); }

  createPlanRaw(actor: OperatorActor | null, input: unknown, key: string = randomUUID(), extra: { host?: string; headers?: Record<string, string> } = {}) {
    return this.call('POST', '/plans', { data: input, tokens: actor?.tokens, headers: { 'Idempotency-Key': key, ...extra.headers }, host: extra.host });
  }

  async plan(actor: OperatorActor, overrides: Partial<PlanInput> = {}): Promise<Plan> {
    return operatorJson<Plan>(await this.createPlanRaw(actor, planInput(overrides)), 'plan', 201);
  }

  createSalonRaw(actor: OperatorActor | null, input: unknown, key: string = randomUUID(), extra: { host?: string; headers?: Record<string, string> } = {}) {
    return this.call('POST', '/salons', { data: input, tokens: actor?.tokens, headers: { 'Idempotency-Key': key, ...extra.headers }, host: extra.host });
  }

  async salon(actor: OperatorActor, planId: string, overrides: Partial<SalonInput> = {}) {
    const input = salonInput(planId, overrides);
    const created = await operatorJson<SalonCreated>(await this.createSalonRaw(actor, input), 'salonCreated', 201);
    return { input, created, salon: provisionedSalon(created, input) };
  }

  async salonDetail(actor: OperatorActor, id: string): Promise<SalonDetail> {
    return operatorJson(await this.call('GET', `/salons/${id}`, { tokens: actor.tokens }), 'salon');
  }

  async salonPage(actor: OperatorActor, query = ''): Promise<Page<SalonDetail>> {
    return operatorJson(await this.call('GET', `/salons${query}`, { tokens: actor.tokens }), 'salonPage');
  }

  async planPage(actor: OperatorActor, query = ''): Promise<Page<Plan>> {
    return operatorJson(await this.call('GET', `/plans${query}`, { tokens: actor.tokens }), 'planPage');
  }

  // S02-S04 routes, exactly as their specs' API tables name them.
  subscription(actor: OperatorActor | null, salonId: string, extra: { host?: string } = {}) {
    return this.call('GET', `/salons/${salonId}/subscription`, { tokens: actor?.tokens, ...extra });
  }
  async version(actor: OperatorActor, salonId: string): Promise<number> {
    const detail = await this.salonDetail(actor, salonId);
    expect(typeof detail.version, 'salon detail exposes version').toBe('number');
    return detail.version!;
  }
  suspend(actor: OperatorActor | null, salonId: string, version: number, reason = 'QA suspension reason', extra: { host?: string } = {}) {
    return this.call('POST', `/salons/${salonId}/suspension`, { tokens: actor?.tokens, data: { version, reason }, ...extra });
  }
  reactivate(actor: OperatorActor | null, salonId: string, version: number, reason = 'QA reactivation reason', extra: { host?: string } = {}) {
    return this.call('DELETE', `/salons/${salonId}/suspension`, { tokens: actor?.tokens, data: { version, reason }, ...extra });
  }
  close(actor: OperatorActor | null, salonId: string, version: number, confirmSlug: string, reason = 'QA closure reason', extra: { host?: string } = {}) {
    return this.call('POST', `/salons/${salonId}/closure`, { tokens: actor?.tokens, data: { version, confirmSlug, reason }, ...extra });
  }
  startSupport(actor: OperatorActor | null, salonId: string, data: Record<string, unknown> = { reason: 'QA support reason text' }, extra: { host?: string } = {}) {
    return this.call('POST', `/salons/${salonId}/support-sessions`, { tokens: actor?.tokens, data, ...extra });
  }
  /** Exchanges an entry code directly with the API on a host (the BFF path is a browser case). */
  exchange(code: string, host: string) {
    return this.call('POST', '/api/v1/support/entry', { data: { code }, host });
  }
}

export type SupportStart = { id: string; expiresAt: string; entryCode: string; entryUrl: string };

/**
 * The application's one clock (docs/qa/test-plan.md). F02's guard requires exactly the three fixed
 * salons and F03 provisions more, so F03 sets the clock here and records a mark for every change.
 * The S03 local reset hook (run in every F03 test's teardown) returns it to real time and
 * reschedules the job occurrences F03's clock moves pushed ahead.
 */
export class TestClock {
  private changed = false;
  constructor(private readonly identity: Identity) {}
  async set(instant: string) {
    const r = await this.identity.api.send('PUT', '/local/clock', { headers: { 'Content-Type': 'application/json' }, data: JSON.stringify({ instant }) });
    expect(r.status(), 'test clock set').toBe(200);
    this.changed = true;
    clockMark('f03-clock-set', instant);
  }
  async advance(ms: number) { await this.set(new Date(Date.parse(await this.identity.now()) + ms).toISOString()); }
  async real() {
    if (!this.changed) return;
    const r = await this.identity.api.send('DELETE', '/local/clock');
    expect(r.status(), 'test clock restored').toBe(200);
    this.changed = false;
    clockMark('f03-clock-real', new Date().toISOString());
  }
}

function clockMark(name: string, instant: string) {
  fs.mkdirSync(reportDir, { recursive: true });
  fs.appendFileSync(path.join(reportDir, 'F03-control-marks.jsonl'), JSON.stringify({ name, instant, at: Date.now() }) + '\n');
}

export const DAY = 86_400_000;

export const test = identityTest.extend<{ operator: Operator; clock: TestClock }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.reset();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  clock: async ({ identity }, use) => {
    const clock = new TestClock(identity);
    try { await use(clock); } finally { await clock.real(); }
  },
});

export { expect, alpha, beta, gamma };
