import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { compareDocument, findStaleFiles, renderClient, SCHEMA_FILE } from '../scripts/lib.mjs';

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const committedDocument = resolve(packageRoot, '..', '..', 'docs', 'api', 'openapi.json');
const generatedDirectory = join(packageRoot, 'src', 'generated');

const document = JSON.stringify({
  openapi: '3.1.0',
  info: { title: 'test', version: 'v1' },
  paths: {
    '/things': {
      get: {
        operationId: 'listThings',
        responses: { 200: { description: 'OK', content: { 'application/json': { schema: { type: 'string' } } } } },
      },
    },
  },
});

let directory: string;

beforeEach(() => {
  directory = mkdtempSync(join(tmpdir(), 'api-contracts-'));
});

afterEach(() => {
  rmSync(directory, { recursive: true, force: true });
});

describe('renderClient', () => {
  it('generates the schema file from the document, marked as generated', async () => {
    const files = await renderClient(document);

    expect([...files.keys()]).toEqual([SCHEMA_FILE]);
    expect(files.get(SCHEMA_FILE)).toContain('auto-generated');
    expect(files.get(SCHEMA_FILE)).toContain('"/things"');
    expect(files.get(SCHEMA_FILE)).toContain('listThings');
  });

  it('generates the same bytes every time', async () => {
    expect(await renderClient(document)).toEqual(await renderClient(document));
  });
});

describe('findStaleFiles', () => {
  const expected = new Map([[SCHEMA_FILE, 'export type A = 1;\n']]);

  it('finds nothing when the folder holds exactly what is expected', () => {
    writeFileSync(join(directory, SCHEMA_FILE), 'export type A = 1;\n');

    expect(findStaleFiles(expected, directory)).toEqual([]);
  });

  it('names a file whose content differs', () => {
    writeFileSync(join(directory, SCHEMA_FILE), 'export type A = 2;\n');

    expect(findStaleFiles(expected, directory)).toEqual([{ file: SCHEMA_FILE, reason: 'different' }]);
  });

  it('names a file that is missing, and reports a missing folder the same way', () => {
    expect(findStaleFiles(expected, directory)).toEqual([{ file: SCHEMA_FILE, reason: 'missing' }]);
    expect(findStaleFiles(expected, join(directory, 'absent'))).toEqual([{ file: SCHEMA_FILE, reason: 'missing' }]);
  });

  it('names a file that should not be there', () => {
    writeFileSync(join(directory, SCHEMA_FILE), 'export type A = 1;\n');
    writeFileSync(join(directory, 'by-hand.ts'), 'export {};\n');

    expect(findStaleFiles(expected, directory)).toEqual([{ file: 'by-hand.ts', reason: 'unexpected' }]);
  });
});

describe('compareDocument', () => {
  it('tells a current document from a different and a missing one', () => {
    mkdirSync(join(directory, 'docs'));
    const path = join(directory, 'docs', 'openapi.json');

    expect(compareDocument(document, path)).toBe('missing');
    writeFileSync(path, document);
    expect(compareDocument(document, path)).toBe('current');
    writeFileSync(path, `${document}\n`);
    expect(compareDocument(document, path)).toBe('different');
  });
});

describe('the committed contract', () => {
  const committed = JSON.parse(readFileSync(committedDocument, 'utf8'));

  it('describes what a suspended salon answers: 403 with the code salon_suspended', () => {
    const forbidden = committed.paths['/api/v1/salon'].get.responses['403'];

    expect(forbidden.description).toContain('`salon_suspended`');
    expect(forbidden.content['application/problem+json'].schema.$ref).toBe('#/components/schemas/Problem');
    expect(committed.components.schemas.Problem.properties.code.enum).toContain('salon_suspended');
  });

  it('lists every error code once, gives every problem a trace id, and names the fields of a validation failure', () => {
    const problem = committed.components.schemas.Problem;

    expect(problem.properties.code.enum).toEqual([
      'invalid_client_assertion',
      'confirmation_required',
      'invalid_code',
      'code_expired',
      'provider_unavailable',
      'invalid_credentials',
      'invalid_session',
      'permission_denied',
      'conflict',
      'rate_limited',
      'bad_request',
      'not_found',
      'method_not_allowed',
      'not_acceptable',
      'unsupported_media_type',
      'request_rejected',
      'service_unavailable',
      'salon_suspended',
      'internal_error',
    ]);
    expect(new Set(problem.properties.code.enum).size).toBe(problem.properties.code.enum.length);
    expect(problem.required).toEqual(expect.arrayContaining(['code', 'status', 'title', 'traceId']));
    expect(problem.properties.traceId.pattern).toBe('^[0-9a-f]{32}$');
    expect(problem.properties.errors.items.required).toEqual(['detail', 'field']);
  });

  it('gives a salon its time zone', () => {
    expect(committed.components.schemas.Salon.required).toContain('timeZone');
  });

  it('describes nothing of the local test hooks or the meters', () => {
    const text = JSON.stringify(committed);

    expect(Object.keys(committed.paths).every((path) => path.startsWith('/api/v1/'))).toBe(true);
    expect(text).not.toContain('/local/');
    expect(text).not.toContain('actuator');
    expect(text).not.toContain('probe');
  });

  it('exposes the code in the generated client', () => {
    const schema = readFileSync(join(generatedDirectory, SCHEMA_FILE), 'utf8');

    expect(schema).toContain('"salon_suspended"');
    expect(schema).toMatch(/getSalon: \{[\s\S]*?403: \{/);
  });
});

describe('the committed client', () => {
  // The quick half of `pnpm contracts:check`: it needs no API. Whether the committed document is
  // what the controllers generate is the other half, and needs the API build.
  it('is what the committed OpenAPI document generates', async () => {
    const files = await renderClient(readFileSync(committedDocument, 'utf8'));

    expect(findStaleFiles(files, generatedDirectory)).toEqual([]);
  });
});
