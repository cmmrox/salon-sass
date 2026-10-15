import { createHmac } from 'node:crypto';
import type { components } from '@salon/api-contracts';
import { NextRequest, NextResponse } from 'next/server';
import { createServerApiClient, requestSignal } from '@/lib/api/client';
import { currentRequest } from '@/lib/observability/request-context';
import { hasSlugForm, platformDomainOf, slugOf } from '@/lib/tenancy/hostname';
import { strictInstant } from './network';

export type Kind = 'staff';
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
export function proxyKey(): Buffer {
  const value = process.env.SALON_IDENTITY_PROXY_KEY;
  if (!value || !/^[a-fA-F0-9]{64}$/.test(value)) throw new Error('Configure SALON_IDENTITY_PROXY_KEY');
  return Buffer.from(value, 'hex');
}
export function assertion(host: string, path: string, ip: string, time: string): string {
  const proof = ['v1', host, 'POST', path, ip, time].join('\n');
  return `v1;${ip};${time};${createHmac('sha256', proxyKey()).update(proof).digest('hex')}`;
}
function output(data: unknown, status = 200) { return NextResponse.json(data, { status, headers: { 'Cache-Control': 'private, no-store' } }); }
function problem(code: string, status: number, extra: Record<string, unknown> = {}) { return output({ code, ...extra }, status); }
function setCookie(response: NextResponse, kind: Kind, name: string, value: string, maxAge: number) {
  response.cookies.set(cookieName(kind, name), value, { httpOnly: true, secure: !localHttp(), sameSite: 'lax', path: '/', maxAge });
}
export function clearCookies(response: NextResponse, kind: Kind) {
  for (const name of ['access', 'refresh', 'expires', 'session', 'challenge', 'challenge_id']) setCookie(response, kind, name, '', 0);
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
    const reads = ['principal', 'sessions', 'staff', 'audit'];
    if (request.method === 'GET' && !reads.includes(action)) return problem('method_not_allowed', 405);
    if (request.method !== 'GET' && !csrf(request)) return problem('request_rejected', 403);
    const client = createServerApiClient({ hostname: host.hostname });
    if (!client) return problem('service_unavailable', 503);
    const access = request.cookies.get(cookieName(kind, 'access'))?.value;
    const refresh = request.cookies.get(cookieName(kind, 'refresh'))?.value;
    const headers: Record<string, string> = access ? { Authorization: `Bearer ${access}` } : {};
    const signal = requestSignal();
    if (Number(request.headers.get('content-length') ?? 0) > 16384) return problem('bad_request', 400);
    const body: Record<string, unknown> = request.method === 'GET' ? {} : await request.json().catch(() => ({}));
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
        let renewable = false;
        const expiry = request.cookies.get(cookieName(kind, 'expires'))?.value;
        if (refresh && (answer.response.status === 401 || !access)) {
          const meta = await client.GET('/api/v1/meta', { signal: requestSignal() });
          renewable = Boolean(meta.data && strictInstant(meta.data.time) && (!access || (strictInstant(expiry) && Date.parse(expiry) <= Date.parse(meta.data.time))));
        }
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
    const permission: Record<string, string> = { staff: 'staff.list', audit: 'audit.list', 'staff-create': 'staff.manage', 'staff-change': 'staff.manage', 'staff-remove': 'staff.manage', password: 'password.change', 'mfa-start': 'mfa.enroll' };
    if (principal && permission[action] && !principal.permissions.includes(permission[action]!)) return problem('permission_denied', 403);
    if (action === 'principal') return output(principal);
    if (action === 'refresh') return output({ ok: true }); // A competing tab already renewed.
    if (action === 'clear') { const response = output({ ok: true }); clearCookies(response, kind); return response; }
    if (request.method === 'GET') {
      const result = action === 'sessions' ? await client.GET('/api/v1/identity/sessions', { ...options, params: { query } })
        : action === 'staff' ? await client.GET('/api/v1/identity/staff', { ...options, params: { query } })
        : action === 'audit' ? await client.GET('/api/v1/identity/audit', { ...options, params: { query } }) : null;
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
