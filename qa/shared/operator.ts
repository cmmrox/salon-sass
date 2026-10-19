/**
 * F03 operator console fixtures: operator accounts, sign-in with MFA, plans and salons, billing,
 * lifecycle and export helpers, the local hooks, the test clock without F02's three-salon guard,
 * and the read-only database observer every F03 test shares.
 *
 * Routes, bodies and codes come from the stage specs and docs/architecture/operator-console.md.
 * What those documents left to the stage handoffs is in CONTRACT below, confirmed against the
 * F03-S01 to S04 handoffs (stage.yaml `handoff.run`) and the generated contract; S04's values are in
 * SUPPORT and PENDING_S04 (the name is kept so the prepared tests read unchanged).
 *
 * Credentials and TOTP seeds are generated in memory and never logged or written to evidence.
 */
import fs from 'node:fs';
import path from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import type { APIResponse } from '@playwright/test';

import { answerOf, expectProblem } from './answers';
import { reportDir } from './env';
import { test as identityTest, expect, json, totp, syntheticAccount, type Identity, type SeedAccount, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { committedContract, schemaValidator } from './openapi';
import { PLATFORM_DOMAIN, alpha, beta, gamma, type Salon } from './tenants';
import type { components } from '@contracts/schema';

/** The console's exact hostname (operator-console.md, "Authority and hostname"). */
export const CONSOLE_HOST = `console.${PLATFORM_DOMAIN}`;
/** The bare platform host: provider events and the Owner's export link (S02, S03 handoffs). */
export const PLATFORM_HOST = PLATFORM_DOMAIN;
export const OPERATOR = '/api/v1/operator';

type Hook = { method: 'GET' | 'POST' | 'PUT' | 'DELETE'; path: string };

/**
 * The contract the specs left to the handoffs, as the handoffs name it. Hooks are loopback-only,
 * `local`-only and outside OpenAPI; they answer on any host.
 */
export const CONTRACT = {
  hooks: {
    // F03-S01
    seed: { method: 'POST', path: '/local/operator/seed' },                       // {accounts:[{email,displayName,role,password,totpSecret|null}]} -> 200 {accounts}
    fixtures: { method: 'DELETE', path: '/local/operator/fixtures' },              // 204: fixture operators' MFA, sessions, rate buckets; bootstrap fault off
    bootstrapFailure: { method: 'PUT', path: '/local/operator/bootstrap-failure' },// {step: owner|brand|receipt|null} -> 200 {step}
    // F03-S02
    operatorActive: { method: 'PUT', path: '/local/operator/operators/active' },   // {email,active} -> 204, 404 for a non-fixture operator
    unbilledSalon: { method: 'POST', path: '/local/operator/unbilled-salons' },    // {slug?} -> 201 {id,slug,hostname}
    billingRun: { method: 'POST', path: '/local/operator/billing/run' },           // -> {at,changed:[...],failed:[...]}
    billingInterrupt: { method: 'PUT', path: '/local/operator/billing/interrupt' },// {point:"after-invoice-issue"|null} -> {point}; one-shot
    providerOutcome: { method: 'PUT', path: '/local/operator/billing/provider-outcomes/{salonId}' }, // {outcome} -> {salonId,outcome}; sticky
    providerSign: { method: 'POST', path: '/local/operator/billing/provider-events/signature' },    // raw body -> {header,signature}
    emails: { method: 'GET', path: '/local/operator/emails' },                     // ?recipient= -> {items:[CapturedEmail]}
    clearEmails: { method: 'DELETE', path: '/local/operator/emails' },             // ?recipient= -> 204
    // F03-S03
    exportRun: { method: 'POST', path: '/local/operator/exports/run' },            // -> {at,exports:[...],failed:[...]}
    deletionRun: { method: 'POST', path: '/local/operator/deletions/run' },        // -> {at,salons:[...],waiting:[...],failed:[...]}
    exportInterrupt: { method: 'PUT', path: '/local/operator/exports/interrupt' }, // {afterTables:1..1000|null}; one-shot
    deletionInterrupt: { method: 'PUT', path: '/local/operator/deletions/interrupt' }, // {afterBatches:1..100000|null}; one-shot
    background: { method: 'GET', path: '/local/operator/salons/{salonId}/background' },
    localReset: { method: 'POST', path: '/local/operator/reset' },                 // -> {removedSalons,salonIds,jobsRescheduled,clock:"real"}
  } satisfies Record<string, Hook>,
  /** Generated contract schema names (docs/api/openapi.json). `schemaValidator` fails loudly on a wrong one. */
  schemas: {
    signIn: 'OperatorSignIn',
    enrollment: 'OperatorEnrollment',
    tokens: 'OperatorTokens',
    principal: 'OperatorPrincipal',
    plan: 'PlanDefinition',
    planPage: 'PlanPage',
    salonCreated: 'SalonCreated',
    salon: 'SalonDetail',
    salonPage: 'SalonPage',
    subscription: 'SubscriptionView',
    invoicePage: 'InvoicePage',
    invoice: 'InvoiceDetail',
    payment: 'PaymentView',
    entitlements: 'Entitlements',
    acknowledged: 'Acknowledged',
    lifecyclePage: 'LifecyclePage',
    exportView: 'ExportView',
    linkView: 'LinkView',
  },
  /** Provider events: `POST /api/v1/billing/provider-events` on the bare platform host (S02 handoff). */
  providerEvents: '/api/v1/billing/provider-events',
  signatureHeader: 'X-Salon-Billing-Signature',
  /** The Owner's export link routes on the bare platform host (S03 handoff). */
  exportLink: (token: string) => `/api/v1/exports/${token}`,
  exportLinkMetadata: (token: string) => `/api/v1/exports/${token}/metadata`,
  /** Operator BFF cookies (operator-console.md). */
  cookies: ['access', 'refresh', 'expires', 'session', 'challenge', 'challenge_id'].map(n => `salon_operator_${n}`),
} as const;

/**
 * F03-S04's routes (spec API table) and what its handoff named that the spec left open: generated
 * schema names (docs/api/openapi.json), the entry exchange's credential field, the operator list's
 * filter names, the salon-host BFF entry action and the screens' test ids (S04 stage.yaml
 * `handoff.run` and `handoff.notes`, "Test ids"). Filled at feature-verify from the closed handoff.
 */
export const SUPPORT = {
  start: (salonId: string) => `/salons/${salonId}/support-sessions`,
  entryCodes: (sessionId: string) => `/support-sessions/${sessionId}/entry-codes`,
  end: (sessionId: string) => `/support-sessions/${sessionId}`,
  list: '/support-sessions',
  entry: '/api/v1/support/entry',
  /** The support credential ends its own session (S04 handoff; added beside the spec table). */
  ownEnd: '/api/v1/support/session',
  salonSessions: '/api/v1/salon/support-sessions',
  cookies: ['salon_support_access', 'salon_support_session'],
} as const;
export const PENDING_S04 = {
  schemas: {
    supportStart: 'SupportEntry' as string | null,       // 201 {id,expiresAt,entryCode,entryUrl,codeExpiresAt}
    entryCode: 'SupportEntry' as string | null,          // 201 a fresh code, same shape
    supportSessionPage: 'SupportSessionPage' as string | null, // GET /operator/support-sessions
    salonSupportSessionPage: 'SalonSupportSessionPage' as string | null, // GET /salon/support-sessions
  },
  /** Where the entry exchange's answer holds the support access token. */
  credentialField: 'accessToken' as string | null,
  /** Query parameter names of the operator list's salon and state filters. */
  listSalonParam: 'salonId' as string | null,
  listStateParam: 'state' as string | null,
  /** The salon-host BFF action that exchanges an entry code (/identity-web/support/entry). */
  entryBffAction: 'support/entry' as string | null,
  /** Browser test ids of the S04 screens. Keys are QA's names for the controls; values are the handoff's. */
  testIds: {
    startOpen: 'console-support-start', startSheet: 'console-support-sheet', startReason: 'console-support-reason',
    startDuration: 'console-support-duration', startSubmit: 'console-support-submit', startResult: 'console-support-started',
    openWorkspace: 'console-support-started-open',
    sessionList: 'console-support-list', sessionRow: 'console-support-row', sessionEnd: 'console-support-end',
    entryPage: 'support-entry', entryRefused: 'support-entry-refused', entryExpired: 'support-entry-ended', entryUnavailable: 'support-entry-unavailable',
    banner: 'support-banner', bannerEnd: 'support-banner-end',
    accessPage: 'support-access', accessRow: 'support-access-row', accessRevoke: 'support-access-revoke', accessConfirm: 'support-access-confirm-revoke',
  } as Record<string, string | null>,
};
/** An S04 test id from the handoff, or an `ENVIRONMENT:` stop until it is named. */
export function supportTestId(name: string): string {
  const value = PENDING_S04.testIds[name];
  if (!value) throw new Error(`ENVIRONMENT: the F03-S04 handoff has not yet named the test id for ${name} (qa/shared/operator.ts PENDING_S04.testIds)`);
  return value;
}
export function pendingS04<K extends Exclude<keyof typeof PENDING_S04, 'schemas' | 'testIds'>>(key: K): string {
  const value = PENDING_S04[key];
  if (value === null) throw new Error(`ENVIRONMENT: the F03-S04 handoff has not yet named ${key} (qa/shared/operator.ts PENDING_S04)`);
  return value;
}
/** A support response checked against the generated schema the S04 handoff names. */
export async function supportJson<T>(response: APIResponse, schema: keyof typeof PENDING_S04.schemas, status = 200): Promise<T> {
  const name = PENDING_S04.schemas[schema];
  if (name === null) throw new Error(`ENVIRONMENT: the F03-S04 handoff has not yet named the ${schema} schema (qa/shared/operator.ts PENDING_S04)`);
  const answer = await answerOf(response);
  expect(answer.status, `${schema}: HTTP status (body ${answer.status >= 400 ? answer.body.slice(0, 200) : 'withheld'})`).toBe(status);
  const body = JSON.parse(answer.body);
  expect(Boolean(schemaValidator(name)(body)), `${schema}: generated contract shape`).toBe(true);
  return body as T;
}

export type OperatorRole = 'OPERATOR_ADMIN' | 'OPERATOR_SUPPORT';
export type OperatorAccount = { email: string; password: string; role: OperatorRole; displayName: string; totpSecret?: string };
export type OperatorSignIn = components['schemas']['OperatorSignIn'];
export type OperatorPrincipal = { id: string; kind: string; role: OperatorRole; permissions: string[]; mfaEnabled: boolean };
export type OperatorActor = { account: OperatorAccount; tokens: Tokens; principal: OperatorPrincipal; signedInAt?: string };
export type Plan = components['schemas']['PlanDefinition'];
export type PlanInput = Omit<Plan, 'id' | 'createdAt' | 'updatedAt'>;
export type Page<T> = { items: T[]; page: number; size: number; total: number };
export type SalonInput = {
  name: string; slug: string; country: string; currency: string; timeZone: string; defaultLanguage: string;
  contactEmail?: string | null; contactPhone?: string | null; address?: string | null; planId: string;
  owner: { name: string; email: string; password: string };
  subscription?: { monthlyAmount: number; paymentMethod: 'card' | 'bank_transfer' };
};
export type SalonCreated = components['schemas']['SalonCreated'];
export type SalonDetail = components['schemas']['SalonDetail'];
export type SalonSummary = components['schemas']['SalonSummary'];
export type Subscription = components['schemas']['SubscriptionView'];
export type InvoiceSummary = components['schemas']['InvoiceSummary'];
export type Invoice = components['schemas']['InvoiceDetail'];
export type Payment = components['schemas']['PaymentView'];
export type Entitlements = components['schemas']['Entitlements'];
export type LifecycleEvent = components['schemas']['LifecycleEvent'];
export type ExportView = components['schemas']['ExportView'];
export type CapturedEmail = { messageId: string; salonId: string; recipient: string; kind: string; subject: string; body: string; links: string[]; deliveredAt: string };
export type BillingPass = { at: string; changed: { salonId: string; issued: string[]; reminders: string[]; charges: string[]; states: string[] }[]; failed: string[] };
export type ExportPass = { at: string; exports: { salonId: string; exportId: string; tablesStored: number; ready: boolean }[]; failed: string[] };
export type DeletionPass = { at: string; salons: { salonId: string; batches: number; rows: number; deleted: boolean }[]; waiting: string[]; failed: string[] };
export type Background = { salonId: string; status: string; accessState: string;
  outbox: { waiting: number; delivered: number; cancelled: number; dead: number };
  jobs: { due: number; run: number; skipped: number; failed: number; lastRunAt: string | null; lastSkippedAt: string | null } };

/** The exact key sets the contract promises; a field outside them is a failure. */
export const SALON_CREATED_KEYS = ['createdAt', 'hostname', 'id', 'planId', 'slug'];
/** S01's sixteen fields, S02's `version` and S03's `closedAt`, `deleteAt`, `deletedAt`. */
export const SALON_DETAIL_KEYS = ['address', 'closedAt', 'contactEmail', 'contactPhone', 'country', 'createdAt', 'currency', 'defaultLanguage',
  'deleteAt', 'deletedAt', 'hostname', 'id', 'name', 'ownerEmail', 'plan', 'slug', 'status', 'timeZone', 'updatedAt', 'version'];
export const SALON_SUMMARY_KEYS = ['createdAt', 'hostname', 'id', 'name', 'plan', 'slug', 'status'];
export const PLAN_KEYS = ['branches', 'createdAt', 'customDomain', 'dedicatedApp', 'id', 'key', 'messageAllowance', 'name', 'staffSeats', 'updatedAt'];
export const PRINCIPAL_KEYS = ['id', 'kind', 'mfaEnabled', 'permissions', 'role'];

/** Permissions of the finished feature (operator-console.md permission tables, S01 to S04). */
export const OPERATOR_PERMISSIONS: Record<OperatorRole, string[]> = {
  OPERATOR_ADMIN: ['operator.session.manage', 'operator.salon.read', 'operator.salon.create', 'operator.plan.read', 'operator.plan.create',
    'operator.billing.read', 'operator.billing.manage', 'operator.salon.suspend', 'operator.salon.close', 'operator.export.read',
    'operator.support.read', 'operator.support.revoke'],
  OPERATOR_SUPPORT: ['operator.session.manage', 'operator.salon.read', 'operator.support.start'],
};

/** Hosts that must never reach operator authority (S01 AC-2). The console host itself is the control. */
export const NON_CONSOLE_HOSTS = [
  alpha.hostname, PLATFORM_DOMAIN, '127.0.0.1', `api.${PLATFORM_DOMAIN}`, `admin.${PLATFORM_DOMAIN}`, `support.${PLATFORM_DOMAIN}`,
  `www.${PLATFORM_DOMAIN}`, `a.${CONSOLE_HOST}`, `${CONSOLE_HOST}.example.com`, `x${CONSOLE_HOST}`, `console-x.${PLATFORM_DOMAIN}`,
];
/** A short set of the same, for rows repeated over many actions. */
export const WRONG_HOSTS = [alpha.hostname, PLATFORM_DOMAIN, `admin.${PLATFORM_DOMAIN}`, `${CONSOLE_HOST}.example.com`];
export const FORGED_FORWARDING: Record<string, string>[] = [
  {}, { 'X-Forwarded-Host': CONSOLE_HOST }, { Forwarded: `host=${CONSOLE_HOST}` },
];

const hex = (bytes: number) => randomBytes(bytes).toString('hex');
/** A unique slug or plan key in the 3-30 character grammar, never reserved, never reused. */
export const uniqueSlug = (prefix = 'qa') => `${prefix}-${hex(6)}`;
export const strongPassword = () => `QA ${hex(20)}`;
export const DAY = 86_400_000;
export const SECOND = 1_000;
export const plusMs = (instant: string, ms: number) => new Date(Date.parse(instant) + ms).toISOString();
/**
 * Exact instants. The API answers with microseconds (`...30.254315Z`) and JavaScript's Date keeps
 * milliseconds, so an instant the product computed (trial end, a period, the read-only instant) is
 * compared in microseconds, never through Date.
 */
export function instantMicros(iso: string): bigint {
  const m = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)$/.exec(iso);
  if (!m) throw new Error(`not an ISO instant: ${iso}`);
  return BigInt(Date.parse(`${m[1]}${m[3]}`)) * 1000n + BigInt((m[2] ?? '').padEnd(6, '0').slice(0, 6));
}
/** The canonical UTC form of an instant, with six fractional digits. */
export function exact(iso: string): string {
  return isoOf(instantMicros(iso));
}
/** An instant plus a whole number of milliseconds, keeping its microseconds. */
export function plusExact(iso: string, ms: number): string {
  return isoOf(instantMicros(iso) + BigInt(ms) * 1000n);
}
function isoOf(us: bigint): string {
  return `${new Date(Number(us / 1000n)).toISOString().slice(0, 19)}.${String(us % 1_000_000n).padStart(6, '0')}Z`;
}

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
export function provisionedSalon(created: { id: string; slug: string; hostname: string }, input?: SalonInput): Salon {
  return { slug: created.slug, id: created.id, name: input?.name ?? '', brandColour: '', hostname: created.hostname, timeZone: input?.timeZone ?? 'Asia/Colombo' };
}

/**
 * The provisioned Owner's first sign-in at its own salon: enrollment, then a session, returned as a
 * signed-in staff member. The account keeps its TOTP seed so the Owner can sign in again after the
 * test clock moves; a second sign-in in the same TOTP step would be a refused replay.
 */
export async function ownerSession(identity: Identity, salon: Salon, input: SalonInput) {
  const account = ownerOf(input);
  const challenge = await identity.signIn(salon, account);
  expect(challenge.state, 'a new Owner must enroll MFA first').toBe('enroll_mfa');
  expect(challenge.credentials ?? null, 'no session before enrollment').toBeNull();
  const enrollment = await json<components['schemas']['Enrollment']>(await identity.call(salon, 'POST', '/staff/mfa/enroll',
    { challengeId: challenge.challengeId, challengeToken: challenge.challengeToken }), 'Enrollment');
  account.totpSecret = enrollment.secret!;
  const tokens = await identity.verify(salon, challenge, account.totpSecret);
  return { salon, account, tokens, principal: await identity.principal(salon, tokens) };
}

/**
 * A staff member's current credentials as the test clock moves: the last tokens while their access
 * has more than 30 seconds left on the application clock, otherwise a new sign-in (after a clock
 * move, so a new TOTP step and no replay of an earlier code).
 */
export function staffTokens(identity: Identity, salon: Salon, account: SeedAccount, tokens: Tokens) {
  let current = tokens;
  return async (): Promise<Tokens> => {
    if (Date.parse(await identity.now()) < Date.parse(current.accessExpiresAt) - 30_000) return current;
    current = (await identity.login(salon, account)).tokens;
    return current;
  };
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
/**
 * An instant (07:00Z) at least a day after every existing plan's creation. Plans are immutable and
 * never removed (the local reset keeps them), clock-moving tests date some in the future, and the
 * console's plan pickers show the 100 newest on their first page. A test that picks its own plan
 * from that page starts its clock here so the plan is on it, independent of earlier runs.
 */
export function afterNewestPlan(): string {
  const newest = identitySql(`SELECT to_char(greatest(coalesce(max(created_at), now()), now()) AT TIME ZONE 'UTC', 'YYYY-MM-DD') FROM plan`);
  expect(newest, 'the newest plan date').toMatch(/^\d{4}-\d{2}-\d{2}$/);
  return new Date(Date.parse(`${newest}T07:00:00Z`) + DAY).toISOString();
}
/** Rows a salon owns in the S01 bootstrap tables: the registry, its Owner and its brand. */
export function bootstrapRows(slug: string) {
  const tenant = countRows('tenant', `slug = ${sqlLiteral(slug)}`);
  const sub = `(SELECT id FROM tenant WHERE slug = ${sqlLiteral(slug)})`;
  return { tenant, staff: countRows('identity_staff', `tenant_id IN ${sub}`), brand: countRows('salon_brand', `tenant_id IN ${sub}`) };
}
/** Every table with a `tenant_id` column, from the catalogue rather than the product's registry. */
export function tenantTables(): string[] {
  const out = identitySql(`SELECT table_name FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'tenant_id' ORDER BY 1`);
  const tables = out.split('\n').filter(Boolean);
  expect(tables.length, 'the catalogue lists tenant tables').toBeGreaterThan(0);
  return tables;
}
/** Row counts per tenant table for one salon. */
export function tenantRowCounts(salonId: string): Record<string, number> {
  return Object.fromEntries(tenantTables().map(t => [t, countRows(t, `tenant_id = ${sqlLiteral(salonId)}`)]));
}
/**
 * Tenant tables whose rows scheduled jobs remove or add as the test clock moves, for every salon:
 * outbox retention and billing reminders (outbox_message) and the identity security cleanup of
 * expired challenges, steps, rate buckets and refresh history. A comparison across a clock jump
 * of "nothing of another salon changed" uses the durable tables only.
 */
export const TIME_CLEANED_TABLES = ['outbox_message', 'identity_mfa_challenge', 'identity_mfa_step', 'identity_rate_bucket',
  'identity_customer_challenge', 'identity_refresh_history'];
export function durableRowCounts(salonId: string): Record<string, number> {
  return Object.fromEntries(Object.entries(tenantRowCounts(salonId)).filter(([table]) => !TIME_CLEANED_TABLES.includes(table)));
}
/** Platform audit entries for a subject, as `action` strings in order (S01 AC-16, S02 AC-14, S03 AC-11). */
export type PlatformAuditRow = { action: string; actorKind: string; actorId: string; subjectKind: string; subjectId: string; traceId: string | null; details: string; at: string };
export function platformAuditWhere(where: string): PlatformAuditRow[] {
  const rows = identitySql(`SELECT coalesce(json_agg(json_build_object('action', action, 'actorKind', actor_kind, 'actorId', actor_id,
    'subjectKind', subject_kind, 'subjectId', subject_id, 'traceId', trace_id, 'details', coalesce(details::text, ''), 'at', created_at)
    ORDER BY created_at, id), '[]'::json) FROM platform_audit_entry WHERE ${where}`);
  return JSON.parse(rows || '[]');
}
export const platformAudit = (subjectId: string) => platformAuditWhere(`subject_id = ${sqlLiteral(subjectId)}`);

export async function operatorJson<T>(response: APIResponse, schema: keyof typeof CONTRACT.schemas, status = 200): Promise<T> {
  const answer = await answerOf(response);
  expect(answer.status, `${schema}: HTTP status (body ${answer.status >= 400 ? answer.body.slice(0, 200) : 'withheld'})`).toBe(status);
  expect(answer.contentType, `${schema}: content type`).toBe('application/json');
  const body = JSON.parse(answer.body);
  const validate = schemaValidator(CONTRACT.schemas[schema]);
  // A credential-bearing body is never printed; only whether it matched.
  expect(Boolean(validate(body)), `${schema}: generated contract shape`).toBe(true);
  return body as T;
}

/** Problem details with a stable code. Codes are strings here because S04 registers new ones. */
export async function operatorProblem(response: APIResponse, status: number, code: string) {
  const answer = await answerOf(response);
  expect(answer.status, `HTTP status for ${code} (body ${answer.body.slice(0, 200)})`).toBe(status);
  const problem = expectProblem(answer, code);
  expect(problem.code).toBe(code);
  return problem as typeof problem & { errors?: { field?: string }[] };
}

/** The field names a 400 names (Problem.errors[].field, a dotted path when nested). */
export function fieldsOf(problem: { errors?: { field?: string }[] }): string[] {
  return (problem.errors ?? []).map(error => error.field ?? '').filter(Boolean);
}

export function expectNoStore(response: APIResponse, label: string) {
  expect(response.headers()['cache-control'] ?? '', `${label}: no-store`).toContain('no-store');
}

/** Every mutating route a salon host serves, from the committed contract (identity and salon APIs). */
export function tenantMutationRoutes(): { method: string; path: string }[] {
  const out: { method: string; path: string }[] = [];
  for (const [route, ops] of Object.entries(committedContract().paths)) {
    if (!route.startsWith('/api/v1/identity') && !route.startsWith('/api/v1/salon')) continue;
    for (const method of Object.keys(ops as object)) {
      if (['post', 'put', 'patch', 'delete'].includes(method)) out.push({ method: method.toUpperCase(), path: route });
    }
  }
  expect(out.length, 'the contract has mutating tenant routes').toBeGreaterThan(0);
  return out;
}

/** A request on a salon host by full API path, with an optional bearer. */
export function onSalonHost(identity: Identity, salon: Salon, method: string, route: string, tokens?: Tokens, data?: unknown) {
  return identity.api.send(method, route, { headers: { Host: salon.hostname, ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}),
    ...(data === undefined ? {} : { 'Content-Type': 'application/json' }) }, data: data === undefined ? undefined : JSON.stringify(data) });
}

/** A problem body without its per-request fields, for "the same answer as an unknown one". */
export const comparable = (text: string) => text.replace(/"(traceId|instance)":"[^"]*"/g, '');

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

  hook(name: keyof typeof CONTRACT.hooks, data?: unknown, params: Record<string, string> = {}, query = '', raw?: string) {
    const hook = CONTRACT.hooks[name];
    const route = Object.entries(params).reduce<string>((p, [k, v]) => p.replace(`{${k}}`, encodeURIComponent(v)), hook.path);
    return this.call(hook.method, `${route}${query}`, { data, raw });
  }

  private async hookOk<T>(name: keyof typeof CONTRACT.hooks, status: number, data?: unknown, params: Record<string, string> = {}, query = ''): Promise<T> {
    const r = await this.hook(name, data, params, query);
    if (r.status() === 404 && !Object.keys(params).length) throw new Error(`ENVIRONMENT: the local hook ${CONTRACT.hooks[name].path} is not available (local profile, loopback)`);
    expect(r.status(), `hook ${name} (${(await r.text()).slice(0, 200)})`).toBe(status);
    return (status === 204 ? undefined : await r.json()) as T;
  }

  /**
   * Back to the synthetic baseline (S03 handoff): removes every salon created after the baseline,
   * resets hook-created operators, returns the clock to real time and reschedules pushed-ahead
   * jobs. Alpha, Beta and Gamma are untouched, so F01/F02 guards run unchanged afterwards. Called
   * in every F03 test's teardown, after disarming every one-shot fault a failed test may have left.
   */
  async resetToBaseline() {
    await this.hookOk('billingInterrupt', 200, { point: null });
    await this.hookOk('exportInterrupt', 200, { afterTables: null });
    await this.hookOk('deletionInterrupt', 200, { afterBatches: null });
    const result = await this.hookOk<{ removedSalons: number; clock: string }>('localReset', 200);
    expect(result.clock, 'local reset returned the clock to real time').toBe('real');
  }

  async resetFixtures() { await this.hookOk('fixtures', 204); }

  async seed(...accounts: OperatorAccount[]) {
    const body = await this.hookOk<{ accounts: number }>('seed', 200,
      { accounts: accounts.map(a => ({ email: a.email, displayName: a.displayName, role: a.role, password: a.password, totpSecret: a.totpSecret ?? null })) });
    expect(body.accounts).toBe(accounts.length);
  }

  async bootstrapFailure(step: 'owner' | 'brand' | 'receipt' | null) {
    expect(await this.hookOk('bootstrapFailure', 200, { step })).toEqual({ step });
  }

  /** Disables or re-enables a hook-created operator (S02 handoff). */
  async setActive(account: OperatorAccount, active: boolean) { await this.hookOk('operatorActive', 204, { email: account.email, active }); }

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

  verifyRaw(challenge: Pick<OperatorSignIn, 'challengeId' | 'challengeToken'>, code: string) {
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
    return { account, tokens, principal: await this.me(tokens), signedInAt: await this.now() };
  }

  async me(tokens: Tokens): Promise<OperatorPrincipal> { return operatorJson(await this.call('GET', '/me', { tokens }), 'principal'); }

  async actor(role: OperatorRole = 'OPERATOR_ADMIN'): Promise<OperatorActor> {
    const account = operatorAccount(role);
    await this.seed(account);
    return this.login(account);
  }

  /**
   * Fresh credentials for an actor after the test clock moved: access lasts five minutes and a
   * session seven days. Renewal first; a full sign-in (a new TOTP step) when the session ended or
   * ends within ten minutes (billing's due instant is exactly seven days after issue, so a renewal
   * there could land milliseconds before the session's absolute end).
   */
  async renew(actor: OperatorActor): Promise<OperatorActor> {
    const now = Date.parse(await this.now());
    const nearEnd = !actor.signedInAt || now - Date.parse(actor.signedInAt) >= 7 * DAY - 10 * 60_000;
    if (!nearEnd) {
      const r = await this.refreshRaw(actor.tokens.refreshToken);
      if (r.status() === 200) { actor.tokens = await this.tokens(r); return actor; }
    }
    const fresh = await this.login(actor.account);
    actor.tokens = fresh.tokens;
    actor.signedInAt = fresh.signedInAt;
    return actor;
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

  async salonPage(actor: OperatorActor, query = ''): Promise<Page<SalonSummary>> {
    return operatorJson(await this.call('GET', `/salons${query}`, { tokens: actor.tokens }), 'salonPage');
  }

  async planPage(actor: OperatorActor, query = ''): Promise<Page<Plan>> {
    return operatorJson(await this.call('GET', `/plans${query}`, { tokens: actor.tokens }), 'planPage');
  }

  async version(actor: OperatorActor, salonId: string): Promise<number> {
    return (await this.salonDetail(actor, salonId)).version;
  }

  // ---- F03-S02 billing (S02 API table and handoff) ----
  subscriptionRaw(actor: OperatorActor | null, salonId: string, extra: { host?: string } = {}) {
    return this.call('GET', `/salons/${salonId}/subscription`, { tokens: actor?.tokens, ...extra });
  }
  async subscription(actor: OperatorActor, salonId: string): Promise<Subscription> {
    return operatorJson(await this.subscriptionRaw(actor, salonId), 'subscription');
  }
  startSubscription(actor: OperatorActor | null, salonId: string, data: Record<string, unknown>, key: string = randomUUID()) {
    return this.call('POST', `/salons/${salonId}/subscription`, { tokens: actor?.tokens, data, headers: { 'Idempotency-Key': key } });
  }
  changeSubscription(actor: OperatorActor | null, salonId: string, data: Record<string, unknown>) {
    return this.call('PATCH', `/salons/${salonId}/subscription`, { tokens: actor?.tokens, data });
  }
  movePlan(actor: OperatorActor | null, salonId: string, version: number, planId: string) {
    return this.call('PUT', `/salons/${salonId}/plan`, { tokens: actor?.tokens, data: { version, planId } });
  }
  async invoices(actor: OperatorActor, salonId: string, query = '?size=100'): Promise<Page<InvoiceSummary>> {
    return operatorJson(await this.call('GET', `/salons/${salonId}/invoices${query}`, { tokens: actor.tokens }), 'invoicePage');
  }
  async invoice(actor: OperatorActor, invoiceId: string): Promise<Invoice> {
    return operatorJson(await this.call('GET', `/invoices/${invoiceId}`, { tokens: actor.tokens }), 'invoice');
  }
  recordTransfer(actor: OperatorActor | null, invoiceId: string, data: Record<string, unknown>, key: string = randomUUID()) {
    return this.call('POST', `/invoices/${invoiceId}/payments`, { tokens: actor?.tokens, data, headers: { 'Idempotency-Key': key } });
  }
  /** Pays an invoice by an exact transfer received on the clock's current date. */
  async pay(actor: OperatorActor, invoice: { id: string; amount: number }): Promise<Payment> {
    const receivedOn = (await this.now()).slice(0, 10);
    return operatorJson(await this.recordTransfer(actor, invoice.id, { reference: `QA-${hex(4)}`, amount: invoice.amount, receivedOn }), 'payment', 201);
  }
  async runBilling(): Promise<BillingPass> { return this.hookOk('billingRun', 200); }
  async armBillingInterrupt(point: 'after-invoice-issue' | null) { expect(await this.hookOk('billingInterrupt', 200, { point })).toEqual({ point }); }
  async providerOutcome(salonId: string, outcome: 'success' | 'failure') {
    expect(await this.hookOk('providerOutcome', 200, { outcome }, { salonId })).toEqual({ salonId, outcome });
  }
  /** The local provider key's signature of the exact body (the key never leaves the API). */
  async sign(body: string): Promise<string> {
    const r = await this.hook('providerSign', undefined, {}, '', body);
    if (r.status() === 404) throw new Error('ENVIRONMENT: the provider signing hook is not available (no local provider key?)');
    expect(r.status()).toBe(200);
    const signed = await r.json() as { header: string; signature: string };
    expect(signed.header.toLowerCase(), 'the documented signature header').toBe(CONTRACT.signatureHeader.toLowerCase());
    expect(signed.signature).toMatch(/^[0-9a-f]{64}$/);
    return signed.signature;
  }
  postEvent(body: string, signature: string | null, host: string = PLATFORM_HOST) {
    return this.identity.api.send('POST', CONTRACT.providerEvents, {
      headers: { Host: host, 'Content-Type': 'application/json', ...(signature === null ? {} : { [CONTRACT.signatureHeader]: signature }) }, data: body });
  }
  async emails(recipient: string): Promise<CapturedEmail[]> {
    return (await this.hookOk<{ items: CapturedEmail[] }>('emails', 200, undefined, {}, `?recipient=${encodeURIComponent(recipient)}`)).items;
  }
  /** Captured emails of a kind, waiting for the outbox to deliver `count` of them, then proving no more arrive. */
  async emailsOfKind(recipient: string, kind: string, count: number): Promise<CapturedEmail[]> {
    await expect.poll(async () => (await this.emails(recipient)).filter(e => e.kind === kind).length,
      { message: `${count} ${kind} email(s) to the Owner contact`, timeout: 15_000, intervals: [250, 500, 1000] }).toBeGreaterThanOrEqual(count);
    const found = (await this.emails(recipient)).filter(e => e.kind === kind);
    expect(found.length, `exactly ${count} ${kind} email(s)`).toBe(count);
    return found;
  }
  async unbilledSalon(slug?: string): Promise<Salon> {
    const created = await this.hookOk<{ id: string; slug: string; hostname: string }>('unbilledSalon', 201, slug ? { slug } : {});
    return provisionedSalon(created);
  }

  // ---- F03-S03 lifecycle and export (S03 API table and handoff) ----
  suspend(actor: OperatorActor | null, salonId: string, version: number, reason = 'QA suspension reason', extra: { host?: string } = {}) {
    return this.call('POST', `/salons/${salonId}/suspension`, { tokens: actor?.tokens, data: { version, reason }, ...extra });
  }
  reactivate(actor: OperatorActor | null, salonId: string, version: number, reason = 'QA reactivation reason', extra: { host?: string } = {}) {
    return this.call('DELETE', `/salons/${salonId}/suspension`, { tokens: actor?.tokens, data: { version, reason }, ...extra });
  }
  close(actor: OperatorActor | null, salonId: string, version: number, confirmSlug: string, reason = 'QA closure reason', extra: { host?: string } = {}) {
    return this.call('POST', `/salons/${salonId}/closure`, { tokens: actor?.tokens, data: { version, confirmSlug, reason }, ...extra });
  }
  async lifecycle(actor: OperatorActor, salonId: string, query = '?size=100'): Promise<Page<LifecycleEvent>> {
    return operatorJson(await this.call('GET', `/salons/${salonId}/lifecycle${query}`, { tokens: actor.tokens }), 'lifecyclePage');
  }
  async exportStatus(actor: OperatorActor, salonId: string): Promise<ExportView> {
    return operatorJson(await this.call('GET', `/salons/${salonId}/export`, { tokens: actor.tokens }), 'exportView');
  }
  archiveRaw(actor: OperatorActor | null, salonId: string, extra: { host?: string } = {}) {
    return this.call('GET', `/salons/${salonId}/export/archive`, { tokens: actor?.tokens, ...extra });
  }
  linkRaw(token: string, host: string = PLATFORM_HOST) { return this.identity.api.getOn(host, CONTRACT.exportLink(token)); }
  linkMetadataRaw(token: string, host: string = PLATFORM_HOST) { return this.identity.api.getOn(host, CONTRACT.exportLinkMetadata(token)); }
  async runExports(): Promise<ExportPass> { return this.hookOk('exportRun', 200); }
  async runDeletions(): Promise<DeletionPass> { return this.hookOk('deletionRun', 200); }
  async armExportInterrupt(afterTables: number | null) { expect(await this.hookOk('exportInterrupt', 200, { afterTables })).toEqual({ afterTables }); }
  async armDeletionInterrupt(afterBatches: number | null) { expect(await this.hookOk('deletionInterrupt', 200, { afterBatches })).toEqual({ afterBatches }); }
  async background(salonId: string): Promise<Background> { return this.hookOk('background', 200, undefined, { salonId }); }

  // ---- F03-S04 support sessions (S04 API table and handoff) ----
  startSupport(actor: OperatorActor | null, salonId: string, data: Record<string, unknown> = { reason: 'QA support reason text' }, extra: { host?: string } = {}) {
    return this.call('POST', SUPPORT.start(salonId), { tokens: actor?.tokens, data, ...extra });
  }
  newEntryCode(actor: OperatorActor | null, sessionId: string) { return this.call('POST', SUPPORT.entryCodes(sessionId), { tokens: actor?.tokens }); }
  endSupport(actor: OperatorActor | null, sessionId: string) { return this.call('DELETE', SUPPORT.end(sessionId), { tokens: actor?.tokens }); }
  supportList(actor: OperatorActor, filters: { salonId?: string; state?: string } = {}) {
    const query = [filters.salonId ? `${pendingS04('listSalonParam')}=${filters.salonId}` : '', filters.state ? `${pendingS04('listStateParam')}=${filters.state}` : '']
      .filter(Boolean).join('&');
    return this.call('GET', `${SUPPORT.list}${query ? `?${query}` : ''}`, { tokens: actor.tokens });
  }
  /** Exchanges an entry code directly with the API on a host (the BFF path is a browser case). */
  exchange(code: string, host: string) {
    return this.call('POST', SUPPORT.entry, { data: { code }, host });
  }
  /** The support access token from a successful exchange, as `Tokens` for the identity helpers. */
  async supportCredential(response: APIResponse): Promise<Tokens> {
    expect(response.status(), 'the exchange issued a credential (200, S04 spec)').toBe(200);
    const body = await response.json() as Record<string, unknown>;
    const field = pendingS04('credentialField');
    const access = field.split('.').reduce<unknown>((v, k) => (v as Record<string, unknown> | undefined)?.[k], body);
    expect(typeof access, 'a support access token').toBe('string');
    return { accessToken: access as string } as Tokens;
  }
}

export type SupportStart = { id: string; expiresAt: string; entryCode: string; entryUrl: string; codeExpiresAt: string };
export type SupportSession = { id: string; salonId?: string; reason: string; state: string; expiresAt: string };

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

export const test = identityTest.extend<{ operator: Operator; clock: TestClock }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  clock: async ({ identity }, use) => {
    const clock = new TestClock(identity);
    try { await use(clock); } finally { await clock.real(); }
  },
});

export { expect, alpha, beta, gamma };
