/**
 * F02 synthetic fixtures. Credentials stay in process memory and are never logged; the roster's own
 * development password is public by decision 099.
 *
 * Accounts (decision 099, docs/qa/test-plan.md, "Accounts"): QA runs against the QA instance only.
 * A test that needs only "someone in this role" at a synthetic salon gets the roster's account in that
 * role (shared/roster.ts); `identity.staff()` hands it out the first time a test asks for a role at a
 * salon, and an extra account (a realistic person, shared/people.ts) every later time, so two people
 * a test asks for are always two people. Every account seeded through the local hook has the
 * development password `123456@321`; an account made for a product path (adding staff, a new
 * password) keeps a passphrase the identity policy accepts, because the product refuses `123456@321`.
 *
 * Around every test the three salons' identity records are reset, which removes the extra accounts
 * the test made. The reset before a test leaves the salons without staff, exactly as the tests were
 * written (a test that proves "the last Owner cannot be removed" needs no other Owner present); the
 * roster account a test asks for is seeded when it asks. The reset after a test is followed by
 * seeding the whole roster again, so the instance always rests with the roster in place and a shell
 * check can rely on it.
 */
import { createHmac, randomBytes } from 'node:crypto';
import type { APIResponse } from '@playwright/test';
import type { components } from '@contracts/schema';
import { ApiClient } from './api-client';
import { answerOf, expectProblem } from './answers';
import { test as base, expect } from './fixtures';
import { schemaValidator } from './openapi';
import { alpha, beta, gamma, type Salon } from './tenants';
import { mobile, passphrase, person, staffDomain } from './people';
import { DEVELOPMENT_PASSWORD, hasRoster, roster, rosterStaff } from './roster';
import { SchedulerClockGuard } from './identity-clock-cleanup';

export const IDENTITY = '/api/v1/identity';
export const ROLES = ['OWNER', 'MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'] as const;
export type Role = typeof ROLES[number];
export type Tokens = Required<components['schemas']['Tokens']>;
export type SignIn = components['schemas']['SignIn'];
export type Staff = Required<components['schemas']['StaffView']>;
export type Principal = Required<components['schemas']['Principal']>;
export type SessionPage = Required<components['schemas']['PageSessionView']>;
export type AuditPage = Required<components['schemas']['PageAuditView']>;
export type SeedAccount = { email: string; mobile: string; password: string; role: Role; totpSecret?: string; professionalGrant?: boolean };
export type Actor = { salon: Salon; tokens: Tokens; principal: Principal };
export type SignedStaff = Actor & { account: SeedAccount };

/**
 * An extra account: a realistic person (unique within the test), a valid Sri Lankan mobile in local
 * form (`07XXXXXXXX`) and a passphrase the identity policy accepts, for a product path. Seeding it
 * through `identity.seed()` gives it the development password instead (decision 099).
 * `salon` chooses the email's domain (alpha's when not given).
 */
export function syntheticAccount(role: Role = 'MANAGER', mfa = role === 'OWNER', salon?: Salon | string): SeedAccount {
  const someone = person();
  return { email: `${someone.local}@${staffDomain(typeof salon === 'string' ? salon : salon?.slug)}`, mobile: mobile(),
    password: passphrase(), role,
    ...(mfa ? { totpSecret: base32(randomBytes(20)) } : {}) };
}

function base32(bytes: Buffer): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, value = 0, out = '';
  for (const byte of bytes) { value = (value << 8) | byte; bits += 8; while (bits >= 5) { out += alphabet[(value >>> (bits - 5)) & 31]; bits -= 5; } }
  if (bits) out += alphabet[(value << (5 - bits)) & 31];
  return out;
}

/** RFC 6238 fixture calculation, independent of the application's implementation. */
export function totp(secret: string, instant: string): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, value = 0; const bytes: number[] = [];
  for (const letter of secret.toUpperCase().replace(/=/g,'')) {
    const digit = alphabet.indexOf(letter); if (digit < 0) throw new Error('ENVIRONMENT: invalid fixture TOTP seed');
    value = (value << 5) | digit; bits += 5;
    if (bits >= 8) { bytes.push((value >>> (bits - 8)) & 255); bits -= 8; }
  }
  const counter = Buffer.alloc(8); counter.writeBigUInt64BE(BigInt(Math.floor(Date.parse(instant) / 30000)));
  const mac = createHmac('sha1', Buffer.from(bytes)).update(counter).digest();
  const offset = mac[mac.length-1] & 15;
  return ((mac.readUInt32BE(offset) & 0x7fffffff) % 1000000).toString().padStart(6,'0');
}

export async function json<T>(response: APIResponse, schema: string, status = 200): Promise<T> {
  expect(response.status(), `${schema}: HTTP status`).toBe(status);
  expect(response.headers()['content-type']?.split(';')[0]).toBe('application/json');
  const body = await response.json(); const validate = schemaValidator(schema);
  // Do not include a credential-bearing response in assertion output.
  expect(Boolean(validate(body)), `${schema}: generated contract shape`).toBe(true);
  return body as T;
}

export async function problem(response: APIResponse, status: number, code: components['schemas']['Problem']['code']) {
  expect(response.status(), 'HTTP error status').toBe(status);
  const result = expectProblem(await answerOf(response), 'identity error');
  expect(result.code).toBe(code);
  return result;
}

export function assertTokens(tokens: components['schemas']['Tokens'] | undefined): asserts tokens is Tokens {
  expect(Boolean(tokens), 'credentials exist').toBe(true);
  expect(tokens?.state).toBe('authenticated');
  expect(Boolean(tokens?.accessToken && tokens.accessToken.split('.').length === 3), 'signed access credential').toBe(true);
  expect(Boolean(tokens?.refreshToken && tokens.refreshToken.length >= 43), '256-bit opaque refresh credential').toBe(true);
  expect(tokens?.sessionId).toMatch(/^[0-9a-f-]{36}$/);
  expect(Number.isFinite(Date.parse(tokens?.accessExpiresAt ?? ''))).toBe(true);
  expect(Number.isFinite(Date.parse(tokens?.refreshExpiresAt ?? ''))).toBe(true);
}

export class Identity {
  private schedulerClock:SchedulerClockGuard|undefined;
  /** The roster accounts this test was already handed, `<slug>/<role>`: the next request for one is an extra account. */
  private readonly rosterHanded=new Set<string>();
  constructor(readonly api: ApiClient) {}
  call(salon: Salon | null, method: string, path: string, data?: unknown, tokens?: Tokens, extra: Record<string,string> = {}) {
    return this.api.send(method, path.startsWith('/local/') ? path : `${IDENTITY}${path}`, {
      headers: { ...(salon ? { Host: salon.hostname } : {}), ...(data === undefined ? {} : {'Content-Type':'application/json'}),
        ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}), ...extra },
      data: data === undefined ? undefined : JSON.stringify(data),
    });
  }
  async now(): Promise<string> { const r = await this.api.meta(); expect(r.status()).toBe(200); return (await r.json()).time; }
  async clock(instant?: string) {
    if(instant){this.schedulerClock??=await SchedulerClockGuard.snapshot();this.schedulerClock.advanced(instant);}
    const r = await this.api.send(instant?'PUT':'DELETE','/local/clock', {
    headers: {'Content-Type':'application/json'}, data: instant ? JSON.stringify({instant}) : undefined }); expect(r.status()).toBe(200); }
  async restoreClockFixture(){await this.clock();if(this.schedulerClock)await this.schedulerClock.restoreAfterRealClock();}
  async reset() { for(const salon of [alpha,beta,gamma]) expect((await this.call(salon,'DELETE','/local/identity')).status()).toBe(204); }
  /** Seeds the whole fixed roster of the three salons again (idempotent: present accounts are kept as they are). */
  async seedRoster() { for(const salon of [alpha,beta,gamma]) await this.seed(salon,rosterStaff(salon)); }
  /**
   * Seeds accounts through the local hook. Every account seeded this way has the roster's development
   * password (decision 099), written back onto the account so a later sign-in uses it.
   */
  async seed(salon: Salon, accounts: SeedAccount[]) {
    for (const account of accounts) account.password = DEVELOPMENT_PASSWORD;
    const payload = accounts.map(account => { const { name: _name, ...seeded } = account as SeedAccount & { name?: string }; return seeded; });
    const r = await this.call(salon,'POST','/local/identity/seed',{accounts:payload}); expect(r.status()).toBe(200);
    const body = await r.json(); expect(body).toEqual({accounts:accounts.length});
  }
  async signIn(salon: Salon, account: SeedAccount, identifier = account.email, deviceLabel = 'QA device'): Promise<SignIn> {
    return json(await this.call(salon,'POST','/staff/sign-in',{identifier,password:account.password,deviceLabel}),'SignIn');
  }
  async verify(salon: Salon, challenge: SignIn, secret: string): Promise<Tokens> {
    const tokens = await json<Tokens>(await this.call(salon,'POST','/staff/mfa/verify',{
      challengeId:challenge.challengeId,challengeToken:challenge.challengeToken,code:totp(secret,await this.now()),
    }),'Tokens'); assertTokens(tokens); return tokens;
  }
  async principal(salon: Salon | null, tokens: Tokens): Promise<Principal> {
    return json(await this.call(salon,'GET','/me',undefined,tokens),'Principal');
  }
  async login(salon: Salon, account: SeedAccount, deviceLabel = 'QA device'): Promise<SignedStaff> {
    const signIn = await this.signIn(salon,account,account.email,deviceLabel);
    let tokens: Tokens;
    if (signIn.state === 'verify_mfa') {
      if(!account.totpSecret) throw new Error('ENVIRONMENT: fixture has no MFA seed');
      tokens = await this.verify(salon,signIn,account.totpSecret);
    } else { assertTokens(signIn.credentials); tokens = signIn.credentials; }
    return {salon,account,tokens,principal:await this.principal(salon,tokens)};
  }
  /** The roster's account in this role at a synthetic salon, seeded if it is missing, signed in. */
  async member(salon: Salon = alpha, role: Role = 'MANAGER'): Promise<SignedStaff> {
    const account = roster(salon, role); await this.seed(salon,[account]); this.rosterHanded.add(`${salon.slug}/${role}`);
    return this.login(salon,account);
  }
  /** An extra account in this role, a realistic person seeded for this test only, signed in. */
  async extra(salon = alpha, role: Role = 'MANAGER', mfa = role === 'OWNER') {
    const account = syntheticAccount(role,mfa,salon); await this.seed(salon,[account]); return this.login(salon,account);
  }
  /**
   * Someone in this role, signed in: the roster's account the first time this test asks for the role
   * at a synthetic salon (with the roster's own MFA arrangement: an Owner enrolled, the others not),
   * an extra account otherwise.
   */
  async staff(salon = alpha, role: Role = 'MANAGER', mfa = role === 'OWNER') {
    if (hasRoster(salon) && mfa === (role === 'OWNER') && !this.rosterHanded.has(`${salon.slug}/${role}`)) return this.member(salon, role);
    return this.extra(salon, role, mfa);
  }
  async sessions(actor: Actor, page = 0, size = 100): Promise<SessionPage> {
    return json(await this.call(actor.salon,'GET',`/sessions?page=${page}&size=${size}`,undefined,actor.tokens),'PageSessionView');
  }
  async list(actor: SignedStaff): Promise<Staff[]> {
    const page = await json<Required<components['schemas']['PageStaffView']>>(await this.call(actor.salon,'GET','/staff?size=100',undefined,actor.tokens),'PageStaffView');
    return page.items as Staff[];
  }
  async audit(actor: Actor, subjectId?: string): Promise<AuditPage> {
    return json(await this.call(actor.salon,'GET',`/audit?size=100${subjectId?`&subjectId=${encodeURIComponent(subjectId)}`:''}`,undefined,actor.tokens),'PageAuditView');
  }
}

export const test = base.extend<{identity:Identity}>({identity: async({api},use)=>{
  const identity = new Identity(api); await identity.clock(); await identity.reset();
  try { await use(identity); } finally { await identity.restoreClockFixture(); await identity.reset(); await identity.seedRoster(); }
}});
export { expect, alpha, beta, gamma };
