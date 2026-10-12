/**
 * F01-S01: what the API says about itself when everything is up. Expected results are the stage's
 * acceptance criteria AC-2, AC-3 and AC-5 and the committed contract, docs/api/openapi.json.
 */
import { execFileSync } from 'node:child_process';

import { mediaType, type Meta } from '@qa/api-client';
import { REPO_ROOT } from '@qa/env';
import { expect, test } from '@qa/fixtures';
import { committedContract, schemaValidator, sorted } from '@qa/openapi';

/** An ISO-8601 instant in UTC: a date, a time, and the letter Z (never an offset, never local). */
const UTC_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;

test('TC-F01-S01-010 health answers 200 with status UP and a database component that is UP', async ({ api }) => {
  const response = await api.health();

  expect(response.status()).toBe(200);
  const body = await response.json();
  expect(body.status).toBe('UP');
  expect(body.components, 'health lists its components').toBeTruthy();
  expect(body.components.db, 'health has a database component named db').toBeTruthy();
  expect(body.components.db.status).toBe('UP');
});

test('TC-F01-S01-011 meta answers 200 with service, version, commit and time, and matches the committed contract', async ({ api }) => {
  const contract = committedContract();
  expect(contract.paths['/api/v1/meta']?.get, 'the contract describes GET /api/v1/meta').toBeTruthy();
  const validate = schemaValidator('Meta');

  const response = await api.meta();

  expect(response.status()).toBe(200);
  expect(mediaType(response)).toBe('application/json');
  const body = (await response.json()) as Meta;
  for (const field of ['service', 'version', 'commit', 'time'] as const) {
    expect(typeof body[field], `${field} is a string`).toBe('string');
    expect(body[field].length, `${field} is not empty`).toBeGreaterThan(0);
  }
  expect(validate(body), `the body matches the Meta schema: ${JSON.stringify(validate.errors)}`).toBe(true);
  // The contract is the whole promise: a field it does not describe is drift the client cannot see.
  const described = Object.keys(contract.components.schemas.Meta.properties ?? {}).sort();
  expect(Object.keys(body).sort()).toEqual(described);
  expect(body.time).toMatch(UTC_INSTANT);
});

test('TC-F01-S01-012 meta reports the server\'s time now, as a UTC instant, on every request', async ({ api }) => {
  const before = Date.now();
  const first = (await (await api.meta()).json()) as Meta;
  await expect
    .poll(async () => ((await (await api.meta()).json()) as Meta).time, {
      message: 'a later request reports a later time: the answer is not cached',
      timeout: 5_000,
    })
    .not.toBe(first.time);
  const second = (await (await api.meta()).json()) as Meta;
  const after = Date.now();

  for (const body of [first, second]) {
    expect(body.time).toMatch(UTC_INSTANT);
  }
  const firstAt = Date.parse(first.time);
  const secondAt = Date.parse(second.time);
  expect(Number.isNaN(firstAt)).toBe(false);
  // The API runs on this machine, so its clock is this clock. A time in another zone written with
  // a Z would be hours out; two seconds allows for rounding and nothing else.
  expect(firstAt).toBeGreaterThanOrEqual(before - 2_000);
  expect(secondAt).toBeLessThanOrEqual(after + 2_000);
  expect(secondAt).toBeGreaterThan(firstAt);
});

test('TC-F01-S01-013 meta reports the commit the running build was made from', async ({ api }) => {
  const body = (await (await api.meta()).json()) as Meta;

  expect(body.commit, 'commit is a full commit id, not a placeholder').toMatch(/^[0-9a-f]{40}$/);
  // It comes from the build, so it is a commit of this repository.
  const type = execFileSync('git', ['-C', REPO_ROOT, 'cat-file', '-t', body.commit], { encoding: 'utf8' }).trim();
  expect(type).toBe('commit');
});

test('TC-F01-S01-014 the contract the running API serves is the committed docs/api/openapi.json', async ({ api }) => {
  const response = await api.get('/v3/api-docs');

  expect(response.status(), 'the local profile serves the OpenAPI document').toBe(200);
  const served = await response.json();
  expect(Object.keys(served.paths ?? {}).length, 'the served document describes at least one path').toBeGreaterThan(0);
  expect(sorted(served)).toEqual(sorted(committedContract()));
});

test('TC-F01-S01-019 with the local profile, info reports Java 25 and that virtual threads are enabled', async ({ api }) => {
  const response = await api.info();

  expect(response.status()).toBe(200);
  const body = await response.json();
  // docs/architecture/tech-stack.md: Java 25, virtual threads on.
  expect(body.runtime, 'info has a runtime section').toBeTruthy();
  expect(body.runtime.javaMajorVersion).toBe(25);
  expect(body.runtime.virtualThreads).toBe(true);
  expect(String(body.java?.version ?? ''), 'the JVM that is running is Java 25').toMatch(/^25(\.|$)/);
});

test('TC-F01-S01-020 no actuator endpoint that exposes configuration, memory or control is served', async ({ api }) => {
  const index = await api.get('/actuator');
  const links = index.status() === 200 ? Object.keys((await index.json())._links ?? {}) : [];
  // Proves the list below is checked against a live actuator, not against one that is switched off.
  expect((await api.health()).status()).toBe(200);
  expect(links.filter((name) => !['self', 'health', 'health-path', 'info'].includes(name))).toEqual([]);

  const sensitive = [
    'env', 'beans', 'configprops', 'heapdump', 'threaddump', 'mappings', 'loggers', 'metrics',
    'shutdown', 'logfile', 'conditions', 'scheduledtasks', 'flyway', 'caches', 'sbom', 'startup',
  ];
  for (const name of sensitive) {
    const response = await api.get(`/actuator/${name}`);
    expect(response.status(), `GET /actuator/${name}`).toBe(404);
  }
  const shutdown = await api.send('POST', '/actuator/shutdown');
  expect([404, 405], 'POST /actuator/shutdown is refused').toContain(shutdown.status());
  expect((await api.health()).status(), 'the API is still up afterwards').toBe(200);
});
