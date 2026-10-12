/**
 * The committed contract, docs/api/openapi.json, as a test reads it: the document itself and a
 * validator for any schema it defines. The document is read from the repository; nothing in it is
 * restated here.
 */
import fs from 'node:fs';
import path from 'node:path';

import Ajv2020, { type ValidateFunction } from 'ajv/dist/2020';
import addFormats from 'ajv-formats';

import { REPO_ROOT } from './env';

export const OPENAPI_PATH = path.join(REPO_ROOT, 'docs', 'api', 'openapi.json');

type OpenApiDocument = {
  openapi: string;
  paths: Record<string, Record<string, unknown>>;
  components: { schemas: Record<string, { required?: string[]; properties?: Record<string, unknown> }> };
};

export function committedContract(): OpenApiDocument {
  return JSON.parse(fs.readFileSync(OPENAPI_PATH, 'utf8')) as OpenApiDocument;
}

/**
 * A validator for `#/components/schemas/<name>`. Fails loudly when the document has no such
 * schema: a validator built from nothing would accept anything.
 */
export function schemaValidator(name: string): ValidateFunction {
  const document = committedContract();
  if (!document.components?.schemas?.[name]) {
    throw new Error(`docs/api/openapi.json defines no schema named ${name}`);
  }
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  // Annotation keywords OpenAPI adds to JSON Schema, and the place it keeps its schemas.
  ajv.addVocabulary(['example', 'xml', 'externalDocs', 'discriminator', 'components']);
  // The document is not itself a schema, so the schema under test is referred to inside it: every
  // `#/components/schemas/...` reference in the document then resolves exactly as written.
  const validate = ajv.compile({
    $id: 'https://contract.test/openapi.json',
    $ref: `#/components/schemas/${name}`,
    components: { schemas: document.components.schemas },
  });
  return validate;
}

/** A value with its object keys sorted at every level, so two documents compare by content. */
export function sorted(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sorted);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
        .map(([key, inner]) => [key, sorted(inner)]),
    );
  }
  return value;
}
