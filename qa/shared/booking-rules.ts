/**
 * F04-S04 booking rules fixtures: the public read, the settings read and save, the history, rule
 * builders, the prototype's customer sentences (`ruText`) as the expected text, and read-only
 * database observations of the one table this stage adds.
 *
 * Routes, bodies, codes and shapes come from the F04-S04 spec ("API and data changes", "Corrections
 * at close") and the generated contract (docs/api/openapi.json). The table is the one the stage's
 * migration creates (`booking_rules_version`, handoff "data"), observed only, never written except
 * inside a transaction that is rolled back.
 *
 * A salon keeps its versions for ever (they are immutable), so a test that must start from "never
 * saved" uses a salon the operator creates in the test; a test on alpha works from its current
 * version and saves values no earlier save can share (`distinct`).
 */
import type { APIResponse } from '@playwright/test';

import type { components } from '@contracts/schema';

import { answerOf, expectProblem } from './answers';
import { expect, json, type Identity, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { onSalonHost, sqlLiteral } from './operator';
import type { Salon } from './tenants';

export const PUBLIC_RULES = '/api/v1/salon/booking-rules';
export const RULES_SETTINGS = '/api/v1/salon/booking-rules/settings';
export const RULES_HISTORY = '/api/v1/salon/booking-rules/history';
export const RULES_TABLE = 'booking_rules_version';

export type Rules = components['schemas']['BookingRules'];
export type RulesInput = components['schemas']['BookingRulesInput'];
export type RulesSettings = components['schemas']['BookingRulesSettings'];
export type PublicRules = components['schemas']['PublicBookingRules'];
export type History = components['schemas']['BookingRulesHistory'];
export type HistoryEntry = components['schemas']['BookingRulesHistoryEntry'];

/** The eight rule names, in the contract's order (also the history's `changedFields` values). */
export const RULE_NAMES = ['slotIntervalMinutes', 'minimumNoticeMinutes', 'bookingHorizonDays', 'deposit', 'freeCancellationHours',
  'lateCancellation', 'noShow', 'reschedule'] as const;

/** AC-1: the prototype's suggested rules, as the API stores them (Rs. 2,000 is 200000 minor units). */
export const SUGGESTED: RulesInput = {
  slotIntervalMinutes: 15,
  minimumNoticeMinutes: 60,
  bookingHorizonDays: 60,
  deposit: { kind: 'percentage', percent: 20, capMinor: 200000 },
  freeCancellationHours: 24,
  lateCancellation: { kind: 'deposit' },
  noShow: { kind: 'deposit' },
  reschedule: { limit: 2, untilHours: 12 },
};

/** AC-2 and AC-3: every value the API must accept. */
export const ALLOWED = {
  interval: [10, 15, 20, 30],
  notice: [0, 30, 60, 120, 240, 1440],
  horizon: [14, 30, 60, 90, 180],
  free: [2, 6, 12, 24, 48],
  until: [2, 6, 12, 24],
};

/** Rules with some fields replaced. */
export const rules = (overrides: Partial<RulesInput> = {}): RulesInput => ({ ...structuredClone(SUGGESTED), ...structuredClone(overrides) });

/**
 * Rules no earlier save of alpha can equal: a percentage deposit whose cap is a random whole rupee
 * amount between Rs. 500 and about Rs. 9 million, so an identical-save refusal never trips a test.
 */
export function distinct(overrides: Partial<RulesInput> = {}): RulesInput {
  const rupees = 500 + Math.floor(Math.random() * 9_000_000);
  return rules({ deposit: { kind: 'percentage', percent: 20, capMinor: rupees * 100 }, ...overrides });
}

/** What a saved input reads back as (the API answers every field; reschedule keeps nulls). */
export const stored = (input: RulesInput): Rules => structuredClone(input) as Rules;

export class BookingRules {
  constructor(readonly identity: Identity) {}

  call(salon: Salon, method: string, route: string, tokens?: Tokens, data?: unknown): Promise<APIResponse> {
    return onSalonHost(this.identity, salon, method, route, tokens, data);
  }

  async settings(salon: Salon, tokens: Tokens): Promise<RulesSettings> {
    return json<RulesSettings>(await this.call(salon, 'GET', RULES_SETTINGS, tokens), 'BookingRulesSettings');
  }
  saveRaw(salon: Salon, tokens: Tokens | undefined, version: number, body: unknown): Promise<APIResponse> {
    return this.call(salon, 'PUT', RULES_SETTINGS, tokens, { version, rules: body });
  }
  async save(salon: Salon, tokens: Tokens, input: RulesInput, version?: number): Promise<RulesSettings> {
    const from = version ?? (await this.settings(salon, tokens)).version;
    return json<RulesSettings>(await this.saveRaw(salon, tokens, from, input), 'BookingRulesSettings');
  }
  historyRaw(salon: Salon, tokens: Tokens | undefined, query = ''): Promise<APIResponse> {
    return this.call(salon, 'GET', `${RULES_HISTORY}${query}`, tokens);
  }
  async history(salon: Salon, tokens: Tokens, query = ''): Promise<History> {
    return json<History>(await this.historyRaw(salon, tokens, query), 'BookingRulesHistory');
  }

  /** The public read on the salon's hostname with no credential: the body as sent and as parsed. */
  async publicRaw(salon: Salon, tokens?: Tokens): Promise<{ status: number; contentType: string; text: string; body: PublicRules | null }> {
    const response = tokens ? await this.call(salon, 'GET', PUBLIC_RULES, tokens) : await this.identity.api.getOn(salon.hostname, PUBLIC_RULES);
    const text = await response.text();
    return { status: response.status(), contentType: (response.headers()['content-type'] ?? '').split(';')[0], text, body: text ? JSON.parse(text) : undefined };
  }
  /** The public current rules, checked against the contract (`PublicBookingRules`). */
  async published(salon: Salon): Promise<PublicRules> {
    return json<PublicRules>(await this.identity.api.getOn(salon.hostname, PUBLIC_RULES), 'PublicBookingRules');
  }
}

// ---- The customer summary (AC-4): the prototype's ruText, written out, with formatMoney's rupees ----

/** `formatMoney` for LKR whole rupees as the prototype's `fm` shows them: `Rs. 2,000`. */
export const rupees = (minor: number) => `Rs. ${new Intl.NumberFormat('en-LK', { maximumFractionDigits: 0 }).format(minor / 100)}`;

/**
 * The four sentences the prototype's `ruText` writes for a set of rules, without its bold markup.
 * This is the expected text of "What customers see before paying"; it is written here from the
 * prototype, not taken from the product's translation file.
 */
export function prototypeSentences(r: RulesInput): string[] {
  const d = r.deposit;
  const deposit = d.kind === 'none' ? 'No deposit. You pay at the salon.'
    : d.kind === 'percentage' ? `Deposit: ${d.percent}% of the price, up to ${rupees(d.capMinor!)}, taken when you book.`
      : `Deposit: ${rupees(d.amountMinor!)}, taken when you book.`;
  const keep = (k: { kind: string; percent?: number }) => k.kind === 'nothing' ? 'nothing is kept'
    : k.kind === 'deposit' ? (d.kind === 'none' ? 'nothing is kept' : 'the deposit is kept') : `${k.percent}% of the price is kept`;
  const times = r.reschedule.limit === null ? 'as often as you need' : r.reschedule.limit === 1 ? 'once' : 'twice';
  return [
    deposit,
    `Free cancellation until ${r.freeCancellationHours} hours before your appointment.`,
    `After that, ${keep(r.lateCancellation)}. If you don’t come, ${keep(r.noShow)}.`,
    r.reschedule.limit === 0 ? 'To reschedule, contact the salon.' : `You can reschedule ${times}, up to ${r.reschedule.untilHours} hours before.`,
  ];
}

// ---- Answers ----

/** Asserts a refused call answered `status` with `code`. */
export async function refused(response: APIResponse, status: number, code: string, label: string) {
  const answer = await answerOf(response);
  expect(answer.status, `${label}: HTTP status (body ${answer.body.slice(0, 300)})`).toBe(status);
  const problem = expectProblem(answer, label);
  expect(problem.code, `${label}: code`).toBe(code);
  return { answer, problem };
}

/** A 400 bad_request; returns its field errors. */
export async function fieldErrors(response: APIResponse, label: string) {
  const { problem, answer } = await refused(response, 400, 'bad_request', label);
  return { errors: problem.errors ?? [], body: answer.body };
}

// ---- Database observations (owner role unless a tenant is given) ----

/** How many versions a salon has, as the owning role sees them. */
export const versionRows = (salonId: string) => Number(identitySql(`SELECT count(*) FROM ${RULES_TABLE} WHERE tenant_id = ${sqlLiteral(salonId)}`));

/** A digest of every version row of a salon, to prove a refused call changed nothing. */
export const rulesSnapshot = (salonId: string) => identitySql(
  `SELECT coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY x.version)), 'none') FROM ${RULES_TABLE} x WHERE tenant_id = ${sqlLiteral(salonId)}`);

/** One version's whole stored row, as text, by its id. */
export const versionRow = (salonId: string, versionId: string) => identitySql(
  `SELECT row_to_json(x)::text FROM ${RULES_TABLE} x WHERE tenant_id = ${sqlLiteral(salonId)} AND id = ${sqlLiteral(versionId)}`);

export type AuditRow = { action: string; actorKind: string; actorId: string; subjectKind: string; subjectId: string; at: string; details: string };

/** The salon's audit entries of booking actions (`booking.*`), oldest first (owner role). */
export function rulesAudit(salonId: string): AuditRow[] {
  const out = identitySql(`SELECT json_build_object('action', action, 'actorKind', actor_kind, 'actorId', actor_id, 'subjectKind', subject_kind,
      'subjectId', subject_id, 'at', created_at, 'details', row_to_json(a)::text)
    FROM audit_entry a WHERE tenant_id = ${sqlLiteral(salonId)} AND action LIKE 'booking.%' ORDER BY created_at, id`);
  return out ? out.split('\n').map(line => JSON.parse(line) as AuditRow) : [];
}
