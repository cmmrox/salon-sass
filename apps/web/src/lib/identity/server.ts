import { createHmac } from 'node:crypto';
import type { ApiClient, components } from '@salon/api-contracts';
import { NextRequest, NextResponse } from 'next/server';
import { createServerApiClient, requestSignal } from '@/lib/api/client';
import { currentRequest } from '@/lib/observability/request-context';
import { hasSlugForm, platformDomainOf, slugOf } from '@/lib/tenancy/hostname';
import { strictInstant } from './network';
import { readBoundedJson } from '@/lib/http/bounded-json';

export type Kind = 'staff' | 'support';
type SupportCredential = components['schemas']['SupportCredential'];
export type Principal = components['schemas']['Principal'];
type Tokens = components['schemas']['Tokens'];
export const cookieName = (kind: Kind, name: string) => `salon_${kind}_${name}`;
const localHttp = () => process.env.SALON_WEB_LOCAL_HTTP === 'true';

export function requestHost(raw: string | null): { hostname: string; origin: string } | null {
  if (!raw || !/^[a-zA-Z0-9.-]+(?::[0-9]{1,5})?$/.test(raw)) return null;
  const hostname = raw.split(':')[0]!.toLowerCase();
  const port = raw.split(':')[1];
  if (port && (Number(port) < 1 || Number(port) > 65535)) return null;
  const slug = slugOf(hostname, platformDomainOf(process.env.SALON_PLATFORM_DOMAIN));
  if (!slug || !hasSlugForm(slug)) return null;
  return { hostname, origin: `${localHttp() ? 'http' : 'https'}://${raw.toLowerCase()}` };
}
export function csrf(request: NextRequest): boolean {
  const host = requestHost(request.headers.get('host'));
  return Boolean(host && request.headers.get('origin') === host.origin && request.headers.get('sec-fetch-site') !== 'cross-site');
}
/** A support session's mutations (F03-S04): the exact salon origin, and same-origin Fetch Metadata only. */
export function supportCsrf(request: NextRequest): boolean {
  const site = request.headers.get('sec-fetch-site');
  return csrf(request) && site !== 'same-site';
}
/** The support credential's host-only HttpOnly cookies on the salon's host (F03-S04). */
export const SUPPORT_COOKIES = ['access', 'session'] as const;
/** Support reads: what the Owner can read; support-access and own-account reads are refused by the API. */
const SUPPORT_READS = ['principal', 'staff', 'audit', 'entitlements', 'invoices'];
export function proxyKey(): Buffer {
  const value = process.env.SALON_IDENTITY_PROXY_KEY;
  if (!value || !/^[a-fA-F0-9]{64}$/.test(value)) throw new Error('Configure SALON_IDENTITY_PROXY_KEY');
  return Buffer.from(value, 'hex');
}
export function assertion(host: string, path: string, ip: string, time: string): string {
  const proof = ['v1', host, 'POST', path, ip, time].join('\n');
  return `v1;${ip};${time};${createHmac('sha256', proxyKey()).update(proof).digest('hex')}`;
}
export type Credentials = { access?: string; refresh?: string; expiry?: string };
/**
 * Whether a refresh family may renew: only when the access credential is gone or answered 401
 * and, if it is still present, its recorded expiry has passed by the API's clock. Expiry metadata
 * never authorizes access; `unknown` means the API's clock could not be read.
 */
export async function renewal(client: ApiClient, { access, refresh, expiry }: Credentials, status: number): Promise<'renewable' | 'spent' | 'unknown'> {
  if (!refresh || !(status === 401 || !access)) return 'spent';
  const meta = await client.GET('/api/v1/meta', { signal: requestSignal() });
  if (!meta.data || !strictInstant(meta.data.time)) return 'unknown';
  return !access || (strictInstant(expiry) && Date.parse(expiry) <= Date.parse(meta.data.time)) ? 'renewable' : 'spent';
}
function output(data: unknown, status = 200) { return NextResponse.json(data, { status, headers: { 'Cache-Control': 'private, no-store' } }); }
function problem(code: string, status: number, extra: Record<string, unknown> = {}) { return output({ code, ...extra }, status); }
function setCookie(response: NextResponse, kind: Kind, name: string, value: string, maxAge: number) {
  response.cookies.set(cookieName(kind, name), value, { httpOnly: true, secure: !localHttp(), sameSite: 'lax', path: '/', maxAge });
}
export function clearCookies(response: NextResponse, kind: Kind) {
  const names = kind === 'support' ? SUPPORT_COOKIES : ['access', 'refresh', 'expires', 'session', 'challenge', 'challenge_id'];
  for (const name of names) setCookie(response, kind, name, '', 0);
}
/**
 * Left on the salon's host when a support view ends (BUG-F03-005): the support cookies may be
 * cleared by any refused request (a prefetch, a read) before the browser's next page, and that page
 * must still land on the "session ended" notice rather than the staff sign-in. It carries no
 * credential and authorizes nothing; it only chooses the notice, and the entry page forgets it.
 */
export const SUPPORT_ENDED_COOKIE = 'salon_session_ended';
const SUPPORT_ENDED_SECONDS = 600;
export function supportEnded(request: { cookies: { get(name: string): { value: string } | undefined } }): boolean {
  return request.cookies.get(SUPPORT_ENDED_COOKIE)?.value === 'support';
}
/** A support session that ended: its cookies cleared host-only, the ended marker left behind. */
export function endSupportView(response: NextResponse) {
  clearCookies(response, 'support');
  response.cookies.set(SUPPORT_ENDED_COOKIE, 'support', { httpOnly: true, secure: !localHttp(), sameSite: 'lax', path: '/', maxAge: SUPPORT_ENDED_SECONDS });
}
function forgetSupportEnded(response: NextResponse) {
  response.cookies.set(SUPPORT_ENDED_COOKIE, '', { httpOnly: true, secure: !localHttp(), sameSite: 'lax', path: '/', maxAge: 0 });
}
function issueSupport(response: NextResponse, credential: SupportCredential) {
  if (!strictInstant(credential.issuedAt) || !strictInstant(credential.expiresAt)) throw new Error('Invalid credential expiry');
  // Both instants are the API's; the support credential lives until its session's expiry, with no refresh.
  const lifetime = Math.max(1, Math.floor((Date.parse(credential.expiresAt) - Date.parse(credential.issuedAt)) / 1000));
  setCookie(response, 'support', 'access', credential.accessToken, lifetime);
  setCookie(response, 'support', 'session', credential.sessionId, lifetime);
}

/**
 * A support session's BFF actions on the salon's host (F03-S04): `entry` exchanges the single-use
 * code once for the support credential, kept only in host-only HttpOnly `salon_support_*` cookies;
 * `end` ends the session; `clear` leaves the support view; reads are the Owner's reads. Every other
 * mutation is refused here as the API refuses it: 403 `support_read_only`, with no upstream call.
 */
async function handleSupport(request: NextRequest, action: string, host: { hostname: string }) {
  if (request.method === 'GET' && !SUPPORT_READS.includes(action)) return action === 'sessions' || action === 'support-sessions' ? problem('permission_denied', 403) : problem('method_not_allowed', 405);
  if (request.method !== 'GET' && request.method !== 'POST') return problem('method_not_allowed', 405);
  if (request.method === 'POST' && !supportCsrf(request)) return problem('request_rejected', 403);
  if (request.method === 'POST' && !['entry', 'end', 'clear'].includes(action)) return problem('support_read_only', 403);
  const client = createServerApiClient({ hostname: host.hostname });
  if (!client) return problem('service_unavailable', 503);
  const access = request.cookies.get(cookieName('support', 'access'))?.value;
  const options = { headers: access ? { Authorization: `Bearer ${access}` } : {}, signal: requestSignal() };
  if (action === 'clear') { const response = output({ ok: true }); clearCookies(response, 'support'); forgetSupportEnded(response); return response; }
  if (action === 'entry') {
    const read = await readBoundedJson(request, 1024);
    if (!read.ok) return problem('bad_request', 400);
    const code = typeof read.value.code === 'string' ? read.value.code : '';
    const r = await client.POST('/api/v1/support/entry', { signal: requestSignal(), body: { code } });
    if (!r.data) return failure(r);
    const response = output({ ok: true, expiresAt: r.data.expiresAt });
    issueSupport(response, r.data);
    forgetSupportEnded(response);
    return response;
  }
  if (action === 'end') {
    const r = await client.DELETE('/api/v1/support/session', options);
    // Ended now, or already ended: either way the browser leaves the support view.
    const response = r.response.ok || r.response.status === 401 ? output({ ok: true }) : failure(r);
    if (r.response.ok || r.response.status === 401) endSupportView(response);
    return response;
  }
  const answer = await client.GET('/api/v1/identity/me', options);
  if (!answer.data || answer.data.kind !== 'support') {
    if (answer.response.status === 503 || (answer.response.status !== 401 && answer.response.status !== 403 && !answer.data)) return problem('service_unavailable', 503);
    // The session ended (expiry, End session, Admin or Owner revocation): the browser leaves the support view.
    const response = problem('invalid_session', 401, { renewable: false });
    endSupportView(response);
    return response;
  }
  const principal = answer.data;
  const permission: Record<string, string> = { entitlements: 'entitlement.read', invoices: 'invoice.read', staff: 'staff.list', audit: 'audit.list' };
  if (permission[action] && !principal.permissions.includes(permission[action]!)) return problem('permission_denied', 403);
  if (action === 'principal') return output(principal);
  const page = Number(request.nextUrl.searchParams.get('page') ?? 0);
  const query = { page, size: 10 };
  const result = action === 'staff' ? await client.GET('/api/v1/identity/staff', { ...options, params: { query } })
    : action === 'audit' ? await client.GET('/api/v1/identity/audit', { ...options, params: { query } })
    : action === 'entitlements' ? await client.GET('/api/v1/salon/entitlements', options)
    : await client.GET('/api/v1/salon/invoices', { ...options, params: { query } });
  if (result.response.status === 401) { const response = problem('invalid_session', 401, { renewable: false }); endSupportView(response); return response; }
  return result.data ? output(result.data) : problem(result.error?.code ?? 'service_unavailable', result.response.status, { errors: result.error?.errors });
}
function issue(response: NextResponse, kind: Kind, tokens: Tokens) {
  if (!strictInstant(tokens.accessExpiresAt) || !strictInstant(tokens.refreshExpiresAt)) throw new Error('Invalid credential expiry');
  // Both instants are API Clock-derived; never subtract the web machine clock.
  const lifetime = Math.max(1, Math.floor((Date.parse(tokens.refreshExpiresAt) - Date.parse(tokens.accessExpiresAt)) / 1000) + 300);
  setCookie(response, kind, 'access', tokens.accessToken, 300);
  setCookie(response, kind, 'refresh', tokens.refreshToken, lifetime);
  setCookie(response, kind, 'expires', tokens.accessExpiresAt, lifetime);
  setCookie(response, kind, 'session', tokens.sessionId, lifetime);
  for (const name of ['challenge', 'challenge_id']) setCookie(response, kind, name, '', 0);
}

/** Allowlisted account operations only; neither a URL nor a tenant selector comes from input. */
export async function handleIdentity(request: NextRequest, kind: Kind, action: string) {
  try {
    const host = requestHost(request.headers.get('host'));
    if (!host) return problem('not_found', 404);
    if (kind === 'support') return await handleSupport(request, action, host);
    const reads = ['principal', 'sessions', 'staff', 'audit', 'entitlements', 'invoices', 'support-sessions'];
    if (request.method === 'GET' && !reads.includes(action)) return problem('method_not_allowed', 405);
    if (request.method !== 'GET' && !csrf(request)) return problem('request_rejected', 403);
    const client = createServerApiClient({ hostname: host.hostname });
    if (!client) return problem('service_unavailable', 503);
    const access = request.cookies.get(cookieName(kind, 'access'))?.value;
    const refresh = request.cookies.get(cookieName(kind, 'refresh'))?.value;
    const headers: Record<string, string> = access ? { Authorization: `Bearer ${access}` } : {};
    const signal = requestSignal();
    const read = request.method === 'GET' ? { ok: true as const, value: {} } : await readBoundedJson(request, 16384);
    if (!read.ok) return problem('bad_request', 400);
    const body: Record<string, unknown> = read.value;
    // Input values are forwarded only through each generated contract's explicit fields.
    const text = (name: string) => typeof body[name] === 'string' ? body[name] as string : '';
    const page = Number(request.nextUrl.searchParams.get('page') ?? 0);
    const query = { page, size: 10 };
    const options = { headers, signal };
    const publicActions = ['sign-in', 'mfa-enroll', 'mfa-verify'];
    let principal: Principal | undefined;
    if (!publicActions.includes(action) && action !== 'clear') {
      const answer = await client.GET('/api/v1/identity/me', options);
      principal = answer.data;
      if (!principal || principal.kind !== kind) {
        const expiry = request.cookies.get(cookieName(kind, 'expires'))?.value;
        const renewable = await renewal(client, { access, refresh, expiry }, answer.response.status) === 'renewable';
        if (action === 'refresh' && renewable) {
          const rotated = await client.POST('/api/v1/identity/sessions/refresh', { body: { refreshToken: refresh! }, signal: requestSignal() });
          const verified = rotated.data ? await client.GET('/api/v1/identity/me', { headers: { Authorization: `Bearer ${rotated.data.accessToken}` }, signal: requestSignal() }) : null;
          const valid = Boolean(rotated.data && verified?.data?.kind === kind);
          const response = valid ? output({ ok: true }) : problem('invalid_session', 401);
          if (valid && rotated.data) issue(response, kind, rotated.data); else clearCookies(response, kind);
          return response;
        }
        if (action === 'refresh') {
          const response = problem('invalid_session', 401); clearCookies(response, kind); return response;
        }
        if (answer.response.status === 503) return problem('service_unavailable', 503);
        return problem('invalid_session', 401, { renewable });
      }
    }
    const permission: Record<string, string> = { entitlements: 'entitlement.read', invoices: 'invoice.read', staff: 'staff.list', audit: 'audit.list', 'staff-create': 'staff.manage', 'staff-change': 'staff.manage', 'staff-remove': 'staff.manage', password: 'password.change', 'mfa-start': 'mfa.enroll', 'support-sessions': 'support.manage', 'support-revoke': 'support.manage' };
    if (principal && permission[action] && !principal.permissions.includes(permission[action]!)) return problem('permission_denied', 403);
    if (action === 'principal') return output(principal);
    if (action === 'refresh') return output({ ok: true }); // A competing tab already renewed.
    if (action === 'clear') { const response = output({ ok: true }); clearCookies(response, kind); return response; }
    if (request.method === 'GET') {
      const result = action === 'sessions' ? await client.GET('/api/v1/identity/sessions', { ...options, params: { query } })
        : action === 'staff' ? await client.GET('/api/v1/identity/staff', { ...options, params: { query } })
        : action === 'audit' ? await client.GET('/api/v1/identity/audit', { ...options, params: { query } })
        : action === 'entitlements' ? await client.GET('/api/v1/salon/entitlements', options)
        : action === 'invoices' ? await client.GET('/api/v1/salon/invoices', { ...options, params: { query: { page, size: 10 } } })
        : action === 'support-sessions' ? await client.GET('/api/v1/salon/support-sessions', { ...options, params: { query } }) : null;
      if (!result) return problem('not_found', 404);
      return result.data ? output(result.data) : problem(result.error?.code ?? 'service_unavailable', result.response.status, { errors: result.error?.errors });
    }
    if (request.method !== 'POST') return problem('method_not_allowed', 405);
    if (action === 'sign-in') {
      const ip = currentRequest()?.clientAddress;
      const meta = await client.GET('/api/v1/meta', { signal: requestSignal() });
      if (!ip || !meta.data || !strictInstant(meta.data.time)) return problem('service_unavailable', 503);
      const path = '/api/v1/identity/staff/sign-in';
      headers['x-salon-client-assertion'] = assertion(host.hostname, path, ip, meta.data.time);
    }
    const challengeId = request.cookies.get(cookieName(kind, 'challenge_id'))?.value ?? '';
    const challengeToken = request.cookies.get(cookieName(kind, 'challenge'))?.value ?? '';
    switch (action) {
      case 'sign-in': {
        const r = await client.POST('/api/v1/identity/staff/sign-in', { ...options, body: { identifier: text('identifier'), password: text('password'), deviceLabel: text('deviceLabel') } });
        if (!r.data) return failure(r);
        const response = output({ state: r.data.state, expiresAt: r.data.expiresAt });
        if (r.data.credentials) issue(response, kind, r.data.credentials);
        else {
          setCookie(response, kind, 'challenge_id', r.data.challengeId ?? '', 300);
          setCookie(response, kind, 'challenge', r.data.challengeToken ?? '', 300);
        }
        return response;
      }
      case 'mfa-start': {
        const r = await client.POST('/api/v1/identity/staff/mfa/challenges', options);
        if (!r.data) return failure(r);
        const response = output({ state: r.data.state, expiresAt: r.data.expiresAt });
        setCookie(response, kind, 'challenge_id', r.data.challengeId ?? '', 300);
        setCookie(response, kind, 'challenge', r.data.challengeToken ?? '', 300);
        return response;
      }
      case 'mfa-enroll': {
        const r = await client.POST('/api/v1/identity/staff/mfa/enroll', { ...options, body: { challengeId, challengeToken } });
        return r.data ? output({ secret: r.data.secret, otpauthUri: r.data.otpauthUri, expiresAt: r.data.expiresAt }) : failure(r);
      }
      case 'mfa-verify': {
        const r = await client.POST('/api/v1/identity/staff/mfa/verify', { ...options, body: { challengeId, challengeToken, code: text('code') } });
        const response = r.data ? output({ ok: true }) : failure(r); if (r.data) issue(response, kind, r.data); return response;
      }
      case 'password': {
        const r = await client.PUT('/api/v1/identity/staff/password', { ...options, body: { oldPassword: text('oldPassword'), newPassword: text('newPassword') } });
        const response = r.data ? output({ ok: true }) : failure(r); if (r.data) issue(response, kind, r.data); return response;
      }
      case 'logout': case 'revoke': case 'revoke-all': {
        const id = action === 'logout' ? request.cookies.get(cookieName(kind, 'session'))?.value ?? '' : text('id');
        const r = action === 'revoke-all' ? await client.DELETE('/api/v1/identity/sessions', options)
          : await client.DELETE('/api/v1/identity/sessions/{id}', { ...options, params: { path: { id } } });
        const response = r.response.ok ? output({ ok: true }) : failure(r);
        if (r.response.ok && (action !== 'revoke' || id === request.cookies.get(cookieName(kind, 'session'))?.value)) clearCookies(response, kind);
        return response;
      }
      case 'staff-create': case 'staff-change': case 'staff-remove': {
        const r = action === 'staff-create' ? await client.POST('/api/v1/identity/staff', { ...options, body: { email: text('email'), mobile: text('mobile'), password: text('password'), role: text('role'), professionalGrant: body.professionalGrant as boolean } })
          : action === 'staff-change' ? await client.PATCH('/api/v1/identity/staff/{id}', { ...options, params: { path: { id: text('id') } }, body: { version: body.version as number, role: text('role'), professionalGrant: body.professionalGrant as boolean } })
          : await client.DELETE('/api/v1/identity/staff/{id}', { ...options, params: { path: { id: text('id') } } });
        return r.response.ok ? output(r.data ?? { ok: true }) : failure(r);
      }
      case 'support-revoke': {
        const r = await client.DELETE('/api/v1/salon/support-sessions/{id}', { ...options, params: { path: { id: text('id') } } });
        return r.response.ok ? output({ ok: true }) : failure(r);
      }
      default: return problem('not_found', 404);
    }
  } catch {
    const response = problem('service_unavailable', 503);
    // Unknown rotation outcome is never retried: prevent restoring the spent family.
    if (action === 'refresh') clearCookies(response, kind);
    return response;
  }
}
function failure(r: { error?: { code?: string; errors?: unknown }; response: Response }) {
  const retry = r.response.headers.get('retry-after');
  return problem(r.error?.code ?? 'service_unavailable', r.response.status, { errors: r.error?.errors, ...(retry ? { retryAfter: Number(retry) } : {}) });
}
