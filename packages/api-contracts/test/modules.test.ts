import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { pathsByModule, renderClient, SCHEMA_FILE } from '../scripts/lib.mjs';
import * as branding from '../src/generated/branding';
import * as shared from '../src/generated/shared';

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const generatedDirectory = join(packageRoot, 'src', 'generated');
const committed = JSON.parse(readFileSync(resolve(packageRoot, '..', '..', 'docs', 'api', 'openapi.json'), 'utf8'));

const problem = { $ref: '#/components/schemas/Problem' };

function operation(operationId: string, tags: string[], schema = problem) {
  return {
    operationId,
    tags,
    responses: { 200: { description: 'OK', content: { 'application/json': { schema } } } },
  };
}

const document = {
  openapi: '3.1.0',
  info: { title: 'test', version: 'v1' },
  paths: {
    '/api/v1/booking/holds': { post: operation('hold', ['booking'], { $ref: '#/components/schemas/Hold' }) },
    '/api/v1/booking/holds/{id}': { delete: operation('release', ['booking']) },
    '/api/v1/payment/refunds': { post: operation('refund', ['payment']) },
  },
  components: {
    schemas: {
      Hold: { type: 'object', properties: { slot: { $ref: '#/components/schemas/Slot' } } },
      Slot: { type: 'string' },
      Problem: { type: 'object', properties: { code: { type: 'string' } } },
      Refund: { type: 'object' },
    },
  },
};

describe('module entries', () => {
  it('groups the paths by the module each operation is tagged with', () => {
    expect(pathsByModule(document)).toEqual(
      new Map([
        ['booking', ['/api/v1/booking/holds', '/api/v1/booking/holds/{id}']],
        ['payment', ['/api/v1/payment/refunds']],
      ]),
    );
  });

  it('generates one entry per module, naming only its paths, operations and the schemas they use', async () => {
    const files = await renderClient(JSON.stringify(document));
    const booking = files.get('booking.ts') ?? '';

    expect([...files.keys()]).toEqual([SCHEMA_FILE, 'booking.ts', 'payment.ts']);
    expect(booking).toContain('"/api/v1/booking/holds/{id}"');
    expect(booking).not.toContain('/api/v1/payment/refunds');
    expect(booking).toContain('"release"');
    expect(booking).not.toContain('"refund"');
    expect(booking).toContain('"Slot"');
    expect(booking).not.toContain('"Refund"');
  });

  it('refuses an operation with two tags, and a path whose operations name two modules', () => {
    const twoTags = { paths: { '/api/v1/booking/holds': { post: operation('hold', ['booking', 'payment']) } } };
    const twoModules = {
      paths: {
        '/api/v1/booking/holds': { post: operation('hold', ['booking']), get: operation('holds', ['payment']) },
      },
    };

    expect(() => pathsByModule(twoTags)).toThrow('2 tags');
    expect(() => pathsByModule(twoModules)).toThrow('booking, payment');
  });
});

describe('the committed module entries', () => {
  const exported = JSON.parse(readFileSync(join(packageRoot, 'package.json'), 'utf8')).exports as Record<
    string,
    string
  >;
  const modules = readdirSync(generatedDirectory)
    .filter((file) => file !== SCHEMA_FILE)
    .map((file) => file.replace(/\.ts$/, ''));

  it('are one for each module the API tags, and each is exported as @salon/api-contracts/<module>', () => {
    expect(modules.sort()).toEqual(['branding', 'identity', 'platform', 'shared', 'staff']);
    expect(modules.sort()).toEqual([...pathsByModule(committed).keys()]);
    for (const module of modules) {
      expect(exported[`./${module}`]).toBe(`./src/generated/${module}.ts`);
    }
    expect(exported['.']).toBe('./src/index.ts');
  });

  it("give a client that requests its module's paths", async () => {
    const requests: string[] = [];
    const fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push(new Request(input, init).url);
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    await shared.createApiClient({ baseUrl: 'http://api.test:8090', fetch }).GET('/api/v1/meta');
    await branding.createApiClient({ baseUrl: 'http://alpha.localhost:8090', fetch }).GET('/api/v1/salon');

    expect(requests).toEqual(['http://api.test:8090/api/v1/meta', 'http://alpha.localhost:8090/api/v1/salon']);
  });
});
