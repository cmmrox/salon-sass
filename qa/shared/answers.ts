/**
 * An answer from the API, however it was obtained (Playwright's request context or raw bytes on a
 * socket), and the assertions every test of an answer shares: it is problem details as the
 * contract describes them, it shows nothing of how the product is built, and it names no salon it
 * should not.
 */
import { expect, type APIResponse } from '@playwright/test';

import { mediaType, PROBLEM_JSON, type Problem, type SalonBrand } from './api-client';
import { internalsIn } from './leaks';
import { schemaValidator } from './openapi';
import type { RawResponse } from './raw-http';
import { salons, type Salon } from './tenants';

export type Answer = { status: number; contentType: string; headers: Record<string, string>; body: string };

/** A code is a stable, machine-readable word: lower case, joined with underscores. */
const CODE = /^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/;

/** Any UUID at all. A tenant id is one, and no answer of this API has a reason to carry any. */
const ANY_UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

export async function answerOf(response: APIResponse): Promise<Answer> {
  return {
    status: response.status(),
    contentType: mediaType(response),
    headers: response.headers(),
    body: await response.text(),
  };
}

export function answerOfRaw(response: RawResponse): Answer {
  return {
    status: response.status,
    contentType: (response.headers['content-type'] ?? '').split(';')[0].trim().toLowerCase(),
    headers: response.headers,
    body: response.body,
  };
}

/** Asserts the answer is problem details as the contract describes them, and returns the body. */
export function expectProblem(answer: Answer, label: string): Problem {
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
export function expectNoInternals(answer: Answer, label: string): void {
  expect(internalsIn(answer.body), `${label}: internals in the body: ${answer.body.slice(0, 500)}`).toEqual([]);
  const headers = Object.entries(answer.headers)
    // The date and the media type are not internals; their digits and dots are not versions.
    .filter(([name]) => !['date', 'content-type', 'content-length', 'keep-alive', 'connection', 'transfer-encoding', 'allow', 'vary', 'accept', 'accept-patch'].includes(name))
    .map(([name, value]) => `${name}: ${value}`)
    .join('\n');
  expect(internalsIn(headers), `${label}: internals in the headers:\n${headers}`).toEqual([]);
  for (const name of ['server', 'x-powered-by', 'x-application-context']) {
    expect(answer.headers[name], `${label}: the ${name} header`).toBeUndefined();
  }
}

function headerText(answer: Answer): string {
  return Object.entries(answer.headers)
    .map(([name, value]) => `${name}: ${value}`)
    .join('\n');
}

/** Asserts the answer carries no tenant id: none of the salons', and no UUID of any kind. */
export function expectNoTenantId(answer: Answer, label: string): void {
  const everything = `${headerText(answer)}\n${answer.body}`;
  for (const salon of salons) {
    expect(everything.toLowerCase(), `${label}: the tenant id of ${salon.slug}`).not.toContain(salon.id.toLowerCase());
  }
  expect(ANY_UUID.exec(everything)?.[0], `${label}: a UUID in the answer`).toBeUndefined();
}

/**
 * The public salon's fields: F01-S02's slug, name and colour, F01-S04's time zone, F04-S01's
 * published profile and F04-S02's published tagline, logo and cover. Nothing else.
 */
export const PUBLIC_SALON_FIELDS = ['brandColour', 'cover', 'logo', 'name', 'profile', 'slug', 'tagline', 'timeZone'];

/**
 * The ids the public salon is allowed to carry: its published logo's and cover's (F04-S02) and its
 * published branches' (F04-S01). Any other UUID in the answer is one it has no reason to carry.
 */
function publishedIds(brand: SalonBrand): string[] {
  const ids = [brand.logo?.id, brand.cover?.id, ...(brand.profile?.branches ?? []).map(branch => branch.id)];
  return ids.filter((id): id is string => typeof id === 'string').map(id => id.toLowerCase());
}

/**
 * Asserts the answer is exactly one salon's public brand: 200, JSON, its slug, name, colour and
 * time zone, the fields the contract adds since (tagline, logo, cover, profile) in the contract's
 * shape and no other field, no tenant id, no id the contract does not publish, and nothing of any
 * other salon.
 */
export function expectBrandOf(answer: Answer, of: Salon, label: string): SalonBrand {
  expect(answer.status, `${label}: status (body ${answer.body.slice(0, 200)})`).toBe(200);
  expect(answer.contentType, `${label}: content type`).toBe('application/json');
  const brand = JSON.parse(answer.body) as SalonBrand;
  // The whole field list: a field the contract does not promise is a failure here.
  expect(Object.keys(brand).sort(), `${label}: the public fields and no other`).toEqual(PUBLIC_SALON_FIELDS);
  expect({ slug: brand.slug, name: brand.name, brandColour: brand.brandColour, timeZone: brand.timeZone }, label)
    .toEqual({ slug: of.slug, name: of.name, brandColour: of.brandColour, timeZone: of.timeZone });
  const validate = schemaValidator('Salon');
  expect(validate(brand), `${label}: matches the Salon schema: ${JSON.stringify(validate.errors)}`).toBe(true);
  expectOnlyPublishedIds(answer, label);
  expectNothingOfOtherSalons(answer, of, label);
  return brand;
}

/**
 * For a 200 of the public salon: no salon's tenant id, and no UUID but the published logo's,
 * cover's and branches' ids. For any other answer this is `expectNoTenantId`: no UUID at all.
 */
export function expectOnlyPublishedIds(answer: Answer, label: string): void {
  if (answer.status !== 200 || answer.contentType !== 'application/json') {
    expectNoTenantId(answer, label);
    return;
  }
  const everything = `${headerText(answer)}\n${answer.body}`;
  for (const salon of salons) {
    expect(everything.toLowerCase(), `${label}: the tenant id of ${salon.slug}`).not.toContain(salon.id.toLowerCase());
  }
  const allowed = publishedIds(JSON.parse(answer.body) as SalonBrand);
  const uuids = [...everything.matchAll(new RegExp(ANY_UUID.source, 'gi'))].map(match => match[0].toLowerCase());
  expect(uuids.filter(uuid => !allowed.includes(uuid)), `${label}: a UUID the contract does not publish`).toEqual([]);
}

/** Asserts nothing in the answer belongs to a salon other than the one named (or to any, with none named). */
export function expectNothingOfOtherSalons(answer: Answer, of: Salon | null, label: string): void {
  const everything = `${headerText(answer)}\n${answer.body}`;
  for (const other of salons) {
    if (other === of) {
      continue;
    }
    for (const value of [other.id, other.name, other.brandColour, `"${other.slug}"`]) {
      expect(everything, `${label}: must show nothing of ${other.slug}`).not.toContain(value);
    }
  }
}
