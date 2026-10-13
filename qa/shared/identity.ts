/** F02 synthetic fixtures. Credentials stay in process memory and are never logged. */
import { createHmac, randomBytes, randomInt, randomUUID } from 'node:crypto';
import type { APIResponse } from '@playwright/test';
import type { components } from '@contracts/schema';
import { ApiClient } from './api-client';
import { answerOf, expectProblem } from './answers';
import { test as base, expect } from './fixtures';
import { schemaValidator } from './openapi';
import { alpha, beta, gamma, type Salon } from './tenants';

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

export function syntheticAccount(role: Role = 'MANAGER', mfa = role === 'OWNER'): SeedAccount {
  return { email: `qa-${randomUUID()}@example.invalid`, mobile: `077${randomInt(1000000,10000000)}`,
    password: `QA ${randomBytes(20).toString('hex')}`, role,
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
  constructor(readonly api: ApiClient) {}
  call(salon: Salon | null, method: string, path: string, data?: unknown, tokens?: Tokens, extra: Record<string,string> = {}) {
    return this.api.send(method, path.startsWith('/local/') ? path : `${IDENTITY}${path}`, {
      headers: { ...(salon ? { Host: salon.hostname } : {}), ...(data === undefined ? {} : {'Content-Type':'application/json'}),
        ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}), ...extra },
      data: data === undefined ? undefined : JSON.stringify(data),
    });
  }
  async now(): Promise<string> { const r = await this.api.meta(); expect(r.status()).toBe(200); return (await r.json()).time; }
  async clock(instant?: string) { const r = await this.api.send(instant?'PUT':'DELETE','/local/clock', {
    headers: {'Content-Type':'application/json'}, data: instant ? JSON.stringify({instant}) : undefined }); expect(r.status()).toBe(200); }
  async reset() { for(const salon of [alpha,beta,gamma]) expect((await this.call(salon,'DELETE','/local/identity')).status()).toBe(204); }
  async seed(salon: Salon, accounts: SeedAccount[]) {
    const r = await this.call(salon,'POST','/local/identity/seed',{accounts}); expect(r.status()).toBe(200);
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
  async staff(salon = alpha, role: Role = 'MANAGER', mfa = role === 'OWNER') {
    const account = syntheticAccount(role,mfa); await this.seed(salon,[account]); return this.login(salon,account);
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
  try { await use(identity); } finally { await identity.clock(); await identity.reset(); }
}});
export { expect, alpha, beta, gamma };
