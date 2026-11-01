import 'server-only';

import { createApiClient, type components } from '@salon/api-contracts/booking';

import { createServerApiClient } from '@/lib/api-core/client';

type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };
type RulesInput = components['schemas']['BookingRulesInput'];
type DepositInput = components['schemas']['DepositRuleInput'];

const record = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
const text = (value: unknown): string => (typeof value === 'string' ? value : '');
/** A number as the browser sent it; anything else is passed on as it came, for the API to refuse by name. */
const number = (value: unknown) => value as number;
/** A number or null as sent (the reschedule limit and time); absent becomes null, which the API judges. */
const numberOrNull = (value: unknown) => (value === undefined ? null : (value as number | null));
/** An optional number: absent stays absent, so a deposit sends only the fields of its kind. */
const optional = (value: unknown) => (value === undefined || value === null ? undefined : (value as number));

function deposit(value: unknown): DepositInput {
  const each = record(value);
  const input: DepositInput = { kind: text(each.kind) as DepositInput['kind'] };
  if (optional(each.percent) !== undefined) input.percent = optional(each.percent);
  if (optional(each.capMinor) !== undefined) input.capMinor = optional(each.capMinor);
  if (optional(each.amountMinor) !== undefined) input.amountMinor = optional(each.amountMinor);
  return input;
}

function retention<K extends string>(value: unknown): { kind: K; percent?: number } {
  const each = record(value);
  const percent = optional(each.percent);
  return percent === undefined ? { kind: text(each.kind) as K } : { kind: text(each.kind) as K, percent };
}

function rulesOf(value: unknown): RulesInput {
  const rules = record(value);
  const reschedule = record(rules.reschedule);
  return {
    slotIntervalMinutes: number(rules.slotIntervalMinutes),
    minimumNoticeMinutes: number(rules.minimumNoticeMinutes),
    bookingHorizonDays: number(rules.bookingHorizonDays),
    deposit: deposit(rules.deposit),
    freeCancellationHours: number(rules.freeCancellationHours),
    lateCancellation: retention<RulesInput['lateCancellation']['kind']>(rules.lateCancellation),
    noShow: retention<RulesInput['noShow']['kind']>(rules.noShow),
    reschedule: { limit: numberOrNull(reschedule.limit), untilHours: numberOrNull(reschedule.untilHours) },
  };
}

/**
 * The booking paths the workspace BFF reaches for a settings user, under the salon's hostname (F04-S04): the
 * rules as settings staff keep them, their save, and their history. Every request is rebuilt from the contract's
 * own fields; the BFF checks the session and the permission before any of them. Null when the API is not
 * configured.
 */
export function salonBookingRulesFor(hostname: string) {
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return null;
  return {
    rules: (call: Call) => client.GET('/api/v1/salon/booking-rules/settings', call),
    saveRules: (call: Call, version: unknown, rules: unknown) =>
      client.PUT('/api/v1/salon/booking-rules/settings', {
        ...call,
        body: { version: version as number, rules: rulesOf(rules) },
      }),
    history: (call: Call, page: number) =>
      client.GET('/api/v1/salon/booking-rules/history', { ...call, params: { query: { page, size: 10 } } }),
    // The rules as customers see them, for the Services page's "Salon rule" deposit (F05-S02).
    publicRules: (call: Call) => client.GET('/api/v1/salon/booking-rules', call),
  };
}
