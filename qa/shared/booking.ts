/**
 * F07 booking fixtures: the booking endpoints, hold builders, a salon arranged so a desk can book in it, the
 * customer as the desk types them, and read-only database observations of the booking tables.
 *
 * Routes, bodies, codes and shapes come from the F07-S01 spec ("API and data changes", the criteria) and its handoff
 * ("FINAL ENDPOINT SHAPES", "OVERLAP MECHANISM", the audit actions), decisions 108 and 109, and the generated
 * contract. Every name the build gives (a table, a column, a job, an audit action, an outbox type) is in
 * `PENDING_F07_S01`, confirmed against the handoff and V32 at the dry run (afaa6477); nothing else in the F07 tests
 * spells one. S02's and S03's names are in `PENDING_F07_S02` and `PENDING_F07_S03`, from their specs, and are
 * confirmed when each stage closes.
 *
 * A salon keeps its bookings for ever, and a fixed salon's staff are reset around every test, which would leave a
 * team member linked to an account that no longer exists. So every F07 test books in salons the operator creates
 * now (`bookingSalon`), which the operator fixture removes after the test. The test clock fixes "now" at 08:00 in
 * the salon's zone on a date after every existing plan, so the same test passes at any hour of any day.
 */
import { randomUUID } from 'node:crypto';

import type { APIResponse } from '@playwright/test';

import type { components } from '@contracts/schema';

import { BookingRules, refused, rules, type RulesInput } from './booking-rules';
import { Catalogue, CatalogueS02, rupees, serviceInput, variantInput, type Service, type ServiceS02 } from './catalogue';
import { expect, json, type Identity, type Role, type SignedStaff, type Staff, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { mobile, person } from './people';
import { afterNewestPlan, fieldsOf, onSalonHost, sqlLiteral, type Operator, type TestClock } from './operator';
import { Hours, localDate, plusDays, week } from './salon-hours';
import { Team, memberInput, teamSalon, type TeamMember } from './team';
import type { Salon } from './tenants';

// ---------------------------------------------------------------- names the build gives

/** F07-S01's names, from its handoff and V32 (confirmed at the dry run, afaa6477). */
export const PENDING_F07_S01 = {
  tables: { booking: 'booking_appointment', history: 'booking_state_change' },
  /** The exclusion constraint and the reference constraint (handoff, "OVERLAP MECHANISM"; V32). */
  constraints: { overlap: 'booking_appointment_no_overlap', reference: 'booking_appointment_reference' },
  /** Internal states of an ended hold: kept as a row, never shown, occupying nothing (decision 109 b). */
  endedHold: { released: 'released', expired: 'expired' },
  job: 'booking-hold-expiry',
  /** Audit actions (handoff, note 14), subject kind and the system actor's id. */
  audit: {
    placed: 'booking.hold_placed', confirmed: 'booking.confirmed', released: 'booking.hold_released', expired: 'booking.hold_expired',
    arrived: 'booking.arrived', started: 'booking.service_started', cancelled: 'booking.cancelled', noShow: 'booking.no_show_marked',
    noShowUndone: 'booking.no_show_undone', subject: 'booking_appointment', systemActor: 'booking',
  },
  outboxType: 'booking.BookingConfirmed.v1',
  schemas: { booking: 'Booking', detail: 'BookingDetail', page: 'BookingPage' },
} as const;

/** F07-S02's names, from its spec; confirmed against its handoff when it closes. */
export const PENDING_F07_S02 = {
  schemas: { availability: 'BookingAvailability', openDays: 'BookingOpenDays' },
  reasons: ['closed', 'nobody_performs', 'not_working', 'fully_booked'] as const,
  strategies: ['least_booked', 'most_booked'] as const,
  holdMinutes: [5, 10, 15, 20, 30] as const,
  /** Where a booking records that it was assigned for "No preference" (AC-5, for F13): a field the handoff names. */
  assignedField: 'assigned',
} as const;

// ---------------------------------------------------------------- routes and shapes

export const BOOKINGS = '/api/v1/bookings';
export const bookingPath = (id: string) => `${BOOKINGS}/${id}`;
export const AVAILABILITY = `${BOOKINGS}/availability`;
export const OPEN_DAYS = `${BOOKINGS}/open-days`;

/** The staff actions, in the order `allowedActions` lists them (handoff). */
export const ACTIONS = ['confirm', 'release', 'arrive', 'start', 'cancel', 'no-show', 'undo-no-show'] as const;
export type Action = typeof ACTIONS[number];
/** The actions `booking.manage` reaches, and those `booking.attend` reaches (AC-12). */
export const MANAGE_ACTIONS: Action[] = ['confirm', 'release', 'cancel', 'no-show', 'undo-no-show'];
export const ATTEND_ACTIONS: Action[] = ['arrive', 'start'];

/** The channels staff may send (AC-2), and the two only F15 writes. */
export const STAFF_CHANNELS = ['phone', 'whatsapp', 'front_desk', 'walk_in', 'instagram', 'google', 'staff_web'] as const;
export const APP_CHANNELS = ['customer_app', 'shared_app_link'] as const;
export const CANCEL_REASONS = ['customer_asked', 'salon_unable', 'booked_by_mistake', 'other'] as const;

export type Booking = components['schemas']['Booking'];
export type BookingDetail = components['schemas']['BookingDetail'];
export type BookingPage = components['schemas']['BookingPage'];
export type HistoryEntry = components['schemas']['BookingHistoryEntry'];
export type PlaceBooking = components['schemas']['PlaceBooking'];

/** Decision 108 (d): six characters of this alphabet, shown as `K7M-4QX`. */
export const REFERENCE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const REFERENCE_SHOWN = new RegExp(`^[${REFERENCE_ALPHABET}]{3}-[${REFERENCE_ALPHABET}]{3}$`);

export const LIMITS = { nameMin: 2, nameMax: 60, note: 200, rangeDays: 31, pageMax: 100, holdMinutes: 10 } as const;

// ---------------------------------------------------------------- time

/** The salons F07 makes are in Colombo (+05:30, no daylight saving). */
export const COLOMBO = 'Asia/Colombo';
export const OFFSET = '+05:30';
/** A local date and `HH:mm` in Colombo as the offset date-time the API answers, to the second. */
export const localAt = (date: string, time: string) => `${date}T${time}:00${OFFSET}`;
/** The same instant in UTC, for the test clock. */
export const utcOf = (date: string, time: string) => new Date(Date.parse(localAt(date, time))).toISOString();
export const minutesBetween = (a: string, b: string) => (Date.parse(b) - Date.parse(a)) / 60_000;
/** `HH:mm` plus minutes, on the same day. */
export function plusMinutes(time: string, minutes: number): string {
  const [h, m] = time.split(':').map(Number);
  const total = h * 60 + m + minutes;
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}

// ---------------------------------------------------------------- the customer

/** A customer as the desk types them: a realistic name and a Sri Lankan mobile in the local form `07XXXXXXXX`. */
export type Customer = { name: string; mobile: string };
/**
 * A mobile libphonenumber calls a Sri Lankan mobile (decision 084). The shared generator's `074` numbers are valid only
 * as `0740` to `0744`, so a customer's number never starts `074`.
 */
export function customerMobile(): string {
  for (;;) { const n = mobile(); if (!n.startsWith('074')) return n; }
}
export const customer = (): Customer => ({ name: person().name, mobile: customerMobile() });
/** `0771234567` as the national format the API answers (decision 084): `077 123 4567`. */
export const national = (local: string) => `${local.slice(0, 3)} ${local.slice(3, 6)} ${local.slice(6)}`;
/** `0771234567` in E.164, as stored. */
export const e164 = (local: string) => `+94${local.slice(1)}`;
/** Every common form a desk may type one number in. */
export const mobileForms = (local: string) => [local, national(local), e164(local), `+94 ${local.slice(1, 3)} ${local.slice(3, 6)} ${local.slice(6)}`, `0094${local.slice(1)}`];

// ---------------------------------------------------------------- the hold

export type HoldInput = {
  memberId?: unknown; serviceId?: unknown; variantId?: unknown; date?: unknown; time?: unknown; customer?: unknown; channel?: unknown;
};
/** A hold for the desk's phone call, unless overridden. */
export const holdInput = (memberId: string | null, serviceId: string, date: string, time: string, overrides: HoldInput = {}): HoldInput => ({
  memberId, serviceId, date, time, customer: customer(), channel: 'phone', ...overrides,
});

// ---------------------------------------------------------------- the API

export class Bookings {
  constructor(readonly identity: Identity) {}

  call(salon: Salon, method: string, route: string, tokens?: Tokens, data?: unknown, headers: Record<string, string> = {}): Promise<APIResponse> {
    if (!Object.keys(headers).length) return onSalonHost(this.identity, salon, method, route, tokens, data);
    return this.identity.api.send(method, route, {
      headers: { Host: salon.hostname, ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}),
        ...(data === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
      data: data === undefined ? undefined : JSON.stringify(data),
    });
  }

  /** `POST /bookings` with an `Idempotency-Key` (a new one unless given; null sends none). */
  placeRaw(salon: Salon, tokens: Tokens | undefined, input: unknown, key: string | null = randomUUID()) {
    return this.call(salon, 'POST', BOOKINGS, tokens, input, key === null ? {} : { 'Idempotency-Key': key });
  }
  async place(salon: Salon, tokens: Tokens, input: HoldInput, key?: string): Promise<Booking> {
    return json<Booking>(await this.placeRaw(salon, tokens, input, key), PENDING_F07_S01.schemas.booking, 201);
  }

  actionRaw(salon: Salon, tokens: Tokens | undefined, id: string, action: Action, body?: unknown) {
    return this.call(salon, 'POST', `${bookingPath(id)}/${action}`, tokens, body ?? (action === 'cancel' ? { reason: 'customer_asked' } : undefined));
  }
  async act(salon: Salon, tokens: Tokens, id: string, action: Exclude<Action, 'release'>, body?: unknown): Promise<Booking> {
    return json<Booking>(await this.actionRaw(salon, tokens, id, action, body), PENDING_F07_S01.schemas.booking);
  }
  async release(salon: Salon, tokens: Tokens, id: string) {
    const r = await this.actionRaw(salon, tokens, id, 'release');
    expect(r.status(), `release (body ${(await r.text()).slice(0, 200)})`).toBe(204);
  }
  confirm(salon: Salon, tokens: Tokens, id: string) { return this.act(salon, tokens, id, 'confirm'); }

  getRaw(salon: Salon, tokens: Tokens | undefined, id: string) { return this.call(salon, 'GET', bookingPath(id), tokens); }
  async get(salon: Salon, tokens: Tokens, id: string): Promise<BookingDetail> {
    return json<BookingDetail>(await this.getRaw(salon, tokens, id), PENDING_F07_S01.schemas.detail);
  }
  listRaw(salon: Salon, tokens: Tokens | undefined, query: string) { return this.call(salon, 'GET', `${BOOKINGS}?${query}`, tokens); }
  async list(salon: Salon, tokens: Tokens, query: string): Promise<BookingPage> {
    return json<BookingPage>(await this.listRaw(salon, tokens, query), PENDING_F07_S01.schemas.page);
  }
  /** Every booking of one local day (the API's largest page). */
  async day(salon: Salon, tokens: Tokens, date: string, extra = ''): Promise<Booking[]> {
    const page = await this.list(salon, tokens, `from=${date}&to=${date}&size=${LIMITS.pageMax}${extra}`);
    expect(page.total, `${date}: one page holds the day`).toBeLessThanOrEqual(LIMITS.pageMax);
    return page.items;
  }

  // S02
  availabilityRaw(salon: Salon, tokens: Tokens | undefined, query: Record<string, string | null | undefined>) {
    const q = Object.entries(query).filter(([, v]) => v !== undefined && v !== null).map(([k, v]) => `${k}=${encodeURIComponent(v as string)}`).join('&');
    return this.call(salon, 'GET', `${AVAILABILITY}?${q}`, tokens);
  }
  async availability(salon: Salon, tokens: Tokens, query: Record<string, string | null | undefined>): Promise<Availability> {
    return json<Availability>(await this.availabilityRaw(salon, tokens, query), PENDING_F07_S02.schemas.availability);
  }
  openDaysRaw(salon: Salon, tokens: Tokens | undefined, from: string, to: string) { return this.call(salon, 'GET', `${OPEN_DAYS}?from=${from}&to=${to}`, tokens); }
  async openDays(salon: Salon, tokens: Tokens, from: string, to: string): Promise<string[]> {
    return (await json<{ days: string[] }>(await this.openDaysRaw(salon, tokens, from, to), PENDING_F07_S02.schemas.openDays)).days;
  }
}

/** S02's answer (spec, "API and data changes"): `reason` is null when `times` is not empty. */
export type FreeTime = { time: string; startsAt: string; member: { id: string; name: string } };
export type Availability = { date: string; times: FreeTime[]; reason: string | null };

// ---------------------------------------------------------------- a salon to book in

/** "Cut and blow-dry": 45 minutes and a 15-minute buffer, Rs. 4,500 (as the S01 walk). */
export const CUT = { name: 'Cut and blow-dry', minutes: 45, buffer: 15, price: rupees(4500) } as const;
/** The day the salon is open, every day. */
export const OPENS = '09:00';
export const CLOSES = '19:00';

export type BookingSalon = Awaited<ReturnType<typeof bookingSalon>>;

/**
 * A salon the operator creates now, ready for the desk: the test clock at 08:00 in Colombo on `today` (a date after
 * every plan), opening hours 09:00 to 19:00 every day, saved booking rules (60-minute customer notice and a 60-day
 * horizon, which staff are not bound by, decision 108), one service (`CUT`) and `people` team members who perform it,
 * each on the salon's own hours. `rulesSaved: false` leaves the rules unsaved; `keepClock` makes a second salon in a
 * test without moving the clock.
 */
export async function bookingSalon(identity: Identity, operator: Operator, clock: TestClock,
  options: { people?: number; rulesSaved?: boolean; rules?: Partial<RulesInput>; startAt?: string; keepClock?: boolean } = {}) {
  // A second salon in the same test keeps the clock: setting it back would make the first salon's credentials not yet valid.
  if (!options.keepClock) {
    const day = afterNewestPlan().slice(0, 10);
    await clock.set(utcOf(day, options.startAt ?? '08:00'));
    expect(localDate(await identity.now(), COLOMBO), 'the clock is on the chosen local day').toBe(day);
  }
  const today = localDate(await identity.now(), COLOMBO);
  const made = await teamSalon(operator, identity, 25);
  const { salon, owner } = made;
  const hours = new Hours(identity);
  await hours.save(salon, owner.tokens, week(OPENS, CLOSES, []));
  const bookingRules = new BookingRules(identity);
  if (options.rulesSaved !== false) await bookingRules.save(salon, owner.tokens, rules({ minimumNoticeMinutes: 60, bookingHorizonDays: 60, ...options.rules }));
  const catalogue = new CatalogueS02(identity);
  const category = await catalogue.category(salon, owner.tokens, 'Cuts & styling');
  const service = await catalogue.create(salon, owner.tokens, serviceInput(category.id, {
    name: CUT.name, priceMinor: CUT.price, durationMinutes: CUT.minutes, bufferMinutes: CUT.buffer }));
  const team = new Team(identity);
  const members: TeamMember[] = [];
  for (let n = 0; n < (options.people ?? 2); n++) {
    members.push(await team.add(salon, owner.tokens, memberInput({ services: { categoryIds: [], serviceIds: [service.id] } })));
  }
  return { ...made, identity, today, hours, bookingRules, catalogue, category, service, team, members, bookings: new Bookings(identity) };
}

/** A service with Short, Medium and Long variants in the same salon, performed by the given members. */
export async function variantService(s: BookingSalon, performers: TeamMember[] = s.members): Promise<ServiceS02> {
  const service = await s.catalogue.createS02(s.salon, s.owner.tokens, variantInput(s.category.id, undefined, { name: `Colour by length ${randomUUID().slice(0, 4)}`, bufferMinutes: 10 }));
  for (const member of performers) await performs(s, member, [...(await currentServices(s, member)), service.id]);
  return service;
}

async function currentServices(s: BookingSalon, member: TeamMember) {
  return (await s.team.get(s.salon, s.owner.tokens, member.id)).services.serviceIds;
}
/** Saves which single services a member performs. */
export async function performs(s: BookingSalon, member: TeamMember, serviceIds: string[]): Promise<TeamMember> {
  const current = await s.team.get(s.salon, s.owner.tokens, member.id);
  return s.team.save(s.salon, s.owner.tokens, current, { services: { categoryIds: [], serviceIds } });
}

/**
 * Someone in `role` at a salon the operator made, signed in. A Professional may be linked to a team member
 * (`linkTo`) and given the IAM-08 grant to read the salon's calendar (`granted`).
 */
export async function staffAt(s: BookingSalon, role: Role, options: { linkTo?: TeamMember; granted?: boolean } = {}): Promise<SignedStaff> {
  let actor = await s.identity.extra(s.salon, role);
  if (options.linkTo) {
    const member = await s.team.get(s.salon, s.owner.tokens, options.linkTo.id);
    await s.team.link(s.salon, s.owner.tokens, member, actor.principal.id);
  }
  if (options.granted) {
    const row = (await s.identity.list(s.owner)).find(a => a.id === actor.principal.id) as Staff | undefined;
    expect(row, 'the Professional is on the staff list').toBeTruthy();
    const r = await s.identity.call(s.salon, 'PATCH', `/staff/${row!.id}`, { version: row!.version, professionalGrant: true }, s.owner.tokens);
    expect(r.status(), `grant (body ${(await r.text()).slice(0, 200)})`).toBe(200);
    actor = await s.identity.login(s.salon, actor.account);
  }
  return actor;
}

const current = new WeakMap<SignedStaff, SignedStaff>();
/**
 * Someone's credentials after the test clock moved: the last ones while their access has more than 30 seconds left by
 * the application's clock, else a new sign-in. A second sign-in in the same TOTP step would be a refused replay.
 */
export async function again(identity: Identity, who: SignedStaff): Promise<SignedStaff> {
  const last = current.get(who) ?? who;
  if (Date.parse(await identity.now()) < Date.parse(last.tokens.accessExpiresAt) - 30_000) return last;
  const fresh = await identity.login(who.salon, who.account);
  current.set(who, fresh);
  return fresh;
}

// ---------------------------------------------------------------- answers

/** A 400 bad_request whose every field error is on `field` (a dotted path may end in it). */
export async function refusedOnField(response: APIResponse, field: string, label: string) {
  const { problem, answer } = await refused(response, 400, 'bad_request', label);
  const fields = fieldsOf(problem as { errors?: { field?: string }[] });
  expect(fields.length, `${label}: names the field (${answer.body.slice(0, 300)})`).toBeGreaterThan(0);
  for (const f of fields) expect(f, `${label}: the error is on ${field}`).toMatch(new RegExp(`^${field.replace(/\./g, '\\.')}($|[.\\[])`));
}

/** A 409 with `code` and a `detail` saying which rule refused (decision 109 f). */
export async function refusedWithDetail(response: APIResponse, code: string, label: string, detail?: RegExp) {
  const { problem, answer } = await refused(response, 409, code, label);
  const said = (problem as { detail?: string }).detail ?? '';
  expect(said.length, `${label}: a detail names the rule (${answer.body.slice(0, 300)})`).toBeGreaterThan(0);
  if (detail) expect(said, `${label}: the detail names the rule`).toMatch(detail);
  return said;
}

/** A problem body without its per-request fields, for "the same answer as an unknown id". */
export const comparable = (text: string) => text.replace(/"(traceId|instance)":"[^"]*"/g, '');

/** The booking as the desk sees it, without what is generated. */
export const comparableBooking = (b: Booking) => ({
  state: b.state, startsAt: b.startsAt, endsAt: b.endsAt, member: b.member, service: b.service, customer: b.customer,
  channel: b.channel, priceMinor: b.priceMinor, currency: b.currency,
});

// ---------------------------------------------------------------- the database (owner role, read-only)

const T = PENDING_F07_S01.tables;

/** A digest of every booking and history row of a salon, to prove a refused call changed nothing. */
export function bookingSnapshot(salonId: string): string {
  return [T.booking, T.history].map(t => identitySql(
    `SELECT count(*) || ':' || coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text)), 'none') FROM ${t} x WHERE tenant_id = ${sqlLiteral(salonId)}`)).join(',');
}
export const bookingRows = (salonId: string) => Number(identitySql(`SELECT count(*) FROM ${T.booking} WHERE tenant_id = ${sqlLiteral(salonId)}`));

export type StoredBooking = {
  id: string; state: string; reference: string; member_id: string; service_id: string; service_terms_id: string; variant_id: string | null;
  rules_version_id: string; segment_kinds: string[]; segment_minutes: number[]; occupied: string; price_minor: number; currency: string;
  customer_name: string; customer_mobile: string; channel: string; hold_expires_at: string | null; branch_id: string; created_at: string;
};
export function storedBooking(salonId: string, id: string): StoredBooking {
  const row = identitySql(`SELECT row_to_json(x)::text FROM ${T.booking} x WHERE tenant_id = ${sqlLiteral(salonId)} AND id = ${sqlLiteral(id)}`);
  expect(row, `the stored booking ${id}`).not.toBe('');
  return JSON.parse(row) as StoredBooking;
}

export type StoredChange = { position: number; from_state: string | null; to_state: string; changed_at: string; actor_kind: string; actor_id: string | null; reason: string | null; note: string | null };
export function storedHistory(salonId: string, bookingId: string): StoredChange[] {
  const out = identitySql(`SELECT row_to_json(x)::text FROM (SELECT position, from_state, to_state, changed_at, actor_kind, actor_id, reason, note
    FROM ${T.history} WHERE tenant_id = ${sqlLiteral(salonId)} AND booking_id = ${sqlLiteral(bookingId)} ORDER BY position) x`);
  return out ? out.split('\n').map(l => JSON.parse(l) as StoredChange) : [];
}

/** Live rows of one professional (the exclusion constraint's states), as the owning role sees them. */
export function liveRowsOf(salonId: string, memberId: string): { id: string; state: string; starts_at: string }[] {
  const out = identitySql(`SELECT row_to_json(x)::text FROM (SELECT id, state, starts_at FROM ${T.booking} WHERE tenant_id = ${sqlLiteral(salonId)}
    AND member_id = ${sqlLiteral(memberId)} AND state IN ('held','pending_payment','confirmed','arrived','in_service','completed')) x`);
  return out ? out.split('\n').map(l => JSON.parse(l)) : [];
}

export type BookingAudit = { action: string; actorKind: string; actorId: string; subjectId: string; at: string; details: string };
/** The salon's audit entries about a booking, oldest first. */
export function bookingAudit(salonId: string, bookingId: string): BookingAudit[] {
  const out = identitySql(`SELECT json_build_object('action', action, 'actorKind', actor_kind, 'actorId', actor_id, 'subjectId', subject_id,
      'at', created_at, 'details', row_to_json(a)::text)
    FROM audit_entry a WHERE tenant_id = ${sqlLiteral(salonId)} AND subject_id = ${sqlLiteral(bookingId)} ORDER BY created_at, id`);
  return out ? out.split('\n').map(line => JSON.parse(line) as BookingAudit) : [];
}

/** The outbox messages of a type about one booking (ids only, AC-8). */
export function outboxAbout(salonId: string, bookingId: string): { kind: string; eventType: string; status: string; payload: string }[] {
  const out = identitySql(`SELECT json_build_object('kind', kind, 'eventType', event_type, 'status', status, 'payload', payload::text)
    FROM outbox_message WHERE tenant_id = ${sqlLiteral(salonId)} AND payload::text LIKE ${sqlLiteral(`%${bookingId}%`)} ORDER BY created_at`);
  return out ? out.split('\n').map(line => JSON.parse(line)) : [];
}

/** The one service a test made, as a plain `Service`. */
export type { Service, ServiceS02, TeamMember, Catalogue };
