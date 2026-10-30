import 'server-only';
import { NextRequest, NextResponse } from 'next/server';
import { requestSignal } from '@/lib/api-core/client';
import { readBoundedJson } from '@/lib/http/bounded-json';
import { classifyHostname, platformDomainOf } from '@/lib/tenancy/hostname';
import { platformClient } from './client';
import { archive } from './operator-web';

/**
 * The platform host's BFF (F03-S03): `/platform-web/{action}` on the bare platform domain only, for the
 * Owner's export download page. The token comes in the JSON body of a POST (the page reads it from
 * the URL fragment), so it never appears in a URL, a request log line or a cache; the BFF forwards it
 * to the API's platform-host download routes and returns only allowlisted fields or the archive. Exact
 * Origin and Fetch Metadata checks, as for every BFF mutation.
 */
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const ACTIONS = new Set(['export-info', 'export-download']);

/** The bare platform host of a request and its exact origin, or null when the request is not on it. */
function platformRequestHost(raw: string | null): { hostname: string; origin: string } | null {
  let domain: string;
  try {
    domain = platformDomainOf(process.env.SALON_PLATFORM_DOMAIN);
  } catch {
    return null;
  }
  if (!raw || !/^[a-zA-Z0-9.-]+(?::[0-9]{1,5})?$/.test(raw)) return null;
  const port = raw.split(':')[1];
  if (port !== undefined && (Number(port) < 1 || Number(port) > 65535)) return null;
  if (classifyHostname(raw, domain).kind !== 'platform') return null;
  const scheme = process.env.SALON_WEB_LOCAL_HTTP === 'true' ? 'http' : 'https';
  return { hostname: domain, origin: `${scheme}://${raw.toLowerCase()}` };
}

function output(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'private, no-store' } });
}

export async function handlePlatform(request: NextRequest, action: string): Promise<NextResponse> {
  try {
    const host = platformRequestHost(request.headers.get('host'));
    if (!host || !ACTIONS.has(action)) return output({ code: 'not_found' }, 404);
    if (request.method !== 'POST') return output({ code: 'method_not_allowed' }, 405);
    const site = request.headers.get('sec-fetch-site');
    if (request.headers.get('origin') !== host.origin || site === 'cross-site' || site === 'same-site')
      return output({ code: 'request_rejected' }, 403);
    const read = await readBoundedJson(request, 1024);
    if (!read.ok) return output({ code: 'bad_request' }, 400);
    const body = read.value;
    const token = typeof body.token === 'string' ? body.token : '';
    // A malformed token is answered as an unknown one, without asking the API.
    if (!TOKEN.test(token)) return output({ code: 'not_found' }, 404);
    const client = platformClient(host.hostname);
    if (!client) return output({ code: 'service_unavailable' }, 503);
    const params = { path: { token } };
    if (action === 'export-info') {
      const r = await client.GET('/api/v1/exports/{token}/metadata', { params, signal: requestSignal() });
      if (!r.data)
        return output(
          { code: r.response.status === 404 ? 'not_found' : 'service_unavailable' },
          r.response.status === 404 ? 404 : 503,
        );
      return output({
        readyAt: r.data.readyAt,
        deleteAt: r.data.deleteAt,
        sizeBytes: r.data.sizeBytes,
        sha256: r.data.sha256,
      });
    }
    const r = await client.GET('/api/v1/exports/{token}', { params, signal: requestSignal(), parseAs: 'arrayBuffer' });
    if (!r.data)
      return output(
        { code: r.response.status === 404 ? 'not_found' : 'service_unavailable' },
        r.response.status === 404 ? 404 : 503,
      );
    return archive(r.data as ArrayBuffer, r.response);
  } catch {
    return output({ code: 'service_unavailable' }, 503);
  }
}
