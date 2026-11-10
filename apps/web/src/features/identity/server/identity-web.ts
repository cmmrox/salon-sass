import 'server-only';
import { createHmac } from 'node:crypto';
import { createApiClient, type components } from '@salon/api-contracts/identity';
import { NextRequest, NextResponse } from 'next/server';
import {
  createServerApiClient,
  requestSignal,
  restartRequestBudget,
  UPLOAD_API_TIMEOUT_MS,
} from '@/lib/api-core/client';
import { apiTime } from '@/lib/api-core/meta';
import { currentRequest } from '@/lib/observability/request-context';
import { hasSlugForm, platformDomainOf, slugOf } from '@/lib/tenancy/hostname';
import { strictInstant } from '@/lib/http/network';
import { readBoundedJson } from '@/lib/http/bounded-json';
import type { Kind, Principal } from '../types';
import type { RouteTable } from '@/lib/api-core/routes';
import { UPLOAD_LIMIT_BYTES } from '@/lib/http/upload-limits';
import type { SalonAccountFor, SupportCredential } from './salon-account';

type Tokens = components['schemas']['Tokens'];
/** A client for the identity module's paths, for calls made for the salon with this hostname. */
export const identityClient = (hostname: string) => createServerApiClient(createApiClient, { hostname });
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
  return Boolean(
    host && request.headers.get('origin') === host.origin && request.headers.get('sec-fetch-site') !== 'cross-site',
  );
}
/** A support session's mutations (F03-S04): the exact salon origin, and same-origin Fetch Metadata only. */
export function supportCsrf(request: NextRequest): boolean {
  const site = request.headers.get('sec-fetch-site');
  return csrf(request) && site !== 'same-site';
}
/** The support credential's host-only HttpOnly cookies on the salon's host (F03-S04). */
const SUPPORT_COOKIES = ['access', 'session'] as const;
/**
 * A support view's reads of identity's own (F03-S04): the principal and what the Owner can read of the salon's sign-in
 * accounts and audit; a module's read is the support view's unless the module says otherwise. Its own devices are
 * refused 403.
 */
const IDENTITY_SUPPORT_READS = ['principal', 'staff', 'audit'];
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
export async function renewal(
  hostname: string,
  { access, refresh, expiry }: Credentials,
  status: number,
): Promise<'renewable' | 'spent' | 'unknown'> {
  if (!refresh || !(status === 401 || !access)) return 'spent';
  const time = await apiTime(hostname);
  if (!time) return 'unknown';
  return !access || (strictInstant(expiry) && Date.parse(expiry) <= Date.parse(time)) ? 'renewable' : 'spent';
}
function output(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'private, no-store' } });
}
function problem(code: string, status: number, extra: Record<string, unknown> = {}) {
  return output({ code, ...extra }, status);
}
function setCookie(response: NextResponse, kind: Kind, name: string, value: string, maxAge: number) {
  response.cookies.set(cookieName(kind, name), value, {
    httpOnly: true,
    secure: !localHttp(),
    sameSite: 'lax',
    path: '/',
    maxAge,
  });
}
export function clearCookies(response: NextResponse, kind: Kind) {
  const names =
    kind === 'support' ? SUPPORT_COOKIES : ['access', 'refresh', 'expires', 'session', 'challenge', 'challenge_id'];
  for (const name of names) setCookie(response, kind, name, '', 0);
}
/**
 * Left on the salon's host when a support view ends (BUG-F03-005): the support cookies may be
 * cleared by any refused request (a prefetch, a read) before the browser's next page, and that page
 * must still land on the "session ended" notice rather than the staff sign-in. It carries no
 * credential and authorizes nothing; it only chooses the notice, and the entry page forgets it.
 */
const SUPPORT_ENDED_COOKIE = 'salon_session_ended';
const SUPPORT_ENDED_SECONDS = 600;
export function supportEnded(request: { cookies: { get(name: string): { value: string } | undefined } }): boolean {
  return request.cookies.get(SUPPORT_ENDED_COOKIE)?.value === 'support';
}
/** A support session that ended: its cookies cleared host-only, the ended marker left behind. */
export function endSupportView(response: NextResponse) {
  clearCookies(response, 'support');
  response.cookies.set(SUPPORT_ENDED_COOKIE, 'support', {
    httpOnly: true,
    secure: !localHttp(),
    sameSite: 'lax',
    path: '/',
    maxAge: SUPPORT_ENDED_SECONDS,
  });
}
function forgetSupportEnded(response: NextResponse) {
  response.cookies.set(SUPPORT_ENDED_COOKIE, '', {
    httpOnly: true,
    secure: !localHttp(),
    sameSite: 'lax',
    path: '/',
    maxAge: 0,
  });
}
function issueSupport(response: NextResponse, credential: SupportCredential) {
  if (!strictInstant(credential.issuedAt) || !strictInstant(credential.expiresAt))
    throw new Error('Invalid credential expiry');
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
async function handleSupport(
  request: NextRequest,
  action: string,
  host: { hostname: string },
  salonAccountFor: SalonAccountFor,
  routes: RouteTable,
) {
  const route = routes.reads[action];
  const readable = IDENTITY_SUPPORT_READS.includes(action) || (route !== undefined && route.supportView !== false);
  if (request.method === 'GET' && !readable)
    return action === 'sessions' || route?.supportView === false
      ? problem('permission_denied', 403)
      : problem('method_not_allowed', 405);
  if (request.method !== 'GET' && request.method !== 'POST') return problem('method_not_allowed', 405);
  if (request.method === 'POST' && !supportCsrf(request)) return problem('request_rejected', 403);
  if (request.method === 'POST' && !['entry', 'end', 'clear'].includes(action))
    return problem('support_read_only', 403);
  const client = identityClient(host.hostname);
  const account = salonAccountFor(host.hostname);
  if (!client || !account) return problem('service_unavailable', 503);
  const access = request.cookies.get(cookieName('support', 'access'))?.value;
  const options = { headers: access ? { Authorization: `Bearer ${access}` } : {}, signal: requestSignal() };
  if (action === 'clear') {
    const response = output({ ok: true });
    clearCookies(response, 'support');
    forgetSupportEnded(response);
    return response;
  }
  if (action === 'entry') {
    const read = await readBoundedJson(request, 1024);
    if (!read.ok) return problem('bad_request', 400);
    const code = typeof read.value.code === 'string' ? read.value.code : '';
    const r = await account.enterSupport(requestSignal(), code);
    if (!r.data) return failure(r);
    const response = output({ ok: true, expiresAt: r.data.expiresAt });
    issueSupport(response, r.data);
    forgetSupportEnded(response);
    return response;
  }
  if (action === 'end') {
    const r = await account.endSupport(options);
    // Ended now, or already ended: either way the browser leaves the support view.
    const response = r.response.ok || r.response.status === 401 ? output({ ok: true }) : failure(r);
    if (r.response.ok || r.response.status === 401) endSupportView(response);
    return response;
  }
  const answer = await client.GET('/api/v1/identity/me', options);
  if (!answer.data || answer.data.kind !== 'support') {
    if (
      answer.response.status === 503 ||
      (answer.response.status !== 401 && answer.response.status !== 403 && !answer.data)
    )
      return problem('service_unavailable', 503);
    // The session ended (expiry, End session, Admin or Owner revocation): the browser leaves the support view.
    const response = problem('invalid_session', 401, { renewable: false });
    endSupportView(response);
    return response;
  }
  const principal = answer.data;
  const permission = route?.permission ?? IDENTITY_PERMISSIONS[action];
  if (permission && !principal.permissions.includes(permission)) return problem('permission_denied', 403);
  if (action === 'principal') return output(principal);
  const query = { page: Number(request.nextUrl.searchParams.get('page') ?? 0), size: 10 };
  const result = route
    ? await route.read(host.hostname, options, request.nextUrl.searchParams)
    : action === 'staff'
      ? await client.GET('/api/v1/identity/staff', { ...options, params: { query } })
      : await client.GET('/api/v1/identity/audit', { ...options, params: { query } });
  if (!result) return problem('service_unavailable', 503);
  if (result.response.status === 401) {
    const response = problem('invalid_session', 401, { renewable: false });
    endSupportView(response);
    return response;
  }
  return result.data
    ? output(result.data)
    : problem(result.error?.code ?? 'service_unavailable', result.response.status, { errors: result.error?.errors });
}
function issue(response: NextResponse, kind: Kind, tokens: Tokens) {
  if (!strictInstant(tokens.accessExpiresAt) || !strictInstant(tokens.refreshExpiresAt))
    throw new Error('Invalid credential expiry');
  // Both instants are API Clock-derived; never subtract the web machine clock.
  const lifetime = Math.max(
    1,
    Math.floor((Date.parse(tokens.refreshExpiresAt) - Date.parse(tokens.accessExpiresAt)) / 1000) + 300,
  );
  setCookie(response, kind, 'access', tokens.accessToken, 300);
  setCookie(response, kind, 'refresh', tokens.refreshToken, lifetime);
  setCookie(response, kind, 'expires', tokens.accessExpiresAt, lifetime);
  setCookie(response, kind, 'session', tokens.sessionId, lifetime);
  for (const name of ['challenge', 'challenge_id']) setCookie(response, kind, name, '', 0);
}

/**
 * The workspace BFF (`/identity-web/{kind}/{action}`): sign-in and the session's own actions here, every module's reads,
 * changes and uploads from the one route table (decision 121; web H4), each as its module declared it, and the
 * platform module's support entry and end for a support view, handed in by the route that serves it.
 */
export function createIdentityWeb(salonAccountFor: SalonAccountFor, routes: RouteTable) {
  const principalFirst = (method: string, action: string) => checksPrincipalFirst(method, action, routes);
  return (request: NextRequest, kind: Kind, action: string) =>
    answeredForSession(request, kind, action, principalFirst, () =>
      handleIdentity(request, kind, action, salonAccountFor, routes, principalFirst),
    );
}

/** The permission each of identity's own actions needs; a module's are in its routes. */
const IDENTITY_PERMISSIONS: Record<string, string> = {
  staff: 'staff.list',
  audit: 'audit.list',
  'staff-create': 'staff.manage',
  'staff-change': 'staff.manage',
  'staff-remove': 'staff.manage',
  password: 'password.change',
  'mfa-start': 'mfa.enroll',
};

/**
 * The identity module's own actions: signing in, the session and its devices, and the salon's sign-in accounts. They
 * keep the proxy's salon lookup.
 */
const IDENTITY_ACTIONS = new Set([
  'principal',
  'refresh',
  'clear',
  'sign-in',
  'mfa-start',
  'mfa-enroll',
  'mfa-verify',
  'password',
  'logout',
  'revoke',
  'revoke-all',
  'sessions',
  'staff',
  'audit',
  'staff-create',
  'staff-change',
  'staff-remove',
]);

/**
 * Whether the principal is read (`/identity/me`) and the session and permission checked here before the call (the
 * lead's ruling on web H3). A module's read is not: its endpoint authenticates the credential itself, refuses another
 * salon's (the API resolves the salon by `Host`) and requires the same permission (`SessionAccess.auth()` and each use
 * case's `principals.require`), so the refusal is the API's, 403 `permission_denied`. Every change keeps the re-check
 * before its call, as do the identity module's own actions, the reads whose endpoint answers anyone (`open`), and
 * uploads, whose form is read only after the session is known (BUG-F04-001).
 */
function checksPrincipalFirst(method: string, action: string, routes: RouteTable): boolean {
  return method !== 'GET' || IDENTITY_ACTIONS.has(action) || Boolean(routes.reads[action]?.open);
}

/**
 * A module's read answered from the API's own refusals, now that no principal is read first: a credential the API
 * refused is `invalid_session` with whether the refresh credential may renew it, as the principal read said before;
 * a suspended salon is unavailable, as the proxy's lookup said before.
 */
async function answeredForSession(
  request: NextRequest,
  kind: Kind,
  action: string,
  principalFirst: (method: string, action: string) => boolean,
  handle: () => Promise<NextResponse>,
): Promise<NextResponse> {
  const response = await handle();
  if (kind !== 'staff' || principalFirst(request.method, action)) return response;
  if (response.status !== 401 && response.status !== 403) return response;
  const body = (await response
    .clone()
    .json()
    .catch(() => ({}))) as { code?: string; renewable?: boolean };
  if (response.status === 403 && body.code === 'salon_suspended') return problem('service_unavailable', 503);
  if (response.status !== 401 || body.code !== 'invalid_session' || body.renewable !== undefined) return response;
  const host = requestHost(request.headers.get('host'));
  if (!host) return response;
  const credentials = {
    access: request.cookies.get(cookieName(kind, 'access'))?.value,
    refresh: request.cookies.get(cookieName(kind, 'refresh'))?.value,
    expiry: request.cookies.get(cookieName(kind, 'expires'))?.value,
  };
  const renewable = (await renewal(host.hostname, credentials, 401).catch(() => 'unknown')) === 'renewable';
  return problem('invalid_session', 401, { renewable });
}

/**
 * Whether a request to the workspace BFF is answered without the proxy's salon lookup: a module's action. The
 * handler checks the host's form itself and reads the principal, and the API resolves (and refuses) the salon by
 * `Host` on every call; a suspended salon is answered as the lookup answered it, unavailable.
 */
export function answersWithoutLookup(path: string): boolean {
  const match = /^\/identity-web\/(?:staff|support)\/([a-z0-9-]+)$/.exec(path);
  return Boolean(match && !IDENTITY_ACTIONS.has(match[1]!) && match[1] !== 'entry' && match[1] !== 'end');
}

/** Identity's own reads; a module's are in the route table. */
const IDENTITY_READS = ['principal', 'sessions', 'staff', 'audit'];

/** Allowlisted account operations only; neither a URL nor a tenant selector comes from input. */
async function handleIdentity(
  request: NextRequest,
  kind: Kind,
  action: string,
  salonAccountFor: SalonAccountFor,
  routes: RouteTable,
  principalFirst: (method: string, action: string) => boolean,
) {
  try {
    const host = requestHost(request.headers.get('host'));
    if (!host) return problem('not_found', 404);
    if (kind === 'support') return await handleSupport(request, action, host, salonAccountFor, routes);
    const read = routes.reads[action];
    const change = routes.changes[action];
    const upload = routes.uploads[action];
    if (request.method === 'GET' && !IDENTITY_READS.includes(action) && !read)
      return problem('method_not_allowed', 405);
    if (request.method !== 'GET' && !csrf(request)) return problem('request_rejected', 403);
    const client = identityClient(host.hostname);
    if (!client) return problem('service_unavailable', 503);
    const access = request.cookies.get(cookieName(kind, 'access'))?.value;
    const refresh = request.cookies.get(cookieName(kind, 'refresh'))?.value;
    const headers: Record<string, string> = access ? { Authorization: `Bearer ${access}` } : {};
    // An upload's form is read only after the session and permission checks, so no caller who is refused has a form
    // parsed.
    const body =
      request.method === 'GET' || upload ? { ok: true as const, value: {} } : await readBoundedJson(request, 16384);
    // The request's budget, which the request log starts again once the body has arrived (BUG-F04-001).
    const signal = requestSignal();
    if (!body.ok) return problem('bad_request', 400);
    const input: Record<string, unknown> = body.value;
    // Input values are forwarded only through each generated contract's explicit fields.
    const text = (name: string) => (typeof input[name] === 'string' ? (input[name] as string) : '');
    const query = { page: Number(request.nextUrl.searchParams.get('page') ?? 0), size: 10 };
    const options = { headers, signal };
    const publicActions = ['sign-in', 'mfa-enroll', 'mfa-verify'];
    let principal: Principal | undefined;
    const checked = principalFirst(request.method, action);
    // A module's read without an access credential is refused here, without asking the API.
    if (!checked && !access) return problem('invalid_session', 401);
    if (checked && !publicActions.includes(action) && action !== 'clear') {
      const answer = await client.GET('/api/v1/identity/me', options);
      principal = answer.data;
      if (!principal || principal.kind !== kind) {
        const expiry = request.cookies.get(cookieName(kind, 'expires'))?.value;
        const renewable =
          (await renewal(host.hostname, { access, refresh, expiry }, answer.response.status)) === 'renewable';
        if (action === 'refresh' && renewable) {
          const rotated = await client.POST('/api/v1/identity/sessions/refresh', {
            body: { refreshToken: refresh! },
            signal: requestSignal(),
          });
          const verified = rotated.data
            ? await client.GET('/api/v1/identity/me', {
                headers: { Authorization: `Bearer ${rotated.data.accessToken}` },
                signal: requestSignal(),
              })
            : null;
          const valid = Boolean(rotated.data && verified?.data?.kind === kind);
          const response = valid ? output({ ok: true }) : problem('invalid_session', 401);
          if (valid && rotated.data) issue(response, kind, rotated.data);
          else clearCookies(response, kind);
          return response;
        }
        if (action === 'refresh') {
          const response = problem('invalid_session', 401);
          clearCookies(response, kind);
          return response;
        }
        if (answer.response.status === 503) return problem('service_unavailable', 503);
        // A suspended salon, which the proxy's lookup no longer answers for a module's call: unavailable, as before.
        if (answer.error?.code === 'salon_suspended') return problem('service_unavailable', 503);
        return problem('invalid_session', 401, { renewable });
      }
    }
    const permission = (read ?? change ?? upload)?.permission ?? IDENTITY_PERMISSIONS[action];
    if (principal && permission && !principal.permissions.includes(permission))
      return problem('permission_denied', 403);
    if (action === 'principal') return output(principal);
    if (action === 'refresh') return output({ ok: true }); // A competing tab already renewed.
    if (action === 'clear') {
      const response = output({ ok: true });
      clearCookies(response, kind);
      return response;
    }
    if (request.method === 'GET') {
      const result = read
        ? await read.read(host.hostname, options, request.nextUrl.searchParams)
        : action === 'sessions'
          ? await client.GET('/api/v1/identity/sessions', { ...options, params: { query } })
          : action === 'staff'
            ? await client.GET('/api/v1/identity/staff', { ...options, params: { query } })
            : await client.GET('/api/v1/identity/audit', { ...options, params: { query } });
      if (!result) return problem('service_unavailable', 503);
      return result.data
        ? output(result.data)
        : problem(result.error?.code ?? 'service_unavailable', result.response.status, {
            errors: result.error?.errors,
          });
    }
    if (request.method !== 'POST') return problem('method_not_allowed', 405);
    if (action === 'sign-in') {
      const ip = currentRequest()?.clientAddress;
      const time = await apiTime(host.hostname);
      if (!ip || !time) return problem('service_unavailable', 503);
      const path = '/api/v1/identity/staff/sign-in';
      headers['x-salon-client-assertion'] = assertion(host.hostname, path, ip, time);
    }
    const challengeId = request.cookies.get(cookieName(kind, 'challenge_id'))?.value ?? '';
    const challengeToken = request.cookies.get(cookieName(kind, 'challenge'))?.value ?? '';
    switch (action) {
      case 'sign-in': {
        const r = await client.POST('/api/v1/identity/staff/sign-in', {
          ...options,
          body: { identifier: text('identifier'), password: text('password'), deviceLabel: text('deviceLabel') },
        });
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
        const r = await client.POST('/api/v1/identity/staff/mfa/enroll', {
          ...options,
          body: { challengeId, challengeToken },
        });
        return r.data
          ? output({ secret: r.data.secret, otpauthUri: r.data.otpauthUri, expiresAt: r.data.expiresAt })
          : failure(r);
      }
      case 'mfa-verify': {
        const r = await client.POST('/api/v1/identity/staff/mfa/verify', {
          ...options,
          body: { challengeId, challengeToken, code: text('code') },
        });
        const response = r.data ? output({ ok: true }) : failure(r);
        if (r.data) issue(response, kind, r.data);
        return response;
      }
      case 'password': {
        const r = await client.PUT('/api/v1/identity/staff/password', {
          ...options,
          body: { oldPassword: text('oldPassword'), newPassword: text('newPassword') },
        });
        const response = r.data ? output({ ok: true }) : failure(r);
        if (r.data) issue(response, kind, r.data);
        return response;
      }
      case 'logout':
      case 'revoke':
      case 'revoke-all': {
        const id = action === 'logout' ? (request.cookies.get(cookieName(kind, 'session'))?.value ?? '') : text('id');
        const r =
          action === 'revoke-all'
            ? await client.DELETE('/api/v1/identity/sessions', options)
            : await client.DELETE('/api/v1/identity/sessions/{id}', { ...options, params: { path: { id } } });
        const response = r.response.ok ? output({ ok: true }) : failure(r);
        if (r.response.ok && (action !== 'revoke' || id === request.cookies.get(cookieName(kind, 'session'))?.value))
          clearCookies(response, kind);
        return response;
      }
      case 'staff-create':
      case 'staff-change':
      case 'staff-remove': {
        const r =
          action === 'staff-create'
            ? await client.POST('/api/v1/identity/staff', {
                ...options,
                body: {
                  email: text('email'),
                  mobile: text('mobile'),
                  password: text('password'),
                  role: text('role'),
                  professionalGrant: input.professionalGrant as boolean,
                },
              })
            : action === 'staff-change'
              ? await client.PATCH('/api/v1/identity/staff/{id}', {
                  ...options,
                  params: { path: { id: text('id') } },
                  body: {
                    version: input.version as number,
                    role: text('role'),
                    professionalGrant: input.professionalGrant as boolean,
                  },
                })
              : await client.DELETE('/api/v1/identity/staff/{id}', {
                  ...options,
                  params: { path: { id: text('id') } },
                });
        return r.response.ok ? output(r.data ?? { ok: true }) : failure(r);
      }
      default: {
        if (upload) {
          const form = await uploadForm(request, upload.field);
          if ('refused' in form) return problem(form.refused, form.status, { errors: form.errors });
          // The upload's own stated limit, from the end of reading its form (docs/engineering/web.md, "Structure";
          // BUG-F04-001).
          restartRequestBudget(UPLOAD_API_TIMEOUT_MS);
          const r = await upload.upload(host.hostname, { headers, signal: requestSignal() }, form.text, form.file);
          if (!r) return problem('service_unavailable', 503);
          return r.data ? output(r.data, upload.created ? 201 : 200) : failure(r);
        }
        if (!change) return problem('not_found', 404);
        // A module's change: its answer is allowlisted by its contract and its request rebuilt by its module. A
        // creation answers 201, a removal no content.
        const r = await change.change(host.hostname, options, input);
        if (!r) return problem('service_unavailable', 503);
        if (r.data) return output(r.data, r.response.status === 201 ? 201 : 200);
        return r.response.ok ? output({ ok: true }) : failure(r);
      }
    }
  } catch {
    const response = problem('service_unavailable', 503);
    // Unknown rotation outcome is never retried: prevent restoring the spent family.
    if (action === 'refresh') clearCookies(response, kind);
    return response;
  }
}
/** The browser's request over its declared size is refused before its form is read (the API's 10 MB limit). */
const UPLOAD_LIMIT = UPLOAD_LIMIT_BYTES;

/**
 * The request's body, read through a byte count that stops past `limit`: null when it is larger. The whole
 * body is never buffered beyond the limit, whether or not the request declared its length (a chunked request
 * declares none). Next.js's own proxy buffer (`experimental.proxyClientMaxBodySize`) truncates rather than
 * refuses, so it is set above this limit (`PROXY_BODY_LIMIT_BYTES`, next.config.ts): a body over the limit
 * still arrives longer than the limit, and is refused here.
 */
async function boundedBody(request: NextRequest, limit: number): Promise<Uint8Array<ArrayBuffer> | null> {
  const reader = request.body?.getReader();
  if (!reader) return new Uint8Array(new ArrayBuffer(0));
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const body = new Uint8Array(new ArrayBuffer(total));
  let at = 0;
  for (const chunk of chunks) {
    body.set(chunk, at);
    at += chunk.byteLength;
  }
  return body;
}

/**
 * An upload's two fields, read from the browser's multipart form: the file and one text field, `kind` for a brand
 * image (F04-S02) or `serviceId` for a service photo (F05-S01).
 */
async function uploadForm(
  request: NextRequest,
  field: 'kind' | 'serviceId' | 'memberId',
): Promise<{ text: string; file: Blob } | { refused: string; status: number; errors?: unknown }> {
  const declared = request.headers.get('content-length');
  if (declared !== null && Number(declared) > UPLOAD_LIMIT) return { refused: 'request_rejected', status: 413 };
  const body = await boundedBody(request, UPLOAD_LIMIT);
  if (body === null) return { refused: 'request_rejected', status: 413 };
  let form: FormData;
  try {
    form = await new Response(body, {
      headers: { 'Content-Type': request.headers.get('content-type') ?? '' },
    }).formData();
  } catch {
    return { refused: 'bad_request', status: 400, errors: [{ field: 'file', detail: 'is required' }] };
  }
  const text = form.get(field);
  const file = form.get('file');
  if (!(file instanceof Blob))
    return { refused: 'bad_request', status: 400, errors: [{ field: 'file', detail: 'is required' }] };
  return { text: typeof text === 'string' ? text : '', file };
}
function failure(r: { error?: { code?: string; errors?: unknown }; response: Response }) {
  const retry = r.response.headers.get('retry-after');
  return problem(r.error?.code ?? 'service_unavailable', r.response.status, {
    errors: r.error?.errors,
    ...(retry ? { retryAfter: Number(retry) } : {}),
  });
}
