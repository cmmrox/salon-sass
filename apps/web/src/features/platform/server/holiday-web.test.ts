// @vitest-environment node
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { contextFromTraceparent, runInRequest } from '@/lib/observability/request-context';
import { handleOperator } from './operator-web';

/** The console BFF's holiday calendar actions (F04-S03), against a fake API on loopback. */
type Call = { method: string; path: string; authorization?: string; type?: string; body: string };
let server: Server;
let calls: Call[];
let answer: (call: Call) => { status: number; data: unknown };
const principal = { id: 'op', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true };
const YEAR = { year: 2027, state: 'draft', revision: 0, version: 1, publishedAt: null, days: [], withdrawn: [] };

beforeEach(async () => {
  calls = [];
  vi.stubEnv('SALON_PLATFORM_DOMAIN', 'localhost');
  vi.stubEnv('SALON_WEB_LOCAL_HTTP', 'true');
  vi.stubEnv('SALON_OPERATOR_PROXY_KEY', '34'.repeat(32));
  answer = (call) =>
    call.path === '/api/v1/operator/me' ? { status: 200, data: principal } : { status: 200, data: YEAR };
  server = createServer((request, response) => {
    let body = '';
    request.on('data', (chunk) => {
      body += chunk;
    });
    request.on('end', () => {
      const call: Call = {
        method: request.method!,
        path: request.url!,
        authorization: request.headers.authorization,
        type: request.headers['content-type'],
        body,
      };
      calls.push(call);
      const result = answer(call);
      response.writeHead(result.status, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify(result.data));
    });
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  vi.stubEnv('SALON_API_BASE_URL', `http://127.0.0.1:${(server.address() as AddressInfo).port}`);
});
afterEach(async () => {
  server.closeAllConnections();
  await new Promise<void>((r) => server.close(() => r()));
  vi.unstubAllEnvs();
});

const host = 'console.localhost:3100';
const headers = {
  Host: host,
  Origin: `http://${host}`,
  'Sec-Fetch-Site': 'same-origin',
  Cookie: 'salon_operator_access=private-operator-access',
};
function run(action: string, init: { method?: string; body?: BodyInit; type?: string; query?: string } = {}) {
  const req = new NextRequest(`http://${host}/operator-web/${action}${init.query ?? ''}`, {
    method: init.method ?? 'POST',
    headers: { ...headers, ...(init.type ? { 'Content-Type': init.type } : {}) },
    ...(init.body !== undefined ? { body: init.body, duplex: 'half' } : {}),
  } as ConstructorParameters<typeof NextRequest>[1]);
  const context = {
    ...contextFromTraceparent(undefined),
    clientAddress: '127.0.0.9',
    deadline: performance.now() + 3000,
  };
  return runInRequest(context, () => handleOperator(req, action));
}
const apiCalls = () => calls.filter((call) => call.path !== '/api/v1/operator/me');

describe('the console BFF’s holiday calendar', () => {
  it('reads the years and a year, with the operator’s credential and the year checked for its form', async () => {
    expect((await run('holiday-years', { method: 'GET' })).status).toBe(200);
    expect((await run('holiday-year', { method: 'GET', query: '?year=2027' })).status).toBe(200);
    expect((await run('holiday-year', { method: 'GET', query: '?year=../x' })).status).toBe(400);

    expect(apiCalls().map((call) => [call.method, call.path, call.authorization])).toEqual([
      ['GET', '/api/v1/operator/holiday-calendars', 'Bearer private-operator-access'],
      ['GET', '/api/v1/operator/holiday-calendars/2027', 'Bearer private-operator-access'],
    ]);
  });

  it('sends single-day edits and a publication rebuilt from the contract’s fields', async () => {
    const json = 'application/json';
    await run('holiday-day-add', {
      type: json,
      body: JSON.stringify({ year: '2027', date: '2027-01-14', name: 'Pongal', kind: 'public', extra: 'x' }),
    });
    await run('holiday-day-change', {
      type: json,
      body: JSON.stringify({ year: '2027', date: '2027-01-14', name: 'Thai Pongal', kind: 'public' }),
    });
    await run('holiday-day-remove', { type: json, body: JSON.stringify({ year: '2027', date: '2027-01-14' }) });
    await run('holiday-publish', { type: json, body: JSON.stringify({ year: '2027', version: 3, other: 1 }) });
    expect(
      (await run('holiday-day-remove', { type: json, body: JSON.stringify({ year: '2027', date: 'x/../y' }) })).status,
    ).toBe(400);

    expect(apiCalls().map((call) => [call.method, call.path, call.body])).toEqual([
      [
        'POST',
        '/api/v1/operator/holiday-calendars/2027/days',
        JSON.stringify({ date: '2027-01-14', name: 'Pongal', kind: 'public' }),
      ],
      [
        'PUT',
        '/api/v1/operator/holiday-calendars/2027/days/2027-01-14',
        JSON.stringify({ name: 'Thai Pongal', kind: 'public' }),
      ],
      ['DELETE', '/api/v1/operator/holiday-calendars/2027/days/2027-01-14', ''],
      ['POST', '/api/v1/operator/holiday-calendars/2027/publication', JSON.stringify({ version: 3 })],
    ]);
  });

  it('passes on an import’s file alone, and each bad line with its number and reason', async () => {
    answer = (call) =>
      call.path === '/api/v1/operator/me'
        ? { status: 200, data: principal }
        : {
            status: 400,
            data: { code: 'bad_request', errors: [{ field: 'file', line: 2, detail: 'the date is outside 2027' }] },
          };
    const form = new FormData();
    form.append('year', '2027');
    form.append('file', new File(['2026-12-25,Christmas Day,public\n'], 'calendar.csv', { type: 'text/csv' }));
    form.append('extra', 'dropped');
    const response = await run('holiday-import', { body: form });

    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({
      code: 'bad_request',
      errors: [{ field: 'file', line: 2, detail: 'the date is outside 2027' }],
    });
    const [imported] = apiCalls();
    expect(imported).toMatchObject({ method: 'POST', path: '/api/v1/operator/holiday-calendars/2027/import' });
    expect(imported!.type).toMatch(/^multipart\/form-data/);
    expect(imported!.body).toContain('2026-12-25,Christmas Day,public');
    expect(imported!.body).not.toContain('dropped');
  });

  it('refuses an import body over its limit, chunked or declared, before any upstream call', async () => {
    const big = new Uint8Array(200 * 1024);
    const chunked = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(big);
        controller.close();
      },
    });
    const response = await run('holiday-import', { body: chunked, type: 'multipart/form-data; boundary=x' });

    expect(response.status).toBe(413);
    expect(apiCalls()).toEqual([]);
  });
});
