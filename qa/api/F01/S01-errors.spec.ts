/**
 * F01-S01: how the API answers a request it cannot serve. Expected results are AC-4 and the
 * stage's scope ("Errors as problem details with a stable code"): every error is
 * application/problem+json, carries a machine-readable code, and shows nothing of how the product
 * is built.
 */
import type { APIResponse } from '@playwright/test';

import { mediaType, PROBLEM_JSON, type Problem } from '@qa/api-client';
import { expect, test } from '@qa/fixtures';
import { internalsIn } from '@qa/leaks';
import { schemaValidator } from '@qa/openapi';
import { apiHost, sendRaw, type RawResponse } from '@qa/raw-http';

/** A code is a stable, machine-readable word: lower case, joined with underscores. */
const CODE = /^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/;

type Answer = { status: number; contentType: string; headers: Record<string, string>; body: string };

async function answerOf(response: APIResponse): Promise<Answer> {
  return {
    status: response.status(),
    contentType: mediaType(response),
    headers: response.headers(),
    body: await response.text(),
  };
}

function answerOfRaw(response: RawResponse): Answer {
  return {
    status: response.status,
    contentType: (response.headers['content-type'] ?? '').split(';')[0].trim().toLowerCase(),
    headers: response.headers,
    body: response.body,
  };
}

/** Asserts the answer is problem details as the contract describes them, and returns the body. */
function expectProblem(answer: Answer, label: string): Problem {
  expect(answer.contentType, `${label}: content type (status ${answer.status}, body ${answer.body.slice(0, 200)})`).toBe(PROBLEM_JSON);
  let problem: Problem;
  try {
    problem = JSON.parse(answer.body) as Problem;
  } catch {
    throw new Error(`${label}: the body is not JSON: ${answer.body.slice(0, 300)}`);
  }
  const validate = schemaValidator('Problem');
  expect(validate(problem), `${label}: matches the Problem schema: ${JSON.stringify(validate.errors)}`).toBe(true);
  expect(problem.code, `${label}: code`).toMatch(CODE);
  expect(problem.status, `${label}: the status in the body is the HTTP status`).toBe(answer.status);
  return problem;
}

/** Asserts neither the body nor any header shows how the product is built. */
function expectNoInternals(answer: Answer, label: string): void {
  expect(internalsIn(answer.body), `${label}: internals in the body: ${answer.body.slice(0, 500)}`).toEqual([]);
  const headers = Object.entries(answer.headers)
    // The date and the media type are not internals; their digits and dots are not versions.
    .filter(([name]) => !['date', 'content-type', 'content-length', 'keep-alive', 'connection', 'transfer-encoding', 'allow', 'vary', 'accept'].includes(name))
    .map(([name, value]) => `${name}: ${value}`)
    .join('\n');
  expect(internalsIn(headers), `${label}: internals in the headers:\n${headers}`).toEqual([]);
  for (const name of ['server', 'x-powered-by', 'x-application-context']) {
    expect(answer.headers[name], `${label}: the ${name} header`).toBeUndefined();
  }
}

const UNKNOWN_API_PATHS = [
  '/api/v1/does-not-exist',
  '/api/v1/meta/extra',
  '/api/v1/meta/',
  '/api/v1/',
  '/api/v1',
  '/api/v2/meta',
  '/api/',
  '/api',
  '/api/v1/does-not-exist?page=1&size=20',
  `/api/v1/${'segment/'.repeat(40)}end`,
];

test('TC-F01-S01-015 a path under /api that does not exist answers 404 as problem details with code not_found', async ({ api }) => {
  for (const path of UNKNOWN_API_PATHS) {
    const answer = await answerOf(await api.get(path));
    expect(answer.status, `GET ${path}`).toBe(404);
    const problem = expectProblem(answer, `GET ${path}`);
    expect(problem.code, `GET ${path}`).toBe('not_found');
  }
});

test('TC-F01-S01-016 an unknown path answers 404 as problem details whatever format the caller asks for', async ({ api }) => {
  const accepts = ['text/html', 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8', 'application/xml', 'text/plain', 'image/png', 'application/json'];
  for (const accept of accepts) {
    const answer = await answerOf(await api.get('/api/v1/does-not-exist', { accept }));
    expect(answer.status, `Accept: ${accept}`).toBe(404);
    const problem = expectProblem(answer, `Accept: ${accept}`);
    expect(problem.code, `Accept: ${accept}`).toBe('not_found');
  }
});

test('TC-F01-S01-017 a 404 shows no stack trace, class name or framework version, in its body or its headers', async ({ api }) => {
  const paths = [
    ...UNKNOWN_API_PATHS,
    // Outside /api too: nothing else the framework or the server would serve is there to find.
    '/does-not-exist', '/actuator/does-not-exist', '/error', '/favicon.ico', '/index.html',
    '/swagger-ui.html', '/swagger-ui/index.html', '/h2-console', '/.env', '/.git/config', '/WEB-INF/web.xml',
  ];
  for (const path of paths) {
    const answer = await answerOf(await api.get(path));
    expect(answer.status, `GET ${path}`).toBe(404);
    expectProblem(answer, `GET ${path}`);
    expectNoInternals(answer, `GET ${path}`);
  }
  // Asking for a trace the way Spring Boot's default error page allows changes nothing.
  const asked = await answerOf(await api.get('/api/v1/does-not-exist?trace=true&message=true&errors=true&exception=true'));
  expect(asked.status).toBe(404);
  expect(Object.keys(JSON.parse(asked.body)).sort(), 'no field beyond the contract\'s').toEqual(['code', 'detail', 'instance', 'status', 'title']);
  expectNoInternals(asked, 'GET with ?trace=true');
});

test('TC-F01-S01-018 a method meta does not support answers 405 as problem details with code method_not_allowed', async ({ api }) => {
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
    const answer = await answerOf(
      await api.send(method, '/api/v1/meta', { headers: { 'content-type': 'application/json' }, data: '{"service":"x"}' }),
    );
    expect(answer.status, method).toBe(405);
    const problem = expectProblem(answer, method);
    expect(problem.code, method).toBe('method_not_allowed');
    expect(answer.headers['allow'] ?? '', `${method}: the Allow header names what is allowed`).toContain('GET');
    expectNoInternals(answer, method);
  }
  // Refusing a write changed nothing: meta still answers.
  expect((await api.meta()).status()).toBe(200);
});

test('TC-F01-S01-021 a response format meta cannot produce answers 406 as problem details with code not_acceptable', async ({ api }) => {
  for (const accept of ['application/xml', 'text/html', 'image/png']) {
    const answer = await answerOf(await api.get('/api/v1/meta', { accept }));
    expect(answer.status, `Accept: ${accept}`).toBe(406);
    const problem = expectProblem(answer, `Accept: ${accept}`);
    expect(problem.code, `Accept: ${accept}`).toBe('not_acceptable');
    expectNoInternals(answer, `Accept: ${accept}`);
  }
});

const host = apiHost();
const get = (target: string, extraHeaders = ''): string =>
  `GET ${target} HTTP/1.1\r\nHost: ${host}\r\nConnection: close\r\n${extraHeaders}\r\n`;

/** Requests no well-behaved client sends. Each is the caller's fault, so each is answered 4xx. */
const MALFORMED: Array<{ name: string; payload: () => string | Buffer }> = [
  { name: 'a path with a broken percent-encoding', payload: () => get('/api/v1/%') },
  { name: 'a path with an encoded NUL', payload: () => get('/api/v1/meta%00') },
  { name: 'a path that climbs out with encoded slashes', payload: () => get('/api/v1/..%2f..%2f..%2fetc%2fpasswd') },
  { name: 'a path that climbs out with dots', payload: () => get('/api/v1/../../../etc/passwd') },
  { name: 'a path with characters a URL may not contain', payload: () => get('/api/v1/<script>alert(1)</script>') },
  { name: 'a path with a backslash', payload: () => get('/api/v1\\meta') },
  { name: 'a URL of 100,000 characters', payload: () => get(`/api/v1/${'a'.repeat(100_000)}`) },
  { name: 'a header of 100,000 characters', payload: () => get('/api/v1/meta', `X-Filler: ${'a'.repeat(100_000)}\r\n`) },
  { name: '2,000 headers', payload: () => get('/api/v1/meta', Array.from({ length: 2000 }, (_, i) => `X-Filler-${i}: a\r\n`).join('')) },
  { name: 'a request with no Host header', payload: () => 'GET /api/v1/meta HTTP/1.1\r\nConnection: close\r\n\r\n' },
  { name: 'a negative Content-Length', payload: () => `POST /api/v1/meta HTTP/1.1\r\nHost: ${host}\r\nConnection: close\r\nContent-Length: -1\r\n\r\n` },
  { name: 'a Content-Length that is not a number', payload: () => `POST /api/v1/meta HTTP/1.1\r\nHost: ${host}\r\nConnection: close\r\nContent-Length: lots\r\n\r\n` },
  { name: 'a header line with no colon', payload: () => get('/api/v1/meta', 'this is not a header\r\n') },
  { name: 'a request line that is not HTTP', payload: () => 'HELLO\r\n\r\n' },
  { name: 'a method made of illegal characters', payload: () => `G"E"T /api/v1/meta HTTP/1.1\r\nHost: ${host}\r\nConnection: close\r\n\r\n` },
  { name: 'binary that is not HTTP at all', payload: () => Buffer.from([0x16, 0x03, 0x01, 0x02, 0x00, 0x01, 0x00, 0x01, 0xfc, 0x03, 0x03, 0x0d, 0x0a, 0x0d, 0x0a]) },
];

test('TC-F01-S01-022 a malformed or oversized request is answered 4xx as problem details, shows no internals and never hangs', async ({ api }) => {
  for (const { name, payload } of MALFORMED) {
    const response = await sendRaw(payload());
    const answer = answerOfRaw(response);
    expect(answer.status, `${name}: an HTTP answer came back (got: ${response.raw.slice(0, 200)})`).toBeGreaterThan(0);
    expect(answer.status, `${name}: the caller's mistake is a 4xx`).toBeGreaterThanOrEqual(400);
    expect(answer.status, `${name}: the caller's mistake is a 4xx`).toBeLessThan(500);
    expectProblem(answer, name);
    expectNoInternals(answer, name);
  }
  expect((await api.health()).status(), 'the API is healthy afterwards').toBe(200);
});

/** A POST carrying a body of the given size to a path, with its length declared honestly. */
function postWithBody(path: string, size: number): Buffer {
  return Buffer.concat([
    Buffer.from(`POST ${path} HTTP/1.1\r\nHost: ${host}\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ${size}\r\n\r\n`),
    Buffer.alloc(size, 'a'),
  ]);
}

test('TC-F01-S01-023 a large body sent to an endpoint that takes none is refused as problem details, and a flood of them leaves the API up', async ({ api }) => {
  const started = Date.now();
  const response = await sendRaw(postWithBody('/api/v1/meta', 1024 * 1024), 15_000);
  const answer = answerOfRaw(response);

  expect(answer.status, `a 1 MB body: an HTTP answer came back (got: ${response.raw.slice(0, 200)})`).toBe(405);
  const problem = expectProblem(answer, 'a 1 MB body');
  expect(problem.code).toBe('method_not_allowed');
  expectNoInternals(answer, 'a 1 MB body');
  expect(Date.now() - started, 'answered promptly').toBeLessThan(10_000);

  // Twenty bodies of 5 MB at once. A server may answer and close before it has read a body it will
  // not use, and the caller then sees either the answer or the closed connection (status 0): both
  // are a refusal. What must not happen is a success, a server error or a hang (sendRaw rejects).
  const flood = await Promise.all(
    Array.from({ length: 20 }, (_, index) =>
      sendRaw(postWithBody(index % 2 === 0 ? '/api/v1/meta' : '/api/v1/does-not-exist', 5 * 1024 * 1024), 15_000),
    ),
  );
  expect(flood).toHaveLength(20);
  for (const one of flood) {
    const refused = one.status === 0 || (one.status >= 400 && one.status < 500);
    expect(refused, `a 5 MB body is refused (status ${one.status}: ${one.raw.slice(0, 200)})`).toBe(true);
    if (one.status !== 0) {
      expectProblem(answerOfRaw(one), 'a 5 MB body');
      expectNoInternals(answerOfRaw(one), 'a 5 MB body');
    }
  }
  expect((await api.health()).status(), 'the API is healthy afterwards').toBe(200);
  expect((await api.meta()).status(), 'and still serves').toBe(200);
});
