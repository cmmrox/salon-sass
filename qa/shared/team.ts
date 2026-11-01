/**
 * F06-S01 team fixtures: the team endpoints, input builders, a salon with a chosen number of staff
 * seats, how seats are counted row by row, and read-only database observations of the stage's tables.
 *
 * Routes, bodies, codes and limits come from the F06-S01 spec (acceptance criteria, "API and data
 * changes", "Design notes") and decisions 099 and 100. Written before the build; every name only
 * the build gives (a table, a contract schema, the web's photo route, a BFF route) is in
 * `PENDING_F06_S01`, confirmed against the F06-S01 handoff at the dry run.
 *
 * A salon keeps its team between runs, and a fixed salon's staff are reset around every test, which
 * would leave a link to an account that no longer exists. So a test that changes a team uses a salon
 * the operator creates now (`teamSalon`), never alpha, beta or gamma.
 */
import { randomBytes } from 'node:crypto';

import { request, type APIRequestContext, type APIResponse } from '@playwright/test';

import { refused } from './booking-rules';
import { apiUrl } from './env';
import { expect, json, syntheticAccount, type Identity, type Role, type SignedStaff, type Staff, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { fieldsOf, onSalonHost, ownerSession, sqlLiteral, type Entitlements, type Operator, type SalonInput } from './operator';
import type { FileUpload } from './salon-brand';
import type { Salon } from './tenants';

/** Names the spec leaves to the build. Nothing else in the F06 tests spells these out. */
export const PENDING_F06_S01 = {
  /** The table of team members (the other team tables are found by `tableMatch`). */
  memberTable: 'team_member',
  /** Every table this stage adds (members, whole categories, single services, photos) has this in its name. */
  tableMatch: 'member',
  /** The count function the spec names; the platform role alone may execute it. */
  countFunction: 'staff_active_unlinked_member_count',
  /** Contract schemas (docs/api/openapi.json, components.schemas). */
  schemas: {
    member: 'TeamMember',
    memberList: 'TeamMemberList',
    publicList: 'PublicTeamList',
    accountList: 'TeamSignInAccountList',
  },
  /** The multipart field of a photo upload (spec, API table). */
  photoField: 'file',
  /** The web's photo route on a salon hostname, as for a service photo. */
  webPhotoPath: (photoId: string) => `/team-photos/${photoId}`,
  /** The browser's data calls for the Team page, for holding or failing them. */
  bffMatch: (url: URL) => url.pathname.startsWith('/identity-web/') && url.pathname.includes('/team-'),
} as const;

export const TEAM = '/api/v1/team';
export const MEMBERS = `${TEAM}/members`;
export const PUBLIC_TEAM = `${TEAM}/public`;
export const SIGN_IN_ACCOUNTS = `${TEAM}/sign-in-accounts`;
export const memberPath = (id: string) => `${MEMBERS}/${id}`;
export const memberPhotoPath = (id: string) => `${memberPath(id)}/photo`;
export const photoPath = (photoId: string) => `${TEAM}/photos/${photoId}`;
export const ENTITLEMENTS = '/api/v1/salon/entitlements';

/** The spec's limits (AC-1, AC-9). */
export const LIMITS = {
  name: [2, 40], title: 40, highlights: 60, bio: 140, members: 200, photoBytes: 5 * 1024 * 1024, photoMinSide: 320, photoMaxSide: 800,
} as const;

// ---- shapes, from the spec's "API and data changes" until the contract exists ----

export type MemberServices = { categoryIds: string[]; serviceIds: string[] };
export type TeamMember = {
  id: string; name: string; title: string | null; highlights: string | null; bio: string | null; shownOnline: boolean;
  photoId: string | null; photoUrl: string | null; services: MemberServices; signInAccountId?: string | null; signInLabel?: string | null; version: number;
};
export type MemberInput = {
  name?: unknown; title?: unknown; highlights?: unknown; bio?: unknown; shownOnline?: unknown;
  services?: { categoryIds?: unknown; serviceIds?: unknown }; signInAccountId?: unknown;
};
export type PublicMember = {
  id: string; name: string; title: string | null; highlights: string | null; bio: string | null; photoUrl: string | null; serviceIds: string[];
};
export type SignInAccount = { id: string; label: string; role: string; memberId: string | null };

/** A short random marker, for names that must be findable afterwards. */
export const mark = () => randomBytes(3).toString('hex');

/** A person with no services, shown online and no sign-in, unless overridden. */
export const memberInput = (overrides: MemberInput = {}): MemberInput => ({
  name: `QA Person ${mark()}`, title: 'Stylist', highlights: 'Balayage', bio: 'A person QA added.', shownOnline: true,
  services: { categoryIds: [], serviceIds: [] }, signInAccountId: null, ...overrides,
});

/** What a client sends back to save a member: its current values with changes applied. */
export const updateOf = (member: TeamMember, change: MemberInput = {}, signIn: string | null = member.signInAccountId ?? null) => ({
  name: member.name, title: member.title, highlights: member.highlights, bio: member.bio, shownOnline: member.shownOnline,
  services: member.services, signInAccountId: signIn, ...change, version: member.version,
});

export class Team {
  private uploads: APIRequestContext | null = null;
  constructor(readonly identity: Identity) {}

  call(salon: Salon, method: string, route: string, tokens?: Tokens, data?: unknown): Promise<APIResponse> {
    return onSalonHost(this.identity, salon, method, route, tokens, data);
  }

  // members
  listRaw(salon: Salon, tokens?: Tokens) { return this.call(salon, 'GET', MEMBERS, tokens); }
  async list(salon: Salon, tokens: Tokens): Promise<TeamMember[]> {
    return (await json<{ items: TeamMember[] }>(await this.listRaw(salon, tokens), PENDING_F06_S01.schemas.memberList)).items;
  }
  getRaw(salon: Salon, tokens: Tokens | undefined, id: string) { return this.call(salon, 'GET', memberPath(id), tokens); }
  async get(salon: Salon, tokens: Tokens, id: string): Promise<TeamMember> {
    return json<TeamMember>(await this.getRaw(salon, tokens, id), PENDING_F06_S01.schemas.member);
  }
  addRaw(salon: Salon, tokens: Tokens | undefined, input: unknown) { return this.call(salon, 'POST', MEMBERS, tokens, input); }
  async add(salon: Salon, tokens: Tokens, input: MemberInput = memberInput()): Promise<TeamMember> {
    return json<TeamMember>(await this.addRaw(salon, tokens, input), PENDING_F06_S01.schemas.member, 201);
  }
  saveRaw(salon: Salon, tokens: Tokens | undefined, id: string, body: unknown) { return this.call(salon, 'PUT', memberPath(id), tokens, body); }
  async save(salon: Salon, tokens: Tokens, member: TeamMember, change: MemberInput = {}): Promise<TeamMember> {
    return json<TeamMember>(await this.saveRaw(salon, tokens, member.id, updateOf(member, change)), PENDING_F06_S01.schemas.member);
  }
  /** Links a member to an account, or unlinks them with null. */
  async link(salon: Salon, tokens: Tokens, member: TeamMember, accountId: string | null): Promise<TeamMember> {
    return json<TeamMember>(await this.saveRaw(salon, tokens, member.id, updateOf(member, {}, accountId)), PENDING_F06_S01.schemas.member);
  }
  removeRaw(salon: Salon, tokens: Tokens | undefined, id: string, version: number) {
    return this.call(salon, 'DELETE', `${memberPath(id)}?version=${version}`, tokens);
  }
  async remove(salon: Salon, tokens: Tokens, member: { id: string; version: number; name?: string }) {
    const r = await this.removeRaw(salon, tokens, member.id, member.version);
    expect(r.status(), `remove ${member.name ?? member.id} (body ${(await r.text()).slice(0, 200)})`).toBe(204);
  }

  // photos
  /** A multipart photo upload on the salon's hostname; `file` null sends none, to test the refusal. */
  async uploadRaw(salon: Salon, tokens: Tokens | undefined, memberId: string, file: FileUpload | null): Promise<APIResponse> {
    this.uploads ??= await request.newContext({ baseURL: apiUrl, timeout: 60_000, maxRedirects: 0 });
    const multipart: Record<string, FileUpload> = {};
    if (file) multipart[PENDING_F06_S01.photoField] = file;
    return this.uploads.fetch(memberPhotoPath(memberId), { method: 'PUT', multipart,
      headers: { Host: salon.hostname, ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}) } });
  }
  async upload(salon: Salon, tokens: Tokens, memberId: string, file: FileUpload): Promise<TeamMember> {
    const member = await json<TeamMember>(await this.uploadRaw(salon, tokens, memberId, file), PENDING_F06_S01.schemas.member);
    expect(member.photoId, 'the member now has a photo').toBeTruthy();
    expect(member.photoUrl, 'and its URL').toBeTruthy();
    return member;
  }
  removePhotoRaw(salon: Salon, tokens: Tokens | undefined, memberId: string) { return this.call(salon, 'DELETE', memberPhotoPath(memberId), tokens); }
  /** A photo read on a hostname with no credential (photos are public on their own salon's host). */
  photo(host: string, photoId: string): Promise<APIResponse> { return this.identity.api.getOn(host, photoPath(photoId)); }

  // the public list
  publicRaw(host: string): Promise<APIResponse> { return this.identity.api.getOn(host, PUBLIC_TEAM); }
  async publicList(salon: Salon): Promise<PublicMember[]> {
    return (await json<{ items: PublicMember[] }>(await this.publicRaw(salon.hostname), PENDING_F06_S01.schemas.publicList)).items;
  }

  // sign-in accounts, through identity's own endpoints
  accountsRaw(salon: Salon, tokens?: Tokens) { return this.call(salon, 'GET', SIGN_IN_ACCOUNTS, tokens); }
  async accounts(salon: Salon, tokens: Tokens): Promise<SignInAccount[]> {
    return (await json<{ items: SignInAccount[] }>(await this.accountsRaw(salon, tokens), PENDING_F06_S01.schemas.accountList)).items;
  }
  createAccountRaw(salon: Salon, tokens: Tokens, role: Role = 'RECEPTIONIST') { return this.identity.call(salon, 'POST', '/staff', syntheticAccount(role), tokens); }
  /** An account created now always has an email, `syntheticAccount`'s. */
  async createAccount(salon: Salon, tokens: Tokens, role: Role = 'RECEPTIONIST'): Promise<Staff & { email: string }> {
    return json<Staff & { email: string }>(await this.createAccountRaw(salon, tokens, role), 'StaffView', 201);
  }
  removeAccountRaw(salon: Salon, tokens: Tokens, id: string) { return this.identity.call(salon, 'DELETE', `/staff/${id}`, undefined, tokens); }
  async removeAccount(salon: Salon, tokens: Tokens, id: string) {
    const r = await this.removeAccountRaw(salon, tokens, id);
    expect(r.status(), `remove account (body ${(await r.text()).slice(0, 200)})`).toBe(204);
  }

  // seats
  async entitlements(salon: Salon, tokens: Tokens): Promise<Entitlements> {
    return json<Entitlements>(await this.call(salon, 'GET', ENTITLEMENTS, tokens), 'Entitlements');
  }
  async usage(salon: Salon, tokens: Tokens): Promise<number> { return (await this.entitlements(salon, tokens)).usage.staffSeats; }

  /**
   * The seat rule read row by row (AC-6): what the salon reports equals its active accounts plus its
   * active members with no linked account, and equals `expected`. Returns the rows it counted.
   */
  async expectSeats(owner: SignedStaff, expected: number, label: string) {
    const accounts = (await this.identity.list(owner)).filter(a => a.active);
    const members = await this.list(owner.salon, owner.tokens);
    const unlinked = members.filter(m => !m.signInAccountId);
    expect(accounts.length, `${label}: the Owner's account is counted, so there is something to count`).toBeGreaterThan(0);
    expect(await this.usage(owner.salon, owner.tokens), `${label}: seats used, with ${accounts.length} accounts and ${unlinked.length} unlinked members`).toBe(accounts.length + unlinked.length);
    expect(accounts.length + unlinked.length, `${label}: the seats expected`).toBe(expected);
    return { accounts, members, unlinked };
  }

  async dispose() { await this.uploads?.dispose(); }
}

/** A salon the operator creates now on a plan of `seats` staff seats, with its Owner signed in (MFA enrolled). */
export async function teamSalon(operator: Operator, identity: Identity, seats = 20, overrides: Partial<SalonInput> = {}) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin, { staffSeats: seats });
  const { input, created, salon } = await operator.salon(admin, plan.id, overrides);
  const owner: SignedStaff = await ownerSession(identity, salon, input);
  return { admin, plan, input, created, salon, owner };
}

// ---- answers ----

/** The seat refusal of AC-6: 409 `entitlement_exceeded` naming `staffSeats`. */
export async function noSeat(response: APIResponse, label: string) {
  const { problem } = await refused(response, 409, 'entitlement_exceeded', label);
  expect(fieldsOf(problem as { errors?: { field?: string }[] }), `${label}: names the limit`).toContain('staffSeats');
}

/** A 400 bad_request whose every field error is on `field` or inside it (`services.categoryIds`). */
export async function refusedOn(response: APIResponse, field: string, label: string) {
  const { problem, answer } = await refused(response, 400, 'bad_request', label);
  const fields = fieldsOf(problem as { errors?: { field?: string }[] });
  expect(fields.length, `${label}: names the field (${answer.body.slice(0, 300)})`).toBeGreaterThan(0);
  for (const f of fields) expect(f, `${label}: the error is on ${field}`).toMatch(new RegExp(`^${field}($|[.\\[])`));
}

/** Everything a person's sign-in or a member's concurrency token could be recognised by, for the public list. */
export const publicKeys = ['bio', 'highlights', 'id', 'name', 'photoUrl', 'serviceIds', 'title'];

// ---- database observations (owner role unless a tenant is given) ----

/** Every table this stage added: those with a `tenant_id` and `member` in their name. */
export function teamTables(): string[] {
  const out = identitySql(`SELECT table_name FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'tenant_id'
    AND table_name LIKE ${sqlLiteral(`%${PENDING_F06_S01.tableMatch}%`)} ORDER BY 1`);
  const tables = out.split('\n').filter(Boolean);
  expect(tables.length, 'the team tables (members, whole categories, single services, photos)').toBeGreaterThanOrEqual(4);
  return tables;
}

/** A digest of every team row of a salon, to prove a refused call changed nothing. */
export function teamSnapshot(salonId: string): string {
  return teamTables().map(t => identitySql(
    `SELECT coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text)), 'none') FROM ${t} x WHERE tenant_id = ${sqlLiteral(salonId)}`)).join(',');
}

/** How many rows of a salon each team table holds. */
export function teamRows(salonId: string): Record<string, number> {
  return Object.fromEntries(teamTables().map(t => [t, Number(identitySql(`SELECT count(*) FROM ${t} WHERE tenant_id = ${sqlLiteral(salonId)}`))]));
}

/** One member's stored row as text, also after removal: its id and name stay for past bookings. */
export const memberRow = (salonId: string, memberId: string) => identitySql(
  `SELECT row_to_json(x)::text FROM ${PENDING_F06_S01.memberTable} x WHERE tenant_id = ${sqlLiteral(salonId)} AND id = ${sqlLiteral(memberId)}`);

/** How many deadlocks the database has resolved so far: the proof that lock order held under a race. */
export const deadlocks = () => Number(identitySql('SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()'));

export type AuditRow = { action: string; actorId: string; subjectId: string; at: string; details: string };

/** The salon's audit entries about one subject from `after` on, oldest first. */
export function auditOf(salonId: string, subjectId: string, after: string): AuditRow[] {
  const out = identitySql(`SELECT json_build_object('action', action, 'actorId', actor_id, 'subjectId', subject_id, 'at', created_at, 'details', row_to_json(a)::text)
    FROM audit_entry a WHERE tenant_id = ${sqlLiteral(salonId)} AND subject_id = ${sqlLiteral(subjectId)}
      AND created_at >= ${sqlLiteral(after)}::timestamptz ORDER BY created_at, id`);
  return out ? out.split('\n').map(line => JSON.parse(line) as AuditRow) : [];
}
