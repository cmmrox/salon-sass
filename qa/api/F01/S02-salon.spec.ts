/**
 * F01-S02: the current salon's public brand, GET /api/v1/salon, and which salon a request is for.
 * Expected results are AC-1, AC-2, AC-3 and AC-12 of the stage, "Salons and their hostnames" in
 * docs/architecture/multi-tenancy.md, and the salons of docs/qa/test-plan.md.
 *
 * An isolation test here has one shape: first the data is shown to exist for its owner, then the
 * other salon is shown not to get it. Hostnames a well-behaved client cannot send are written to
 * a socket as bytes.
 */
import { createApiClient } from '@contracts/client';

import { answerOf, answerOfRaw, expectBrandOf, expectNoInternals, expectNoTenantId, expectNothingOfOtherSalons, expectProblem, type Answer } from '@qa/answers';
import { SALON_PATH, type Problem, type SalonBrand } from '@qa/api-client';
import { apiUrl } from '@qa/env';
import { expect, test } from '@qa/fixtures';
import { committedContract, schemaValidator, sorted } from '@qa/openapi';
import { apiPort, sendOnOneConnection, sendRaw } from '@qa/raw-http';
import { alpha, beta, gamma, NIL_TENANT_ID, PLATFORM_DOMAIN, RESERVED_SLUGS, salons, salonUrl, type Salon } from '@qa/tenants';

const port = apiPort();

/** The bytes of one request with the Host header line exactly as given (or with none). */
function request(
  hostLine: string | Buffer | null,
  options: { target?: string; method?: string; version?: string; headers?: string[]; close?: boolean; body?: string } = {},
): Buffer {
  const { target = SALON_PATH, method = 'GET', version = 'HTTP/1.1', headers = [], close = true, body } = options;
  const lines: Buffer[] = [Buffer.from(`${method} ${target} ${version}\r\n`)];
  if (hostLine !== null) {
    lines.push(Buffer.isBuffer(hostLine) ? hostLine : Buffer.from(hostLine), Buffer.from('\r\n'));
  }
  for (const header of headers) {
    lines.push(Buffer.from(`${header}\r\n`));
  }
  if (body !== undefined) {
    lines.push(Buffer.from(`Content-Type: application/json\r\nContent-Length: ${Buffer.byteLength(body)}\r\n`));
  }
  if (close) {
    lines.push(Buffer.from('Connection: close\r\n'));
  }
  lines.push(Buffer.from('\r\n'));
  if (body !== undefined) {
    lines.push(Buffer.from(body));
  }
  return Buffer.concat(lines);
}

/** One request with the Host header line exactly as given, and its answer. */
async function ask(hostLine: string | Buffer | null, options: Parameters<typeof request>[1] = {}): Promise<Answer> {
  return answerOfRaw(await sendRaw(request(hostLine, options)));
}

const hostOf = (salon: Salon): string => `Host: ${salon.hostname}:${port}`;

/** Asserts the salon's brand exists for its owner: what stops an isolation test passing on nothing. */
async function expectOwnerSeesBrand(salon: Salon): Promise<void> {
  expectBrandOf(await ask(hostOf(salon)), salon, `${salon.slug}, on its own hostname`);
}

// --------------------------------------------------------------------------------------------
// AC-1

test('TC-F01-S02-001 each salon\'s hostname returns that salon\'s slug, name and brand colour, and no other field', async ({ api }) => {
  const validate = schemaValidator('Salon');
  expect(salons, 'three salons to ask for').toHaveLength(3);
  for (const salon of salons) {
    const answer = await answerOf(await api.salon(salon));
    const brand = expectBrandOf(answer, salon, salon.hostname);
    expect(validate(brand), `${salon.hostname}: matches the Salon schema: ${JSON.stringify(validate.errors)}`).toBe(true);
    expect(Object.keys(brand).sort(), `${salon.hostname}: nothing else identifies the salon`).toEqual(['brandColour', 'name', 'slug', 'timeZone']);
    expect(answer.headers['set-cookie'], `${salon.hostname}: no cookie is set`).toBeUndefined();
  }
});

test('TC-F01-S02-002 a salon\'s brand is served on its own hostname and on no other salon\'s', async ({ api }) => {
  let comparisons = 0;
  for (const owner of salons) {
    // The data exists, and its owner can read it.
    const own = await answerOf(await api.salon(owner));
    expectBrandOf(own, owner, `${owner.slug} reads its own brand`);
    for (const other of salons.filter((salon) => salon !== owner)) {
      const answer = await answerOf(await api.salon(other));
      expect(answer.status, `${other.hostname} answers`).toBe(200);
      for (const value of [owner.id, owner.name, owner.brandColour, `"${owner.slug}"`]) {
        expect(answer.body, `${other.hostname} shows nothing of ${owner.slug}`).not.toContain(value);
      }
      comparisons += 1;
    }
  }
  expect(comparisons, 'every salon was compared with both others').toBe(6);
});

test('TC-F01-S02-003 the hostname is matched without regard to case, and the port in it is ignored', async () => {
  const upper = (text: string): string => text.toUpperCase();
  const mixed = (text: string): string => [...text].map((letter, index) => (index % 2 === 0 ? letter.toUpperCase() : letter)).join('');
  const spellings: Array<[string, Salon]> = [];
  for (const salon of salons) {
    spellings.push(
      [`Host: ${upper(salon.hostname)}:${port}`, salon],
      [`Host: ${mixed(salon.hostname)}:${port}`, salon],
      [`Host: ${upper(salon.slug)}.${PLATFORM_DOMAIN}:${port}`, salon],
      [`Host: ${salon.slug}.${upper(PLATFORM_DOMAIN)}:${port}`, salon],
      // The header's own name is not case-sensitive either.
      [`hOsT: ${salon.hostname}:${port}`, salon],
      // No port, another port, an empty port: the salon is its hostname.
      [`Host: ${salon.hostname}`, salon],
      [`Host: ${salon.hostname}:1`, salon],
      [`Host: ${salon.hostname}:65535`, salon],
      [`Host: ${salon.hostname}:`, salon],
      // Blank space around the value is not part of it.
      [`Host:   ${salon.hostname}:${port}  `, salon],
    );
  }
  expect(spellings.length).toBe(30);
  for (const [hostLine, salon] of spellings) {
    expectBrandOf(await ask(hostLine), salon, `"${hostLine}"`);
  }
});

// --------------------------------------------------------------------------------------------
// AC-2

/** A slug that is well formed, not reserved and belongs to no salon. */
const UNKNOWN_SLUG = 'nobody-here';

/** Hostnames AC-2 names: each resolves to no salon. */
const NO_SALON_NAMED_BY_THE_CRITERION: Array<[string, string]> = [
  ['the bare platform domain', PLATFORM_DOMAIN],
  ['an unknown slug', `${UNKNOWN_SLUG}.${PLATFORM_DOMAIN}`],
  ['the reserved slug console', `console.${PLATFORM_DOMAIN}`],
  ['the reserved slug www', `www.${PLATFORM_DOMAIN}`],
  ['two labels in front of the platform domain', `${alpha.slug}.${beta.slug}.${PLATFORM_DOMAIN}`],
  ['a hostname that only contains the platform domain', `${alpha.hostname}.example.com`],
];

/** More hostnames that resolve to no salon: lookalikes of a real one, and names that are not a salon's at all. */
const NO_SALON_LOOKALIKES: Array<[string, string]> = [
  ...RESERVED_SLUGS.map((slug): [string, string] => [`the reserved slug ${slug}`, `${slug}.${PLATFORM_DOMAIN}`]),
  ['two labels, the other way round', `${beta.slug}.${alpha.slug}.${PLATFORM_DOMAIN}`],
  ['three labels', `${gamma.slug}.${beta.slug}.${alpha.slug}.${PLATFORM_DOMAIN}`],
  ['a salon\'s slug repeated', `${alpha.slug}.${alpha.slug}.${PLATFORM_DOMAIN}`],
  ['the platform domain in front', `${PLATFORM_DOMAIN}.${alpha.slug}`],
  ['the platform domain twice', `${alpha.slug}.${PLATFORM_DOMAIN}.${PLATFORM_DOMAIN}`],
  ['the platform domain as the slug', `${PLATFORM_DOMAIN}.${PLATFORM_DOMAIN}`],
  ['no dot between slug and domain', `${alpha.slug}${PLATFORM_DOMAIN}`],
  ['a hyphen for the dot', `${alpha.slug}-${PLATFORM_DOMAIN}`],
  ['a longer domain', `${alpha.hostname}x`],
  ['a longer domain, in front', `${alpha.slug}.x${PLATFORM_DOMAIN}`],
  ['a slug with a letter in front', `x${alpha.hostname}`],
  ['a slug one letter short', `${alpha.slug.slice(0, -1)}.${PLATFORM_DOMAIN}`],
  ['a slug one letter long', `${alpha.slug}a.${PLATFORM_DOMAIN}`],
  ['a slug with a hyphen and more', `${alpha.slug}-2.${PLATFORM_DOMAIN}`],
  ['two slugs joined', `${alpha.slug}-${beta.slug}.${PLATFORM_DOMAIN}`],
  ['a trailing dot', `${alpha.hostname}.`],
  ['the bare domain with a trailing dot', `${PLATFORM_DOMAIN}.`],
  ['the loopback address', '127.0.0.1'],
  ['the loopback address, IPv6', '[::1]'],
  ['a slug in front of an address', `${alpha.slug}.127.0.0.1`],
  ['an address in front of the domain', `127.0.0.1.${PLATFORM_DOMAIN}`],
  ['a salon\'s tenant id as the slug', `${beta.id}.${PLATFORM_DOMAIN}`],
  ['the nil tenant id as the slug', `${NIL_TENANT_ID}.${PLATFORM_DOMAIN}`],
  ['a punycode label that displays like a salon\'s slug', `xn--lph-5cd.${PLATFORM_DOMAIN}`],
  ['the table\'s name as the slug', `tenant.${PLATFORM_DOMAIN}`],
  ['a label of 63 characters', `${'a'.repeat(63)}.${PLATFORM_DOMAIN}`],
  ['a label of 64 characters', `${'a'.repeat(64)}.${PLATFORM_DOMAIN}`],
  ['a label of 300 characters', `${'a'.repeat(300)}.${PLATFORM_DOMAIN}`],
  ['a label of 5,000 characters', `${'a'.repeat(5000)}.${PLATFORM_DOMAIN}`],
  ['200 labels', `${'a.'.repeat(200)}${PLATFORM_DOMAIN}`],
  ['a slug of 31 characters', `${'a'.repeat(31)}.${PLATFORM_DOMAIN}`],
  ['a slug of 2 characters', `ab.${PLATFORM_DOMAIN}`],
];

test('TC-F01-S02-004 a hostname that resolves to no salon answers 404 not_found, with the same body whichever hostname it was', async () => {
  // A salon is there to be found: without this, "404 everywhere" would pass on a product that serves no salon.
  await expectOwnerSeesBrand(alpha);

  const baseline = await ask(`Host: ${UNKNOWN_SLUG}.${PLATFORM_DOMAIN}:${port}`);
  expect(baseline.status, 'an unknown slug').toBe(404);
  const baselineProblem = expectProblem(baseline, 'an unknown slug');
  expect(baselineProblem.code).toBe('not_found');
  expect(baselineProblem.instance, 'instance is the path asked for').toBe(SALON_PATH);

  const cases: Array<[string, Answer]> = [];
  for (const [name, hostname] of [...NO_SALON_NAMED_BY_THE_CRITERION, ...NO_SALON_LOOKALIKES]) {
    cases.push([`${name} (${hostname.slice(0, 60)})`, await ask(`Host: ${hostname}:${port}`)]);
    cases.push([`${name}, without a port`, await ask(`Host: ${hostname}`)]);
  }
  cases.push(['an empty Host header', await ask('Host: ')]);
  cases.push(['HTTP/1.0 with no Host header', await ask(null, { version: 'HTTP/1.0', close: false })]);
  expect(cases.length).toBeGreaterThan(100);

  for (const [name, answer] of cases) {
    expect(answer.status, `${name}: status (body ${answer.body.slice(0, 200)})`).toBe(404);
    const problem = expectProblem(answer, name);
    expect(problem.code, name).toBe('not_found');
    // Identical apart from instance, which is the same path here: so identical outright. A caller
    // cannot tell a reserved slug, a salon that does not exist and a name that is no salon's apart.
    expect(sorted({ ...problem, traceId: '' }), `${name}: the same body as for an unknown slug`).toEqual(sorted({ ...baselineProblem, traceId: '' }));
    expect(answer.contentType, name).toBe(baseline.contentType);
    expectNothingOfOtherSalons(answer, null, name);
    expectNoTenantId(answer, name);
  }
});

test('TC-F01-S02-005 the same 404 is given on another path of the salon\'s API, apart from instance', async () => {
  const onSalon = expectProblem(await ask(`Host: ${UNKNOWN_SLUG}.${PLATFORM_DOMAIN}:${port}`), 'the salon endpoint on an unknown slug');
  const elsewhere = expectProblem(
    await ask(hostOf(alpha), { target: '/api/v1/does-not-exist' }),
    'a path that does not exist, on a real salon',
  );
  expect(elsewhere.instance).toBe('/api/v1/does-not-exist');
  // "No such salon" and "no such thing" are one answer: neither confirms that anything exists.
  expect(sorted({ ...onSalon, instance: '', traceId: '' })).toEqual(sorted({ ...elsewhere, instance: '', traceId: '' }));
});

test('TC-F01-S02-006 meta and health answer 200 on every hostname that resolves to no salon', async () => {
  for (const [name, hostname] of NO_SALON_NAMED_BY_THE_CRITERION) {
    const meta = await ask(`Host: ${hostname}:${port}`, { target: '/api/v1/meta' });
    expect(meta.status, `meta on ${name}`).toBe(200);
    expect(JSON.parse(meta.body).service, `meta on ${name}: it is the service's own answer`).toBeTruthy();
    expectNothingOfOtherSalons(meta, null, `meta on ${name}`);

    const health = await ask(`Host: ${hostname}:${port}`, { target: '/actuator/health' });
    expect(health.status, `health on ${name}`).toBe(200);
    expect(JSON.parse(health.body).status, `health on ${name}`).toBe('UP');
  }
  // And on a salon's own hostname: the platform's endpoints are not the salon's to hide.
  for (const salon of salons) {
    expect((await ask(hostOf(salon), { target: '/api/v1/meta' })).status, `meta on ${salon.hostname}`).toBe(200);
    expect((await ask(hostOf(salon), { target: '/actuator/health' })).status, `health on ${salon.hostname}`).toBe(200);
  }
});

// --------------------------------------------------------------------------------------------
// AC-3

/** Every way a client might name beta while asking on alpha's hostname, as query parameters. */
function queriesNaming(other: Salon): string[] {
  const names = ['tenant', 'tenantId', 'tenant_id', 'tenant-id', 'tenantSlug', 'slug', 'salon', 'salonId', 'salon_id', 'id', 'host', 'hostname', 'domain', 'subdomain', 'X-Tenant-Id', 'X-Forwarded-Host', 'app.tenant_id'];
  const values = [other.slug, other.id, other.hostname, `${other.hostname}:${port}`];
  const queries: string[] = [];
  for (const name of names) {
    for (const value of values) {
      queries.push(`?${encodeURIComponent(name)}=${encodeURIComponent(value)}`);
    }
  }
  // All of them at once, the same name twice, and as path parameters.
  queries.push(`?${names.map((name) => `${encodeURIComponent(name)}=${encodeURIComponent(other.id)}`).join('&')}`);
  queries.push(`?tenant=${alpha.slug}&tenant=${other.slug}`, `?slug=${other.slug}&slug=${other.slug}`);
  queries.push(`;tenant=${other.slug}`, `;tenantId=${other.id}`, `;jsessionid=${other.id}`);
  return queries;
}

test('TC-F01-S02-007 a salon named in a query parameter is never trusted: alpha\'s hostname returns alpha\'s brand', async () => {
  await expectOwnerSeesBrand(beta);
  const queries = queriesNaming(beta);
  expect(queries.length).toBeGreaterThan(60);
  for (const query of queries) {
    expectBrandOf(await ask(hostOf(alpha), { target: `${SALON_PATH}${query}` }), alpha, `alpha with ${query.slice(0, 80)}`);
  }
  // The other way round as well: beta asking with alpha's id gets beta's.
  expectBrandOf(await ask(hostOf(beta), { target: `${SALON_PATH}?tenantId=${alpha.id}&slug=${alpha.slug}` }), beta, 'beta with alpha\'s id');
});

/** Every header a proxy, a framework or a hopeful client uses to say "this request is really for...". */
function headersNaming(other: Salon): string[] {
  const hostValue = `${other.hostname}:${port}`;
  return [
    `X-Forwarded-Host: ${hostValue}`,
    `X-Forwarded-Host: ${other.hostname}`,
    `X-Forwarded-Host: ${hostValue}, ${alpha.hostname}`,
    `X-Forwarded-Server: ${other.hostname}`,
    `X-Original-Host: ${hostValue}`,
    `X-Host: ${hostValue}`,
    `X-HTTP-Host-Override: ${hostValue}`,
    `X-Real-Host: ${hostValue}`,
    `X-Original-URL: http://${hostValue}${SALON_PATH}`,
    `X-Rewrite-URL: http://${hostValue}${SALON_PATH}`,
    `Forwarded: host=${hostValue};proto=http`,
    `Forwarded: for=127.0.0.1;host="${hostValue}"`,
    `X-Forwarded-For: ${other.hostname}`,
    `X-Tenant-Id: ${other.id}`,
    `X-Tenant-Id: ${other.slug}`,
    `X-Tenant: ${other.slug}`,
    `X-Tenant: ${other.id}`,
    `X-Tenant-Slug: ${other.slug}`,
    `X-Salon: ${other.slug}`,
    `X-Salon-Id: ${other.id}`,
    `Tenant: ${other.slug}`,
    `Tenant-Id: ${other.id}`,
    `X-TenantID: ${other.id}`,
    `Origin: http://${hostValue}`,
    `Referer: http://${hostValue}/`,
    `Cookie: tenant=${other.slug}; tenant_id=${other.id}; tenantId=${other.id}; salon=${other.slug}`,
    `Authorization: Bearer ${other.id}`,
    `X-Api-Key: ${other.id}`,
    `From: owner@${other.hostname}`,
    `Via: 1.1 ${other.hostname}`,
  ];
}

test('TC-F01-S02-008 a salon named in a request header other than Host is never trusted: alpha\'s hostname returns alpha\'s brand', async () => {
  await expectOwnerSeesBrand(beta);
  const headers = headersNaming(beta);
  expect(headers.length).toBeGreaterThanOrEqual(30);
  for (const header of headers) {
    const answer=await ask(hostOf(alpha), { headers: [header] });
    if(header.startsWith('Authorization:')){expect(answer.status).toBe(401);expect(expectProblem(answer,'invalid credential is refused').code).toBe('invalid_session');expectNothingOfOtherSalons(answer,null,'invalid bearer reveals no salon');}
    else expectBrandOf(answer, alpha, `alpha with "${header}"`);
  }
  // Every one of them at once, and with the query parameters too.
  const selectors=headers.filter(header=>!header.startsWith('Authorization:'));
  expectBrandOf(await ask(hostOf(alpha), { headers:selectors }), alpha, 'alpha with every noncredential header at once');
  expectBrandOf(
    await ask(hostOf(alpha), { headers:selectors, target: `${SALON_PATH}?tenantId=${beta.id}&slug=${beta.slug}` }),
    alpha,
    'alpha with every such header and beta in the query',
  );
  const refused=await ask(hostOf(alpha),{headers,target:`${SALON_PATH}?tenantId=${beta.id}&slug=${beta.slug}`});expect(refused.status).toBe(401);expect(expectProblem(refused,'invalid credential with all selectors').code).toBe('invalid_session');expectNothingOfOtherSalons(refused,null,'credential rejection discloses no salon');
  // A forwarded header cannot make a hostname that names no salon name one.
  const nobody = await ask(`Host: ${PLATFORM_DOMAIN}:${port}`, { headers: headersNaming(alpha).filter(header=>!header.startsWith('Authorization:')) });
  expect(nobody.status, 'the bare platform domain with headers naming alpha').toBe(404);
  expectNothingOfOtherSalons(nobody, null, 'the bare platform domain with headers naming alpha');
});

/** Requests that name two hosts, or name one in a way no hostname is written. */
function ambiguous(): Array<[string, Buffer]> {
  const a = `${alpha.hostname}:${port}`;
  const b = `${beta.hostname}:${port}`;
  const one = (name: string, hostLine: string | Buffer | null, options: Parameters<typeof request>[1] = {}): [string, Buffer] => [name, request(hostLine, options)];
  return [
    one('two Host headers, alpha then beta', `Host: ${a}\r\nHost: ${b}`),
    one('two Host headers, beta then alpha', `Host: ${b}\r\nHost: ${a}`),
    one('two Host headers in different case', `Host: ${a}\r\nhost: ${b}`),
    one('the same Host header twice', `Host: ${a}\r\nHost: ${a}`),
    one('a Host header continued on a second line', `Host: ${a}\r\n ${b}`),
    one('two hosts separated by a comma', `Host: ${a}, ${b}`),
    one('two hosts separated by a comma, the other way round', `Host: ${b},${a}`),
    one('two hosts separated by a space', `Host: ${a} ${b}`),
    one('another salon as user information', `Host: ${beta.slug}@${a}`),
    one('a salon followed by @ and another', `Host: ${alpha.hostname}@${b}`),
    one('a slash and another salon', `Host: ${alpha.hostname}/${beta.hostname}`),
    one('a backslash and another salon', `Host: ${alpha.hostname}\\${beta.hostname}`),
    one('a question mark and another salon', `Host: ${alpha.hostname}?${beta.hostname}`),
    one('a hash and another salon', `Host: ${beta.hostname}#${alpha.hostname}`),
    one('an absolute request line for beta with Host alpha', `Host: ${a}`, { target: `http://${b}${SALON_PATH}` }),
    one('an absolute request line for alpha with Host beta', `Host: ${b}`, { target: `http://${a}${SALON_PATH}` }),
    one('an absolute request line with user information', `Host: ${a}`, { target: `http://${alpha.hostname}@${b}${SALON_PATH}` }),
    one('HTTP/1.1 with no Host header', null),
    one('an absolute request line for beta and no Host header, HTTP/1.1', null, { target: `http://${b}${SALON_PATH}` }),
    one('a full-width letter', `Host: ａ${alpha.hostname.slice(1)}:${port}`),
    one('a Cyrillic letter that looks like a', `Host: а${alpha.hostname.slice(1)}:${port}`),
    one('an ideographic full stop for the dot', `Host: ${alpha.slug}。${PLATFORM_DOMAIN}:${port}`),
    one('a byte that is not ASCII', Buffer.concat([Buffer.from(`Host: ${alpha.slug.slice(0, -1)}`), Buffer.from([0xe1]), Buffer.from(`.${PLATFORM_DOMAIN}`)])),
    one('a NUL in the name', `Host: ${alpha.slug}\u0000.${PLATFORM_DOMAIN}`),
    one('a percent-encoded letter', `Host: %61${alpha.hostname.slice(1)}`),
    one('an underscore', `Host: ${alpha.slug.slice(0, 2)}_${alpha.slug.slice(2)}.${PLATFORM_DOMAIN}`),
    one('a leading hyphen', `Host: -${alpha.hostname}`),
    one('a wildcard', `Host: *.${PLATFORM_DOMAIN}`),
    one('a SQL wildcard', `Host: %.${PLATFORM_DOMAIN}`),
    one('a quote and a comment', `Host: ${alpha.slug}'--.${PLATFORM_DOMAIN}`),
    one('a condition that is always true', `Host: x' or '1'='1.${PLATFORM_DOMAIN}`),
    one('an empty label', `Host: ${alpha.slug}..${PLATFORM_DOMAIN}`),
    one('a leading dot', `Host: .${alpha.hostname}`),
    one('only a dot in front of the domain', `Host: .${PLATFORM_DOMAIN}`),
    one('a port that is not a number', `Host: ${alpha.hostname}:abc`),
    one('only a port', `Host: :${port}`),
    one('square brackets around a name', `Host: [${alpha.hostname}]`),
  ];
}

test('TC-F01-S02-009 a request that names two hosts, or a host no hostname is written like, is refused and given no salon\'s data', async ({ api }) => {
  await expectOwnerSeesBrand(alpha);
  await expectOwnerSeesBrand(beta);
  const requests = ambiguous();
  expect(requests.length).toBeGreaterThanOrEqual(35);
  for (const [name, bytes] of requests) {
    const response = await sendRaw(bytes);
    const answer = answerOfRaw(response);
    expect([400, 404], `${name}: refused, not answered (status ${answer.status}: ${response.raw.slice(0, 200)})`).toContain(answer.status);
    expectProblem(answer, name);
    for (const salon of salons) {
      for (const value of [salon.id, salon.name, salon.brandColour]) {
        expect(answer.body, `${name}: no salon's data`).not.toContain(value);
      }
    }
    expectNoInternals(answer, name);
  }
  expect((await api.health()).status(), 'the API is healthy afterwards').toBe(200);
});

// --------------------------------------------------------------------------------------------
// One connection, many salons

type Step = { bytes: Buffer; expectation: Salon | number; name: string };

function conversation(): Step[] {
  const keep = { close: false };
  const salonStep = (salon: Salon): Step => ({ bytes: request(hostOf(salon), keep), expectation: salon, name: `GET on ${salon.hostname}` });
  const nobody: Step = { bytes: request(`Host: ${PLATFORM_DOMAIN}:${port}`, keep), expectation: 404, name: 'GET on the bare platform domain' };
  return [
    salonStep(alpha), salonStep(beta), nobody, salonStep(gamma),
    // Each failure on a salon's hostname is followed by a request that names no salon: whatever
    // the failure left behind, the next request on the connection must not inherit it.
    { bytes: request(hostOf(alpha), { ...keep, headers: ['Accept: application/xml'] }), expectation: 406, name: 'a format alpha cannot be given in' },
    nobody,
    { bytes: request(hostOf(alpha), { ...keep, method: 'POST', body: '{"name":"x"}' }), expectation: 405, name: 'POST on alpha' },
    nobody,
    { bytes: request(hostOf(beta), { ...keep, target: '/api/v1/does-not-exist' }), expectation: 404, name: 'a path that does not exist, on beta' },
    nobody,
    { bytes: request(hostOf(beta), { ...keep, target: '/api/v1/meta' }), expectation: 200, name: 'meta on beta' },
    nobody,
    { bytes: request(hostOf(gamma), { ...keep, method: 'HEAD' }), expectation: 200, name: 'HEAD on gamma' },
    nobody,
    salonStep(gamma), salonStep(beta), salonStep(alpha), salonStep(beta), salonStep(alpha), nobody, salonStep(gamma),
  ];
}

for (const pipelined of [false, true]) {
  const id = pipelined ? 'TC-F01-S02-011' : 'TC-F01-S02-010';
  const how = pipelined ? 'sent all at once on one connection' : 'sent one after another on one connection';
  test(`${id} requests for different salons ${how}, with failures between them, are each answered for their own hostname`, async () => {
    const steps = conversation();
    const answers = await sendOnOneConnection(steps.map((step) => step.bytes.toString('latin1')), { pipelined });
    expect(answers, 'every request on the connection was answered').toHaveLength(steps.length);
    let brands = 0;
    steps.forEach((step, index) => {
      const answer = answerOfRaw(answers[index]);
      const label = `request ${index + 1} (${step.name})`;
      if (typeof step.expectation === 'number') {
        expect(answer.status, label).toBe(step.expectation);
        for (const salon of salons) {
          expect(answer.body, `${label}: no salon's brand`).not.toContain(salon.name);
        }
      } else {
        expectBrandOf(answer, step.expectation, label);
        brands += 1;
      }
    });
    expect(brands, 'salons were really asked for on this connection').toBe(9);
  });
}

// --------------------------------------------------------------------------------------------
// Nothing but reading

test('TC-F01-S02-012 nothing but a read is accepted on the salon endpoint, and an attempt to write changes nothing', async ({ api }) => {
  await expectOwnerSeesBrand(alpha);
  await expectOwnerSeesBrand(beta);
  const takeover = JSON.stringify({ slug: beta.slug, name: 'Taken over', brandColour: '#000000', tenantId: beta.id, tenant_id: beta.id });
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
    const answer = await ask(hostOf(alpha), { method, body: takeover });
    expect(answer.status, method).toBe(405);
    expect(expectProblem(answer, method).code, method).toBe('method_not_allowed');
    expectNoInternals(answer, method);
    expectNothingOfOtherSalons(answer, null, method);
  }
  const options = await ask(hostOf(alpha), { method: 'OPTIONS' });
  const allowed = (options.headers['allow'] ?? '').split(',').map((method) => method.trim()).filter(Boolean).sort();
  expect(allowed, 'what OPTIONS says is allowed').toEqual(['GET', 'HEAD', 'OPTIONS']);
  const head = await ask(hostOf(alpha), { method: 'HEAD' });
  expect(head.status, 'HEAD').toBe(200);
  expect(head.body, 'HEAD has no body').toBe('');
  // Read back: every salon's brand is what it was.
  for (const salon of salons) {
    expectBrandOf(await answerOf(await api.salon(salon)), salon, `${salon.slug} afterwards`);
  }
});

test('TC-F01-S02-013 no answer of the salon endpoint shows how the product is built, sets a cookie or allows another origin', async () => {
  const answers: Array<[string, Answer]> = [
    ['200 on alpha', await ask(hostOf(alpha))],
    ['200 on beta', await ask(hostOf(beta))],
    ['200 on gamma', await ask(hostOf(gamma))],
    ['404 on an unknown slug', await ask(`Host: ${UNKNOWN_SLUG}.${PLATFORM_DOMAIN}`)],
    ['404 on the bare domain', await ask(`Host: ${PLATFORM_DOMAIN}`)],
    ['406', await ask(hostOf(alpha), { headers: ['Accept: application/xml'] })],
    ['405', await ask(hostOf(alpha), { method: 'DELETE' })],
    ['400', await ask(`Host: ${alpha.hostname}, ${beta.hostname}`)],
    ['a request from another salon\'s origin', await ask(hostOf(alpha), { headers: [`Origin: http://${beta.hostname}:${port}`] })],
    ['a preflight from another origin', await ask(hostOf(alpha), { method: 'OPTIONS', headers: ['Origin: http://attacker.example', 'Access-Control-Request-Method: GET'] })],
  ];
  const seen = new Set<number>();
  for (const [name, answer] of answers) {
    seen.add(answer.status);
    expectNoInternals(answer, name);
    expectNoTenantId(answer, name);
    expect(answer.headers['set-cookie'], `${name}: no cookie`).toBeUndefined();
    // Nothing signs in yet, so no origin has been given leave to read a salon's answers from a browser.
    expect(answer.headers['access-control-allow-origin'], `${name}: no origin is allowed`).toBeUndefined();
    expect(answer.headers['access-control-allow-credentials'], `${name}: no credentials are allowed`).toBeUndefined();
    if (answer.status >= 400) {
      expectProblem(answer, name);
    }
  }
  // Each kind of answer was really produced: a list of look-alike 200s would prove little.
  expect([...seen].sort()).toEqual([200, 400, 404, 405, 406]);
});

// --------------------------------------------------------------------------------------------
// AC-12

test('TC-F01-S02-014 the contract describes GET /api/v1/salon with its 200 and 404, and the running API agrees with it', async ({ api }) => {
  const contract = committedContract();
  const path = contract.paths[SALON_PATH] as Record<string, { responses: Record<string, { content?: Record<string, { schema?: { $ref?: string } }> }> }> | undefined;
  expect(path, `the contract describes ${SALON_PATH}`).toBeTruthy();
  expect(Object.keys(path!).sort(), 'only GET is described').toEqual(['get']);
  const responses = path!.get.responses;
  expect(responses['200']?.content?.['application/json']?.schema?.$ref, 'the 200 is a Salon').toBe('#/components/schemas/Salon');
  expect(responses['404']?.content?.['application/problem+json']?.schema?.$ref, 'the 404 is problem details').toBe('#/components/schemas/Problem');

  const described = contract.components.schemas.Salon;
  expect(Object.keys(described.properties ?? {}).sort(), 'the Salon schema has the three public fields and no id').toEqual(['brandColour', 'name', 'slug', 'timeZone']);
  expect([...(described.required ?? [])].sort(), 'all three are required').toEqual(['brandColour', 'name', 'slug', 'timeZone']);

  const validSalon = schemaValidator('Salon');
  for (const salon of salons) {
    const body = (await (await api.salon(salon)).json()) as SalonBrand;
    expect(validSalon(body), `${salon.slug} matches the Salon schema: ${JSON.stringify(validSalon.errors)}`).toBe(true);
    expect(body.brandColour, `${salon.slug}: #RRGGBB`).toMatch(/^#[0-9A-Fa-f]{6}$/);
    // The contract is the whole promise: a field it does not describe (a tenant id, say) is drift
    // the schema would let through and a client could not see.
    expect(Object.keys(body).sort(), `${salon.slug}: the fields answered are the fields described`).toEqual(Object.keys(described.properties ?? {}).sort());
  }

  const notFound = await answerOf(await api.getOn(`${UNKNOWN_SLUG}.${PLATFORM_DOMAIN}`, SALON_PATH));
  expect(notFound.status).toBe(404);
  expectProblem(notFound, 'the 404');

  // What the running API says of itself is what was committed, for this path and its schema.
  const served = await api.get('/v3/api-docs');
  expect(served.status(), 'the local profile serves its contract').toBe(200);
  const live = (await served.json()) as typeof contract;
  expect(sorted(live.paths[SALON_PATH]), 'the served description of the path').toEqual(sorted(contract.paths[SALON_PATH]));
  expect(sorted(live.components.schemas.Salon), 'the served Salon schema').toEqual(sorted(contract.components.schemas.Salon));
});

test('TC-F01-S02-015 the generated client exposes the endpoint: a call through it returns each salon\'s brand and a typed 404', async () => {
  for (const salon of salons) {
    const client = createApiClient({ baseUrl: salonUrl(salon, apiUrl) });
    const { data, error, response } = await client.GET('/api/v1/salon');
    expect(response.status, salon.slug).toBe(200);
    expect(error, salon.slug).toBeUndefined();
    // `data` is typed by the generated schema: these three fields exist on it or this file does not compile.
    const brand: SalonBrand | undefined = data;
    expect({ slug: brand?.slug, name: brand?.name, brandColour: brand?.brandColour }, salon.slug).toEqual({
      slug: salon.slug,
      name: salon.name,
      brandColour: salon.brandColour,
    });
  }
  const nobody = createApiClient({ baseUrl: apiUrl.replace(/\/\/[^/:]+/, `//${UNKNOWN_SLUG}.${PLATFORM_DOMAIN}`) });
  const { data, error, response } = await nobody.GET('/api/v1/salon');
  expect(response.status).toBe(404);
  expect(data).toBeUndefined();
  const problem: Problem | undefined = error;
  expect(problem?.code).toBe('not_found');
});
