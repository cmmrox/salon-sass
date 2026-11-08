/**
 * F06-S03 leave and blocked-time fixtures: the endpoints, builders, how leave's days are written, the
 * words the tabs and the card show, a direct arrangement for the two limits, and read-only database
 * observations of the stage's tables.
 *
 * Routes, bodies, codes and limits come from the F06-S03 spec (acceptance criteria, "API and data changes",
 * "UI and UX") and decision 104: a leave's type and note reach nobody without `team.manage`, and the audit
 * trail records neither.
 *
 * Every name the build gives is in `PENDING_F06_S03`, guessed from the F06-S02 handoff's pattern and unconfirmed
 * until this stage's handoff; nothing else in the F06-S03 tests spells one. Also to confirm then, as they are
 * not names:
 * - what a support session is answered on the two `affected` reads (spec, API table: it lacks `team.manage`
 *   and `team.block`; AC-10 gives `support_read_only` only for change endpoints), which the cases hold to any 403;
 * - that `rowCopies` can arrange a limit: a row copied with a new id and day breaks no constraint of the tables.
 */
import { randomUUID } from 'node:crypto';

import type { APIResponse, Locator, Page } from '@playwright/test';

import { refused } from './booking-rules';
import { expect, json, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { afterNewestPlan, fieldsOf, sqlLiteral, type Operator, type TestClock } from './operator';
import { HOURS_TABLES, localDate, plusDays } from './salon-hours';
import { PENDING_F06_S01, S01_TEAM_TABLES, Team, teamSalon, type TeamMember } from './team';
import { PENDING_F06_S02, S02_SCHEDULE_TABLES, calendarDay, schedulePath, shownTime, type MemberSchedule } from './team-schedule';
import type { Identity } from './identity';
import type { Salon } from './tenants';

/** Names the spec leaves to the build, guessed until the handoff. */
export const PENDING_F06_S03 = {
  /** Contract schemas (docs/api/openapi.json, components.schemas). */
  schemas: { schedule: PENDING_F06_S02.schemas.schedule, addedLeave: 'AddedTeamMemberLeave', addedBlock: 'AddedTeamMemberBlock', affected: 'TeamAffectedBookings' },
  /** The tables and columns a limit's direct arrangement writes. */
  tables: { leave: 'staff_member_leave', block: 'staff_member_block' },
  columns: { firstDay: 'first_day', lastDay: 'last_day', blockDate: 'block_date' },
  /** Audit actions, as F06-S02's `staff.team_member_date_change_added`. */
  audit: {
    leaveAdded: 'staff.team_member_leave_added', leaveRemoved: 'staff.team_member_leave_removed',
    blockAdded: 'staff.team_member_block_added', blockRemoved: 'staff.team_member_block_removed',
  },
  /** The browser's calls for the two tabs, for holding, failing or adding to them. */
  bff: {
    leaveAdd: (url: URL) => url.pathname.startsWith('/identity-web/') && /\/team-leave-add$/.test(url.pathname),
    blockAdd: (url: URL) => url.pathname.startsWith('/identity-web/') && /\/team-block-add$/.test(url.pathname),
    leaveAffected: (url: URL) => url.pathname.startsWith('/identity-web/') && /\/team-leave-affected$/.test(url.pathname),
    blockAffected: (url: URL) => url.pathname.startsWith('/identity-web/') && /\/team-block-affected$/.test(url.pathname),
  },
  /** Test ids, following F06-S02's `change-*`. A control the spec names is found by its role and name instead. */
  web: {
    cardLeave: 'team-card-leave',
    leaveBanner: 'leave-banner', leaveEmpty: 'leave-empty', leaveRow: 'leave-row', leaveRowDays: 'leave-row-days', leaveRowType: 'leave-row-type', leaveRowNote: 'leave-row-note', leaveRowResolve: 'leave-row-resolve',
    leaveForm: 'leave-form', leaveFirst: 'leave-first', leaveFirstCalendar: 'leave-first-calendar', leaveLast: 'leave-last', leaveLastCalendar: 'leave-last-calendar',
    leaveType: 'leave-type', leaveNote: 'leave-note', leaveProblem: 'leave-problem',
    leaveRemoveDialog: 'leave-remove-dialog', leaveRemoveConfirm: 'leave-remove-confirm', leaveRemoveCancel: 'leave-remove-cancel',
    blockBanner: 'block-banner', blockEmpty: 'block-empty', blockRow: 'block-row',
    blockForm: 'block-form', blockDate: 'block-date', blockCalendar: 'block-calendar', blockStarts: 'block-starts', blockFinishes: 'block-finishes', blockLabel: 'block-label', blockProblem: 'block-problem',
  },
  /** Control names from the spec and the prototype (`schLv`, `schBl`). */
  names: {
    leaveTab: 'Leave', blockTab: 'Blocked time', addLeave: '+ Add leave', addBlock: '+ Block time', saveLeave: 'Add leave', saveBlock: 'Block time', remove: /^Remove/,
  },
} as const;

export const leavePath = (memberId: string) => `${schedulePath(memberId)}/leave`;
export const oneLeavePath = (memberId: string, leaveId: string) => `${leavePath(memberId)}/${leaveId}`;
export const leaveAffectedPath = (memberId: string, firstDay: string, lastDay: string) => `${leavePath(memberId)}/affected?firstDay=${firstDay}&lastDay=${lastDay}`;
export const blocksPath = (memberId: string) => `${schedulePath(memberId)}/blocked-time`;
export const oneBlockPath = (memberId: string, blockId: string) => `${blocksPath(memberId)}/${blockId}`;
export const blockAffectedPath = (memberId: string, date: string, starts: string, finishes: string) => `${blocksPath(memberId)}/affected?date=${date}&starts=${starts}&finishes=${finishes}`;

/** The spec's limits (AC-1, AC-2, AC-4, AC-5). */
export const ABSENCE_LIMITS = { noteLength: 60, leaves: 100, blocks: 200, label: [2, 40] } as const;

// ---- shapes, from the spec's "API and data changes" until the contract exists ----

export const LEAVE_TYPES = ['annual', 'sick', 'personal', 'other'] as const;
export type LeaveType = typeof LEAVE_TYPES[number];
export type Leave = { id: string; firstDay: string; lastDay: string; type?: LeaveType; note?: string | null; bookingsToResolve: number };
export type Block = { id: string; date: string; starts: string; finishes: string; label: string; bookingsToResolve: number };
export type NextLeave = { firstDay: string; lastDay: string; type?: LeaveType } | null;
export type AbsenceSchedule = MemberSchedule & { leave: Leave[]; blockedTime: Block[] };
export type MemberWithLeave = TeamMember & { nextLeave: NextLeave };
export type LeaveInput = { firstDay?: unknown; lastDay?: unknown; type?: unknown; note?: unknown };
export type BlockInput = { date?: unknown; starts?: unknown; finishes?: unknown; label?: unknown };

/** Exactly what a caller without `team.manage` may receive of a leave (decision 104). */
export const LEAVE_KEYS_WITHOUT_MANAGE = ['bookingsToResolve', 'firstDay', 'id', 'lastDay'];
export const NEXT_LEAVE_KEYS_WITHOUT_MANAGE = ['firstDay', 'lastDay'];

export const leaveInput = (firstDay: string, overrides: LeaveInput = {}): LeaveInput => ({ firstDay, lastDay: firstDay, type: 'annual', note: null, ...overrides });
export const blockInput = (date: string, overrides: BlockInput = {}): BlockInput => ({ date, starts: '10:00', finishes: '11:00', label: 'Training', ...overrides });

/** What a leave holds, leaving out its id and its note. */
export const comparableLeave = (l: { firstDay?: unknown; lastDay?: unknown; type?: unknown }) => ({ firstDay: l.firstDay, lastDay: l.lastDay, type: l.type });
export const comparableBlock = (b: { date?: unknown; starts?: unknown; finishes?: unknown; label?: unknown }) => ({ date: b.date, starts: b.starts, finishes: b.finishes, label: b.label });

// ---- the endpoints ----

export class Absences {
  constructor(readonly team: Team) {}

  async read(salon: Salon, tokens: Tokens, memberId: string): Promise<AbsenceSchedule> {
    return json<AbsenceSchedule>(await this.team.call(salon, 'GET', schedulePath(memberId), tokens), PENDING_F06_S03.schemas.schedule);
  }
  addLeaveRaw(salon: Salon, tokens: Tokens | undefined, memberId: string, input: unknown) { return this.team.call(salon, 'POST', leavePath(memberId), tokens, input); }
  async addLeave(salon: Salon, tokens: Tokens, memberId: string, input: LeaveInput) {
    return json<{ leave: Leave; affected: unknown[] }>(await this.addLeaveRaw(salon, tokens, memberId, input), PENDING_F06_S03.schemas.addedLeave, 201);
  }
  removeLeaveRaw(salon: Salon, tokens: Tokens | undefined, memberId: string, leaveId: string) { return this.team.call(salon, 'DELETE', oneLeavePath(memberId, leaveId), tokens); }
  async removeLeave(salon: Salon, tokens: Tokens, memberId: string, leaveId: string) {
    const r = await this.removeLeaveRaw(salon, tokens, memberId, leaveId);
    expect(r.status(), `remove a leave (body ${(await r.text()).slice(0, 200)})`).toBe(204);
  }
  addBlockRaw(salon: Salon, tokens: Tokens | undefined, memberId: string, input: unknown) { return this.team.call(salon, 'POST', blocksPath(memberId), tokens, input); }
  async addBlock(salon: Salon, tokens: Tokens, memberId: string, input: BlockInput) {
    return json<{ block: Block; affected: unknown[] }>(await this.addBlockRaw(salon, tokens, memberId, input), PENDING_F06_S03.schemas.addedBlock, 201);
  }
  removeBlockRaw(salon: Salon, tokens: Tokens | undefined, memberId: string, blockId: string) { return this.team.call(salon, 'DELETE', oneBlockPath(memberId, blockId), tokens); }
  async removeBlock(salon: Salon, tokens: Tokens, memberId: string, blockId: string) {
    const r = await this.removeBlockRaw(salon, tokens, memberId, blockId);
    expect(r.status(), `remove a block (body ${(await r.text()).slice(0, 200)})`).toBe(204);
  }
}

/**
 * Every endpoint of this stage, with a request that would succeed for the leave and block given: a refusal is
 * then about who asks. `free` is a date holding neither.
 */
export function absenceRoutes(memberId: string, leave: Leave, block: Block, free: string) {
  const leaveRoutes = [
    { label: 'add leave', method: 'POST', path: leavePath(memberId), data: leaveInput(free) as unknown },
    { label: 'remove leave', method: 'DELETE', path: oneLeavePath(memberId, leave.id), data: undefined as unknown },
    { label: 'read what leave would affect', method: 'GET', path: leaveAffectedPath(memberId, free, free), data: undefined as unknown },
  ];
  const blockRoutes = [
    { label: 'add a block', method: 'POST', path: blocksPath(memberId), data: blockInput(free) as unknown },
    { label: 'remove a block', method: 'DELETE', path: oneBlockPath(memberId, block.id), data: undefined as unknown },
    { label: 'read what a block would affect', method: 'GET', path: blockAffectedPath(memberId, free, '10:00', '11:00'), data: undefined as unknown },
  ];
  return { leaveRoutes, blockRoutes, all: [...leaveRoutes, ...blockRoutes] };
}

// ---- answers ----

/** A 400 bad_request whose every field error matches `field`. */
export async function refusedOn(response: APIResponse, field: RegExp, label: string) {
  const { problem, answer } = await refused(response, 400, 'bad_request', label);
  const fields = fieldsOf(problem as { errors?: { field?: string }[] });
  expect(fields.length, `${label}: names the field (${answer.body.slice(0, 300)})`).toBeGreaterThan(0);
  for (const f of fields) expect(f, `${label}: the error is on the field`).toMatch(field);
}

// ---- arranging ----

const COLOMBO = 'Asia/Colombo';

/** A salon with a person, the clock standing at noon on a date after every plan. */
export async function absenceSalon(identity: Identity, operator: Operator, clock: TestClock) {
  const at = afterNewestPlan();
  await clock.set(at);
  const today = localDate(at, COLOMBO);
  const team = new Team(identity);
  const made = await teamSalon(operator, identity);
  const person = await team.add(made.salon, made.owner.tokens);
  return { ...made, today, team, absences: new Absences(team), person };
}

export const salonDate = async (identity: Identity) => localDate(await identity.now(), COLOMBO);

/**
 * Copies of one stored row on other dates, in one statement: the 200 rows of a limit are arrangement, not what
 * is under test, and the row that makes the limit is still sent through the API.
 */
export function rowCopies(salonId: string, table: string, rowId: string, values: Record<string, string>[]) {
  const rows = values.map(v => `(${sqlLiteral(randomUUID())}, ${sqlLiteral(JSON.stringify(v))}::jsonb)`).join(', ');
  identitySql(`INSERT INTO ${table} SELECT (jsonb_populate_record(x, jsonb_build_object('id', v.new_id) || v.vals)).*
    FROM ${table} x, (VALUES ${rows}) AS v(new_id, vals) WHERE x.tenant_id = ${sqlLiteral(salonId)} AND x.id = ${sqlLiteral(rowId)}`);
}
export const leaveCopies = (salonId: string, leaveId: string, days: string[]) => rowCopies(salonId, PENDING_F06_S03.tables.leave, leaveId,
  days.map(day => ({ [PENDING_F06_S03.columns.firstDay]: day, [PENDING_F06_S03.columns.lastDay]: day })));
export const blockCopies = (salonId: string, blockId: string, days: string[]) => rowCopies(salonId, PENDING_F06_S03.tables.block, blockId,
  days.map(day => ({ [PENDING_F06_S03.columns.blockDate]: day })));
export const nextDays = (from: string, count: number) => Array.from({ length: count }, (_, n) => plusDays(from, n));

// ---- the words the tabs and the card show ----

/** A date as the spec writes it: "Thu 12 Nov". */
export function dayWords(date: string): string {
  const d = new Date(`${date}T00:00:00Z`);
  const part = (o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat('en-GB', { ...o, timeZone: 'UTC' }).format(d);
  return `${part({ weekday: 'short' })} ${part({ day: 'numeric' })} ${part({ month: 'short' })}`;
}
/** Leave's days: "Thu 12 Nov to Sat 14 Nov", or one day alone. */
export const daysWords = (firstDay: string, lastDay: string) => (firstDay === lastDay ? dayWords(firstDay) : `${dayWords(firstDay)} to ${dayWords(lastDay)}`);

export const TYPE_WORDS: Record<LeaveType, string> = { annual: 'Annual leave', sick: 'Sick leave', personal: 'Personal', other: 'Other' };

export const ABSENCE_TEXT = {
  leaveBanner: 'Customers can’t book this person on leave days. Existing bookings are kept and flagged, never cancelled for you. For part of a day, use Blocked time.',
  blockBanner: 'Blocked time is part of a day when this person can’t be booked, like training or an errand. It shows on the calendar with its label.',
  lastBeforeFirst: 'The last day can’t be before the first.',
  leaveAdded: 'Leave added.',
  leaveAddedFlagged: 'Leave added. Existing bookings were kept and flagged.',
  leaveOverlap: 'This overlaps leave already planned.',
  noLeave: 'No leave planned.',
  removeTitle: 'Remove this leave?',
  removeBody: 'They become bookable on these days again.',
  toResolve: (n: number) => (n === 1 ? '1 booking to resolve' : `${n} bookings to resolve`),
  endAfterStart: 'The end must be after the start.',
  addLabel: 'Add a label, like Training.',
  blocked: 'Time blocked.',
  blockedFlagged: (n: number) => (n === 1 ? 'Time blocked. The existing booking was kept and flagged.' : `Time blocked. ${n} existing bookings were kept and flagged.`),
  blockOverlap: 'This overlaps time already blocked.',
  noBlocks: 'No blocked time.',
  blockRemoved: 'Blocked time removed.',
  affected: (n: number) => (n === 1 ? '1 existing booking in this time. It is kept and flagged for you. Nothing is cancelled.' : `${n} existing bookings in this time. They are kept and flagged for you. Nothing is cancelled.`),
  badge: (type: LeaveType, firstDay: string, lastDay: string) => `${TYPE_WORDS[type]}, ${daysWords(firstDay, lastDay)}`,
  badgeWithoutType: (firstDay: string, lastDay: string) => `On leave, ${daysWords(firstDay, lastDay)}`,
  blockTimes: (starts: string, finishes: string) => `${shownTime(starts)} – ${shownTime(finishes)}`,
};

/** Chooses a day in a Popover Calendar, moving on to its month when it is not this one. */
export async function pickDay(page: Page, opener: Locator, calendarId: string, date: string) {
  await opener.click();
  const calendar = page.getByTestId(calendarId);
  for (let n = 0; n < 13 && !(await calendarDay(calendar, date).isVisible()); n++) await calendar.getByRole('button', { name: /next month/i }).click();
  await calendarDay(calendar, date).click();
}

// ---- database observations (owner role unless a tenant is given) ----

/** Every table this stage added: the `staff_` tables with a `tenant_id` that F04, F06-S01 and F06-S02 did not add. */
export function absenceTables(): string[] {
  const known = [...S01_TEAM_TABLES, ...HOURS_TABLES, ...S02_SCHEDULE_TABLES].map(sqlLiteral).join(', ');
  const out = identitySql(`SELECT table_name FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'tenant_id'
    AND table_name LIKE 'staff\\_%' AND table_name NOT IN (${known}) ORDER BY 1`);
  const tables = out.split('\n').filter(Boolean);
  expect(tables.length, 'the tables of leave and of blocked time').toBeGreaterThanOrEqual(2);
  return tables;
}

/** A digest of a salon's leave and blocks and its members, to prove a refused call changed nothing. */
export function absenceSnapshot(salonId: string): string {
  return [...absenceTables(), PENDING_F06_S01.memberTable].map(t => identitySql(
    `SELECT coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text)), 'none') FROM ${t} x WHERE tenant_id = ${sqlLiteral(salonId)}`)).join(',');
}

/** How many of a salon's stored audit entries carry the words, ignoring case, whoever may read them. */
export const auditEntriesLike = (salonId: string, words: string) => Number(identitySql(
  `SELECT count(*) FROM audit_entry a WHERE tenant_id = ${sqlLiteral(salonId)} AND row_to_json(a)::text ILIKE ${sqlLiteral(`%${words}%`)}`));
