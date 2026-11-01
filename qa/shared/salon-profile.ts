/**
 * F04-S01 salon profile fixtures: the four settings endpoints, the public salon read, draft
 * builders with values unique to one test, and read-only database observations of the profile
 * and branch tables.
 *
 * Routes, bodies, codes and shapes come from the F04-S01 spec ("API and data changes") and the
 * generated contract (docs/api/openapi.json). The tables are the ones the spec's design notes name
 * (`branding_branch` and the draft and published copies), observed only, never written.
 * The profile has no reset hook (the handoff names none), so every test reads the current draft
 * and works from its version, and values are unique to the test that saves them.
 */
import { randomBytes } from 'node:crypto';
import type { APIResponse } from '@playwright/test';

import type { components } from '@contracts/schema';

import { answerOf, expectProblem } from './answers';
import { expect, json, type Identity, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { onSalonHost, sqlLiteral } from './operator';
import type { Salon } from './tenants';

export const PROFILE_DRAFT = '/api/v1/salon/profile/draft';
export const PROFILE_PUBLICATION = '/api/v1/salon/profile/publication';
export const PUBLIC_SALON = '/api/v1/salon';

export type ProfileDraft = components['schemas']['SalonProfileDraft'];
export type SalonProfile = components['schemas']['SalonProfile'];
export type ProfileBranch = components['schemas']['SalonProfileBranch'];
export type ProfileInput = components['schemas']['SalonProfileInput'];
export type BranchInput = components['schemas']['SalonProfileBranchInput'];
export type PublicSalon = components['schemas']['Salon'];

/** The five tables this stage adds (spec, "Design notes" and "API and data changes"). */
export const PROFILE_TABLES = [
  'branding_branch', 'branding_profile_draft', 'branding_profile_published',
  'branding_branch_contact_draft', 'branding_branch_contact_published',
] as const;

/** The four settings endpoints, as method, path and the body each takes. */
export type Endpoint = { name: 'read' | 'save' | 'publish' | 'discard'; method: string; path: string };
export const ENDPOINTS: Endpoint[] = [
  { name: 'read', method: 'GET', path: PROFILE_DRAFT },
  { name: 'save', method: 'PUT', path: PROFILE_DRAFT },
  { name: 'publish', method: 'POST', path: PROFILE_PUBLICATION },
  { name: 'discard', method: 'DELETE', path: PROFILE_DRAFT },
];
export const WRITES = ENDPOINTS.filter(e => e.name !== 'read');

export const unique = (prefix = 'QA') => `${prefix} ${randomBytes(5).toString('hex')}`;
/** A Sri Lankan mobile number unique enough for one test, in the national form. */
export const mobile = () => `077 ${String(100 + (randomBytes(1)[0] % 900))} ${String(1000 + (randomBytes(2).readUInt16BE() % 9000))}`;

/** A complete profile input naming the salon's default branch, with values unique to this call. */
export function fullInput(branchId: string, overrides: { branch?: Partial<BranchInput>; description?: string | null; links?: ProfileInput['links'] } = {}): ProfileInput {
  const marker = randomBytes(4).toString('hex');
  return {
    description: overrides.description === undefined ? `QA description ${marker}` : overrides.description,
    links: overrides.links ?? { instagram: `https://instagram.com/qa${marker}`, facebook: `https://facebook.com/qa${marker}`, website: `https://qa${marker}.example.com` },
    branches: [{
      id: branchId,
      address: `No. ${marker}, QA Road, Colombo 07`,
      location: { latitude: 6.9061, longitude: 79.8611 },
      phone: '0112504417',
      whatsapp: mobile(),
      ...overrides.branch,
    }],
  };
}

/** The values a published or saved profile must hold for an input, phones aside (stored in E.164). */
export function expectedProfile(input: ProfileInput, phones: { phone: string | null; whatsapp: string | null }) {
  const [branch] = input.branches;
  return {
    description: input.description ?? null,
    links: { instagram: input.links.instagram ?? null, facebook: input.links.facebook ?? null, website: input.links.website ?? null },
    branch: { id: branch.id, isDefault: true, address: branch.address ?? null, location: branch.location ?? null, ...phones },
  };
}

/** The shape a profile has with its one default branch, for comparing two profiles. */
export function comparableProfile(profile: SalonProfile | null | undefined) {
  if (!profile) return null;
  expect(profile.branches.length, 'the profile lists the default branch').toBeGreaterThanOrEqual(1);
  const branch = profile.branches.find(b => b.isDefault);
  expect(branch, 'the profile names a default branch').toBeTruthy();
  return { description: profile.description, links: profile.links, branch };
}

/** E.164 form of the national numbers the fixtures use (LK, +94 and the number without its 0). */
export const e164 = (national: string) => `+94${national.replace(/\D/g, '').replace(/^0/, '')}`;

export class Profiles {
  constructor(readonly identity: Identity) {}

  call(salon: Salon, endpoint: Endpoint | Endpoint['name'], tokens?: Tokens, data?: unknown, headers: Record<string, string> = {}): Promise<APIResponse> {
    const e = typeof endpoint === 'string' ? ENDPOINTS.find(x => x.name === endpoint)! : endpoint;
    if (Object.keys(headers).length === 0) return onSalonHost(this.identity, salon, e.method, e.path, tokens, data);
    return this.identity.api.send(e.method, e.path, {
      headers: { Host: salon.hostname, ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}),
        ...(data === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers },
      data: data === undefined ? undefined : JSON.stringify(data),
    });
  }

  async draft(salon: Salon, tokens: Tokens): Promise<ProfileDraft> {
    return json<ProfileDraft>(await this.call(salon, 'read', tokens), 'SalonProfileDraft');
  }

  saveRaw(salon: Salon, tokens: Tokens | undefined, version: number, profile: unknown) {
    return this.call(salon, 'save', tokens, { version, profile });
  }
  async save(salon: Salon, tokens: Tokens, version: number, profile: ProfileInput): Promise<ProfileDraft> {
    return json<ProfileDraft>(await this.saveRaw(salon, tokens, version, profile), 'SalonProfileDraft');
  }
  publishRaw(salon: Salon, tokens: Tokens | undefined, version: number) { return this.call(salon, 'publish', tokens, { version }); }
  async publish(salon: Salon, tokens: Tokens, version: number): Promise<ProfileDraft> {
    return json<ProfileDraft>(await this.publishRaw(salon, tokens, version), 'SalonProfileDraft');
  }
  discardRaw(salon: Salon, tokens: Tokens | undefined, version: number) { return this.call(salon, 'discard', tokens, { version }); }
  async discard(salon: Salon, tokens: Tokens, version: number): Promise<ProfileDraft> {
    return json<ProfileDraft>(await this.discardRaw(salon, tokens, version), 'SalonProfileDraft');
  }

  /** The public salon, read on its own hostname with no credential, checked against the contract. */
  async published(salon: Salon): Promise<PublicSalon> {
    return json<PublicSalon>(await this.identity.api.getOn(salon.hostname, PUBLIC_SALON), 'Salon');
  }

  /** The salon's default branch id, as the draft names it. */
  async defaultBranch(salon: Salon, tokens: Tokens): Promise<string> {
    const draft = await this.draft(salon, tokens);
    const defaults = draft.profile.branches.filter(b => b.isDefault);
    expect(defaults.length, 'the draft names exactly one default branch').toBe(1);
    return defaults[0].id;
  }

  /** Saves a full, unique profile and publishes it, so the salon has a published profile to compare with. */
  async publishFresh(salon: Salon, tokens: Tokens): Promise<{ input: ProfileInput; draft: ProfileDraft }> {
    const before = await this.draft(salon, tokens);
    const branch = before.profile.branches.find(b => b.isDefault)!.id;
    const input = fullInput(branch);
    const saved = await this.save(salon, tokens, before.version, input);
    const draft = await this.publish(salon, tokens, saved.version);
    return { input, draft };
  }
}

/** A 400 with the field errors of a problem body; returns the field names and the raw body. */
export async function fieldErrors(response: APIResponse, label: string): Promise<{ fields: string[]; body: string }> {
  const answer = await answerOf(response);
  expect(answer.status, `${label}: HTTP status (body ${answer.body.slice(0, 300)})`).toBe(400);
  const problem = expectProblem(answer, label);
  expect(problem.code, `${label}: code`).toBe('bad_request');
  return { fields: (problem.errors ?? []).map(e => e.field), body: answer.body };
}

/** Asserts a refused call answered `status` with `code`. */
export async function refused(response: APIResponse, status: number, code: string, label: string) {
  const answer = await answerOf(response);
  expect(answer.status, `${label}: HTTP status (body ${answer.body.slice(0, 200)})`).toBe(status);
  expect(expectProblem(answer, label).code, `${label}: code`).toBe(code);
  return answer;
}

// ---- Database observations (owner role unless a tenant is given; never writes) ----

/** Rows per table for one salon, as the owning role sees them. */
export function profileRowCounts(salonId: string): Record<string, number> {
  return Object.fromEntries(PROFILE_TABLES.map(t => [t, Number(identitySql(`SELECT count(*) FROM ${t} WHERE tenant_id = ${sqlLiteral(salonId)}`))]));
}

/** A digest of every profile row of a salon (owner role), to prove a refused call changed nothing. */
export function profileSnapshot(salonId: string): string {
  return PROFILE_TABLES.map(t => identitySql(
    `SELECT coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text)), 'none') FROM ${t} x WHERE tenant_id = ${sqlLiteral(salonId)}`)).join(',');
}

export type AuditRow = { action: string; actorKind: string; actorId: string; subjectKind: string; at: string; before: string; after: string; details: string };

/** The salon's audit entries of the profile actions after an instant, oldest first (owner role). */
export function profileAudit(salonId: string, after: string): AuditRow[] {
  const out = identitySql(`SELECT json_build_object('action', action, 'actorKind', actor_kind, 'actorId', actor_id, 'subjectKind', subject_kind,
      'at', created_at, 'before', coalesce(before_values::text, ''), 'after', coalesce(after_values::text, ''), 'details', row_to_json(a)::text)
    FROM audit_entry a WHERE tenant_id = ${sqlLiteral(salonId)} AND action LIKE 'branding.profile%' AND created_at >= ${sqlLiteral(after)}::timestamptz
    ORDER BY created_at, id`);
  return out ? out.split('\n').map(line => JSON.parse(line) as AuditRow) : [];
}
