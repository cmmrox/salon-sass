/**
 * An answer checked against the committed contract for its operation and status: the status is one the operation
 * documents, and the body has the documented media type and schema (or no body when none is documented). The schema
 * is the one the document declares for that response, never one a test picks.
 */
import type { APIResponse } from '@playwright/test';

import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';

import { expect } from './fixtures';
import { committedContract } from './openapi';

type Response = { content?: Record<string, { schema: unknown }> };
export type Operation = { operationId: string; responses: Record<string, Response> };

const contract = committedContract();
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema({ $id: 'https://contract.test/openapi.json', components: contract.components });

export function operation(method: string, template: string): Operation {
  const op = (contract.paths[template] as Record<string, Operation> | undefined)?.[method.toLowerCase()];
  expect(op, `${method} ${template} is in the contract`).toBeTruthy();
  return op!;
}

/** The problem codes the contract lists. */
export const problemCodes = () => (contract.components.schemas.Problem as { properties: { code: { enum: string[] } } }).properties.code.enum;

/** Asserts the answer is documented for the operation and status, and returns the status. */
export async function asDocumented(answer: APIResponse, method: string, template: string, label: string): Promise<string> {
  const op = operation(method, template);
  const status = String(answer.status());
  const documented = op.responses[status];
  expect(documented, `${label}: ${op.operationId} documents ${status} (it documents ${Object.keys(op.responses).join(', ')})`).toBeTruthy();
  const body = await answer.text();
  if (!documented.content) {
    expect(body, `${label}: ${status} has no body`).toBe('');
    return status;
  }
  const mediaType = (answer.headers()['content-type'] ?? '').split(';')[0].trim();
  const declared = documented.content[mediaType];
  expect(declared, `${label}: ${status} as ${mediaType} is documented (${Object.keys(documented.content).join(', ')})`).toBeTruthy();
  const schema = JSON.parse(JSON.stringify(declared.schema).replace(/"#\/components\//g, '"https://contract.test/openapi.json#/components/'));
  const validate = ajv.compile(schema);
  expect(validate(JSON.parse(body)), `${label}: ${status} body matches the contract: ${JSON.stringify(validate.errors)?.slice(0, 400)}`).toBe(true);
  return status;
}

/** Records each status seen per operation, and checks at the end that every documented success and 4xx was seen. */
export class Seen {
  private readonly seen = new Map<string, Set<string>>();
  async check(answer: APIResponse | Promise<APIResponse>, method: string, template: string, label: string) {
    const status = await asDocumented(await answer, method, template, label);
    const key = `${method} ${template}`;
    this.seen.set(key, (this.seen.get(key) ?? new Set()).add(status));
  }
  /** `notProvoked`: documented statuses a test cannot reach from outside, each with its reason in the caller. */
  expectAllSeen(operations: [string, string][], notProvoked: Record<string, string[]> = {}) {
    for (const [method, template] of operations) {
      const name = `${method} ${template}`;
      const documented = Object.keys(operation(method, template).responses).filter(st => /^[24]\d\d$/.test(st));
      const missed = documented.filter(st => !this.seen.get(name)?.has(st) && !(notProvoked[name] ?? []).includes(st));
      expect(missed, `${name}: every documented answer was seen`).toEqual([]);
    }
  }
}
