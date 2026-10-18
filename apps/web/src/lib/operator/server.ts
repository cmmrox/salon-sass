import { createHmac } from 'node:crypto';
import type { ApiClient, components } from '@salon/api-contracts';
import { NextRequest, NextResponse } from 'next/server';
import { createServerApiClient, requestSignal } from '@/lib/api/client';
import { strictInstant } from '@/lib/identity/network';
import { currentRequest } from '@/lib/observability/request-context';
import { consoleRequestHost } from './host';
import { readBoundedJson } from '@/lib/http/bounded-json';

export type OperatorPrincipal = components['schemas']['OperatorPrincipal'];
type Tokens = components['schemas']['OperatorTokens'];

/**
 * The operator console's server BFF (docs/architecture/operator-console.md): `/operator-web/{action}`
 * on the exact console host only, an allowlist of actions with fixed upstream paths, operator
 * authority held only in host-only HttpOnly `salon_operator_*` cookies, exact Origin and Fetch
 * Metadata checks on every mutation (sign-in included), and only nonsecret fields in what it returns.
 * Tokens and challenge credentials never reach client components, storage, HTML, URLs or logs.
 */
export const OPERATOR_COOKIES = ['access', 'refresh', 'expires', 'session', 'challenge', 'challenge_id'] as const;
export const operatorCookie = (name: (typeof OPERATOR_COOKIES)[number]) => `salon_operator_${name}`;
const localHttp = () => process.env.SALON_WEB_LOCAL_HTTP === 'true';
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const SIGN_IN_PATH = '/api/v1/operator/sign-in';

/** Every mutation: the exact console origin and no cross-site Fetch Metadata. */
export function operatorCsrf(request: NextRequest): boolean {
  const host = consoleRequestHost(request.headers.get('host'));
  const site = request.headers.get('sec-fetch-site');
  return Boolean(host && request.headers.get('origin') === host.origin && site !== 'cross-site' && site !== 'same-site');
}

/** The console's own proxy key, shared with the API and independent of every identity key. */
export function operatorProxyKey(): Buffer {
  const value = process.env.SALON_OPERATOR_PROXY_KEY;
  if (!value || !/^[a-fA-F0-9]{64}$/.test(value)) throw new Error('Configure SALON_OPERATOR_PROXY_KEY');
  return Buffer.from(value, 'hex');
}

/** The rate-address assertion for operator sign-in, bound to the console host and the sign-in path. */
export function operatorAssertion(host: string, ip: string, time: string): string {
  const proof = ['v1', host, 'POST', SIGN_IN_PATH, ip, time].join('\n');
  return `v1;${ip};${time};${createHmac('sha256', operatorProxyKey()).update(proof).digest('hex')}`;
}

export type OperatorCredentials = { access?: string; refresh?: string; expiry?: string };

export function credentialsOf(request: { cookies: { get(name: string): { value: string } | undefined } }): OperatorCredentials {
  return {
    access: request.cookies.get(operatorCookie('access'))?.value,
    refresh: request.cookies.get(operatorCookie('refresh'))?.value,
    expiry: request.cookies.get(operatorCookie('expires'))?.value,
  };
}

/**
 * Whether the refresh family may renew: only when the access credential is gone or answered 401 and,
 * when present, its recorded expiry has passed by the API's clock. Expiry metadata never authorizes.
 */
export async function operatorRenewal(client: ApiClient, { access, refresh, expiry }: OperatorCredentials, status: number): Promise<'renewable' | 'spent' | 'unknown'> {
  if (!refresh || !(status === 401 || !access)) return 'spent';
  const meta = await client.GET('/api/v1/meta', { signal: requestSignal() });
  if (!meta.data || !strictInstant(meta.data.time)) return 'unknown';
  return !access || (strictInstant(expiry) && Date.parse(expiry) <= Date.parse(meta.data.time)) ? 'renewable' : 'spent';
}

function output(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'private, no-store' } });
}
function problem(code: string, status: number, extra: Record<string, unknown> = {}) {
  return output({ code, ...extra }, status);
}
/** An export archive passed through as a download: its name from the API, never cached. */
export function archive(bytes: ArrayBuffer, upstream: Response) {
  const disposition = upstream.headers.get('content-disposition');
  const name = /filename="?([A-Za-z0-9._-]+)"?/.exec(disposition ?? '')?.[1] ?? 'salon-export.zip';
  return new NextResponse(bytes, {
    status: 200,
    headers: {
      'Content-Type': 'application/zip',
      'Content-Disposition': `attachment; filename="${name}"`,
      'Cache-Control': 'private, no-store',
      ...(upstream.headers.get('x-content-sha256') ? { 'X-Content-SHA256': upstream.headers.get('x-content-sha256')! } : {}),
    },
  });
}
function setCookie(response: NextResponse, name: (typeof OPERATOR_COOKIES)[number], value: string, maxAge: number) {
  response.cookies.set(operatorCookie(name), value, { httpOnly: true, secure: !localHttp(), sameSite: 'lax', path: '/', maxAge });
}
export function clearOperatorCookies(response: NextResponse) {
  for (const name of OPERATOR_COOKIES) setCookie(response, name, '', 0);
}
function issue(response: NextResponse, tokens: Tokens) {
  if (!strictInstant(tokens.accessExpiresAt) || !strictInstant(tokens.refreshExpiresAt)) throw new Error('Invalid credential expiry');
  // Both instants come from the API's clock; the web machine's clock is never subtracted.
  const lifetime = Math.max(1, Math.floor((Date.parse(tokens.refreshExpiresAt) - Date.parse(tokens.accessExpiresAt)) / 1000) + 300);
  setCookie(response, 'access', tokens.accessToken, 300);
  setCookie(response, 'refresh', tokens.refreshToken, lifetime);
  setCookie(response, 'expires', tokens.accessExpiresAt, lifetime);
  setCookie(response, 'session', tokens.sessionId, lifetime);
  setCookie(response, 'challenge', '', 0);
  setCookie(response, 'challenge_id', '', 0);
}
function failure(r: { error?: { code?: string; errors?: { field?: string }[] }; response: Response }) {
  const retry = r.response.headers.get('retry-after');
  return problem(r.error?.code ?? 'service_unavailable', r.response.status, {
    errors: (r.error?.errors ?? []).map((e) => ({ field: e.field })),
    ...(retry ? { retryAfter: Number(retry) } : {}),
  });
}

const READS = new Set(['principal', 'plans', 'plan', 'salons', 'salon', 'subscription', 'invoices', 'invoice', 'lifecycle', 'export', 'export-archive', 'support-sessions']);
const PUBLIC = new Set(['sign-in', 'mfa-enroll', 'mfa-verify']);
const MUTATIONS = new Set(['sign-in', 'mfa-enroll', 'mfa-verify', 'refresh', 'sign-out', 'clear', 'plan-create', 'salon-create',
  'subscription-start', 'subscription-change', 'plan-move', 'payment-record', 'salon-suspend', 'salon-reactivate', 'salon-close',
  'support-start', 'support-entry-code', 'support-end']);

/** One allowlisted console operation; neither an upstream URL nor a tenant selector comes from input. */
export async function handleOperator(request: NextRequest, action: string): Promise<NextResponse> {
  try {
    const host = consoleRequestHost(request.headers.get('host'));
    if (!host) return problem('not_found', 404);
    if (!READS.has(action) && !MUTATIONS.has(action)) return problem('not_found', 404);
    if (request.method === 'GET' && !READS.has(action)) return problem('method_not_allowed', 405);
    if (request.method !== 'GET' && (request.method !== 'POST' || !MUTATIONS.has(action))) return problem('method_not_allowed', 405);
    if (request.method !== 'GET' && !operatorCsrf(request)) return problem('request_rejected', 403);
    const client = createServerApiClient({ hostname: host.hostname });
    if (!client) return problem('service_unavailable', 503);
    const credentials = credentialsOf(request);
    const headers: Record<string, string> = credentials.access ? { Authorization: `Bearer ${credentials.access}` } : {};
    const options = { headers, signal: requestSignal() };
    const read = request.method === 'GET' ? { ok: true as const, value: {} } : await readBoundedJson(request, 16384);
    if (!read.ok) return problem('bad_request', 400);
    const body: Record<string, unknown> = read.value;
    const text = (name: string) => (typeof body[name] === 'string' ? (body[name] as string) : '');
    const query = request.nextUrl.searchParams;

    if (action === 'clear') {
      const response = output({ ok: true });
      clearOperatorCookies(response);
      return response;
    }

    if (!PUBLIC.has(action)) {
      const answer = await client.GET('/api/v1/operator/me', options);
      if (!answer.data) {
        const renewable = (await operatorRenewal(client, credentials, answer.response.status)) === 'renewable';
        if (action === 'refresh') {
          if (!renewable) {
            const response = problem('invalid_session', 401);
            clearOperatorCookies(response);
            return response;
          }
          const rotated = await client.POST('/api/v1/operator/sessions/refresh', { body: { refreshToken: credentials.refresh! }, signal: requestSignal() });
          const verified = rotated.data ? await client.GET('/api/v1/operator/me', { headers: { Authorization: `Bearer ${rotated.data.accessToken}` }, signal: requestSignal() }) : null;
          const valid = Boolean(rotated.data && verified?.data?.kind === 'operator');
          const response = valid ? output({ ok: true }) : problem('invalid_session', 401);
          if (valid && rotated.data) issue(response, rotated.data);
          else clearOperatorCookies(response);
          return response;
        }
        if (answer.response.status === 401) return problem('invalid_session', 401, { renewable });
        return answer.response.status === 403 ? problem('permission_denied', 403) : problem('service_unavailable', 503);
      }
      if (action === 'principal') {
        const p = answer.data;
        return output({ id: p.id, kind: p.kind, role: p.role, permissions: p.permissions, mfaEnabled: p.mfaEnabled });
      }
      // A competing tab already renewed.
      if (action === 'refresh') return output({ ok: true });
    }

    switch (action) {
      case 'plans':
      case 'salons': {
        // Forwarded as numbers; anything else arrives as NaN, which the API refuses with a field error.
        const params = { query: { page: Number(query.get('page') ?? 0), size: Number(query.get('size') ?? 20), ...(query.get('q') ? { q: query.get('q')! } : {}) } };
        if (action === 'plans') {
          const r = await client.GET('/api/v1/operator/plans', { ...options, params });
          return r.data ? output(r.data) : failure(r);
        }
        const r = await client.GET('/api/v1/operator/salons', { ...options, params });
        return r.data ? output(r.data) : failure(r);
      }
      case 'plan':
      case 'salon': {
        const id = query.get('id') ?? '';
        if (!UUID.test(id)) return problem('bad_request', 400, { errors: [{ field: 'id' }] });
        const r = action === 'plan'
          ? await client.GET('/api/v1/operator/plans/{id}', { ...options, params: { path: { id } } })
          : await client.GET('/api/v1/operator/salons/{id}', { ...options, params: { path: { id } } });
        return r.data ? output(r.data) : failure(r);
      }
      case 'subscription':
      case 'invoices':
      case 'invoice': {
        const id = query.get('id') ?? '';
        if (!UUID.test(id)) return problem('bad_request', 400, { errors: [{ field: 'id' }] });
        if (action === 'subscription') {
          const r = await client.GET('/api/v1/operator/salons/{id}/subscription', { ...options, params: { path: { id } } });
          return r.data ? output(r.data) : failure(r);
        }
        if (action === 'invoice') {
          const r = await client.GET('/api/v1/operator/invoices/{id}', { ...options, params: { path: { id } } });
          return r.data ? output(r.data) : failure(r);
        }
        const r = await client.GET('/api/v1/operator/salons/{id}/invoices', {
          ...options,
          params: { path: { id }, query: { page: Number(query.get('page') ?? 0), size: Number(query.get('size') ?? 20) } },
        });
        return r.data ? output(r.data) : failure(r);
      }
      case 'lifecycle':
      case 'export':
      case 'export-archive': {
        const id = query.get('id') ?? '';
        if (!UUID.test(id)) return problem('bad_request', 400, { errors: [{ field: 'id' }] });
        if (action === 'lifecycle') {
          const r = await client.GET('/api/v1/operator/salons/{id}/lifecycle', {
            ...options,
            params: { path: { id }, query: { page: Number(query.get('page') ?? 0), size: Number(query.get('size') ?? 20) } },
          });
          return r.data ? output(r.data) : failure(r);
        }
        if (action === 'export') {
          const r = await client.GET('/api/v1/operator/salons/{id}/export', { ...options, params: { path: { id } } });
          return r.data ? output(r.data) : failure(r);
        }
        const r = await client.GET('/api/v1/operator/salons/{id}/export/archive', { ...options, params: { path: { id } }, parseAs: 'arrayBuffer' });
        return r.data ? archive(r.data as ArrayBuffer, r.response) : failure(r as unknown as Parameters<typeof failure>[0]);
      }
      case 'support-sessions': {
        // Forwarded as typed; the API names a bad page, size, salon or state as a field error.
        const salonId = query.get('salonId');
        const state = query.get('state');
        const r = await client.GET('/api/v1/operator/support-sessions', {
          ...options,
          params: { query: { page: Number(query.get('page') ?? 0), size: Number(query.get('size') ?? 20), ...(salonId ? { salonId } : {}), ...(state ? { state: state as 'active' | 'ended' } : {}) } },
        });
        return r.data ? output(r.data) : failure(r);
      }
      case 'support-start':
      case 'support-entry-code':
      case 'support-end': {
        const id = text('id');
        if (!UUID.test(id)) return problem('bad_request', 400, { errors: [{ field: 'id' }] });
        if (action === 'support-start') {
          const r = await client.POST('/api/v1/operator/salons/{id}/support-sessions', {
            ...options, params: { path: { id } }, body: { reason: text('reason'), durationMinutes: body.durationMinutes as number },
          });
          // The entry code is meant for this operator's browser: it opens the salon's workspace once.
          return r.data ? output({ id: r.data.id, expiresAt: r.data.expiresAt, entryUrl: r.data.entryUrl, codeExpiresAt: r.data.codeExpiresAt }, 201) : failure(r);
        }
        if (action === 'support-entry-code') {
          const r = await client.POST('/api/v1/operator/support-sessions/{id}/entry-codes', { ...options, params: { path: { id } } });
          return r.data ? output({ id: r.data.id, expiresAt: r.data.expiresAt, entryUrl: r.data.entryUrl, codeExpiresAt: r.data.codeExpiresAt }, 201) : failure(r);
        }
        const r = await client.DELETE('/api/v1/operator/support-sessions/{id}', { ...options, params: { path: { id } } });
        return r.response.ok ? output({ ok: true }) : failure(r);
      }
      case 'salon-suspend':
      case 'salon-reactivate':
      case 'salon-close': {
        const id = text('id');
        if (!UUID.test(id)) return problem('bad_request', 400, { errors: [{ field: 'id' }] });
        const params = { path: { id } };
        const version = body.version as number;
        if (action === 'salon-close') {
          const r = await client.POST('/api/v1/operator/salons/{id}/closure', { ...options, params, body: { version, confirmSlug: text('confirmSlug'), reason: text('reason') } });
          return r.data ? output(r.data) : failure(r);
        }
        const r = action === 'salon-suspend'
          ? await client.POST('/api/v1/operator/salons/{id}/suspension', { ...options, params, body: { version, reason: text('reason') } })
          : await client.DELETE('/api/v1/operator/salons/{id}/suspension', { ...options, params, body: { version, reason: text('reason') } });
        return r.data ? output(r.data) : failure(r);
      }
      case 'subscription-start':
      case 'subscription-change':
      case 'plan-move':
      case 'payment-record': {
        const id = text('id');
        if (!UUID.test(id)) return problem('bad_request', 400, { errors: [{ field: 'id' }] });
        // Amounts arrive as minor units, or as the operator's text when it was not a number, which the
        // API refuses with a field error; nothing is computed here.
        const amount = (name: string) => body[name] as number;
        if (action === 'subscription-change') {
          const r = await client.PATCH('/api/v1/operator/salons/{id}/subscription', {
            ...options,
            params: { path: { id } },
            body: {
              version: body.version as number,
              ...(body.monthlyAmount !== undefined ? { monthlyAmount: amount('monthlyAmount') } : {}),
              ...(text('paymentMethod') ? { paymentMethod: text('paymentMethod') as 'card' | 'bank_transfer' } : {}),
            },
          });
          return r.data ? output(r.data) : failure(r);
        }
        if (action === 'plan-move') {
          const r = await client.PUT('/api/v1/operator/salons/{id}/plan', { ...options, params: { path: { id } }, body: { version: body.version as number, planId: text('planId') } });
          return r.data ? output(r.data) : failure(r);
        }
        const key = text('idempotencyKey');
        if (!UUID.test(key)) return problem('bad_request', 400, { errors: [{ field: 'Idempotency-Key' }] });
        const idempotent = { headers, signal: requestSignal() };
        if (action === 'subscription-start') {
          const r = await client.POST('/api/v1/operator/salons/{id}/subscription', {
            ...idempotent,
            params: { path: { id }, header: { 'Idempotency-Key': key } },
            body: { monthlyAmount: amount('monthlyAmount'), paymentMethod: text('paymentMethod') as 'card' | 'bank_transfer', ...(text('currency') ? { currency: text('currency') } : {}) },
          });
          return r.data ? output(r.data, 201) : failure(r);
        }
        const r = await client.POST('/api/v1/operator/invoices/{id}/payments', {
          ...idempotent,
          params: { path: { id }, header: { 'Idempotency-Key': key } },
          body: { reference: text('reference'), amount: amount('amount'), receivedOn: text('receivedOn') },
        });
        return r.data ? output(r.data, 201) : failure(r);
      }
      case 'sign-in': {
        const ip = currentRequest()?.clientAddress;
        const meta = await client.GET('/api/v1/meta', { signal: requestSignal() });
        if (!ip || !meta.data || !strictInstant(meta.data.time)) return problem('service_unavailable', 503);
        const r = await client.POST('/api/v1/operator/sign-in', {
          headers: { 'x-salon-client-assertion': operatorAssertion(host.hostname, ip, meta.data.time) },
          signal: requestSignal(),
          body: { email: text('email'), password: text('password'), deviceLabel: text('deviceLabel') },
        });
        if (!r.data) return failure(r);
        const response = output({ state: r.data.state, expiresAt: r.data.expiresAt });
        setCookie(response, 'challenge_id', r.data.challengeId ?? '', 300);
        setCookie(response, 'challenge', r.data.challengeToken ?? '', 300);
        return response;
      }
      case 'mfa-enroll':
      case 'mfa-verify': {
        const challengeId = request.cookies.get(operatorCookie('challenge_id'))?.value ?? '';
        const challengeToken = request.cookies.get(operatorCookie('challenge'))?.value ?? '';
        if (!UUID.test(challengeId) || !challengeToken) return problem('invalid_credentials', 401);
        if (action === 'mfa-enroll') {
          const r = await client.POST('/api/v1/operator/mfa/challenges', { signal: requestSignal(), body: { challengeId, challengeToken } });
          // The documented transient setup display: the secret and its URI, shown once to be copied.
          return r.data ? output({ secret: r.data.secret, otpauthUri: r.data.otpauthUri, expiresAt: r.data.expiresAt }) : failure(r);
        }
        const r = await client.POST('/api/v1/operator/mfa/verify', { signal: requestSignal(), body: { challengeId, challengeToken, code: text('code') } });
        const response = r.data ? output({ ok: true }) : failure(r);
        if (r.data) issue(response, r.data);
        return response;
      }
      case 'sign-out': {
        const r = await client.POST('/api/v1/operator/sessions/sign-out', options);
        const response = r.response.ok ? output({ ok: true }) : failure(r);
        if (r.response.ok || r.response.status === 401) clearOperatorCookies(response);
        return response;
      }
      case 'plan-create':
      case 'salon-create': {
        const key = text('idempotencyKey');
        if (!UUID.test(key)) return problem('bad_request', 400, { errors: [{ field: 'Idempotency-Key' }] });
        const idempotent = { headers, params: { header: { 'Idempotency-Key': key } }, signal: requestSignal() };
        if (action === 'plan-create') {
          const r = await client.POST('/api/v1/operator/plans', {
            ...idempotent,
            body: { key: text('key'), name: text('name'), staffSeats: body.staffSeats as number, branches: body.branches as number, messageAllowance: body.messageAllowance as number, customDomain: body.customDomain as boolean, dedicatedApp: body.dedicatedApp as boolean },
          });
          return r.data ? output(r.data, 201) : failure(r);
        }
        const owner = (typeof body.owner === 'object' && body.owner !== null ? body.owner : {}) as Record<string, unknown>;
        const subscription = (typeof body.subscription === 'object' && body.subscription !== null ? body.subscription : {}) as Record<string, unknown>;
        const ownerText = (name: string) => (typeof owner[name] === 'string' ? (owner[name] as string) : '');
        const optional = (name: string) => (text(name).trim() === '' ? null : text(name));
        const r = await client.POST('/api/v1/operator/salons', {
          ...idempotent,
          body: {
            name: text('name'), slug: text('slug'), country: text('country'), currency: text('currency'), timeZone: text('timeZone'),
            defaultLanguage: text('defaultLanguage') as 'en' | 'si' | 'ta', contactEmail: optional('contactEmail'), contactPhone: optional('contactPhone'), address: optional('address'),
            planId: text('planId'), owner: { name: ownerText('name'), email: ownerText('email'), password: ownerText('password') },
            subscription: { monthlyAmount: subscription.monthlyAmount as number, paymentMethod: (typeof subscription.paymentMethod === 'string' ? subscription.paymentMethod : '') as 'card' | 'bank_transfer' },
          },
        });
        return r.data ? output({ id: r.data.id, slug: r.data.slug, hostname: r.data.hostname, planId: r.data.planId, createdAt: r.data.createdAt }, 201) : failure(r);
      }
      default:
        return problem('not_found', 404);
    }
  } catch {
    const response = problem('service_unavailable', 503);
    // An unknown rotation outcome is never retried: a spent family is never restored.
    if (action === 'refresh') clearOperatorCookies(response);
    return response;
  }
}
