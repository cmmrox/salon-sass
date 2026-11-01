/**
 * F05-S01 catalogue fixtures: the categories, services, photos and public menu endpoints, input
 * builders, the prototype's ten default categories, and read-only database observations of the
 * stage's tables.
 *
 * Routes, bodies, codes and limits come from the F05-S01 spec (acceptance criteria, "API and data
 * changes", "Design notes"), decisions 091 and 093, and the generated contract once it exists.
 * Written before the build; every name only the build gives (a table, an audit action, a contract
 * schema, a field of the menu, a BFF route) is in `PENDING_F05_S01` below, confirmed against the
 * F05-S01 handoff and "Corrections at close" at the dry run (34b26fa).
 *
 * A salon keeps its catalogue between runs, and alpha must never fill up to the limits (50
 * categories, 300 services), so a test that changes a catalogue uses a salon the operator creates
 * now (`newSalon`). A test that needs the fixed salons' staff roles works on alpha or beta with
 * names carrying a run marker and removes what it made (`removeMarked`).
 */
import { randomBytes } from 'node:crypto';

import { request, type APIRequestContext, type APIResponse } from '@playwright/test';

import { refused } from './booking-rules';
import { apiUrl } from './env';
import { expect, json, type Identity, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { onSalonHost, ownerSession, sqlLiteral, type Operator, type SalonInput } from './operator';
import type { FileUpload } from './salon-brand';
import type { Salon } from './tenants';

/**
 * Names the spec leaves to the build, as the F05-S01 handoff, V25__create_catalogue.sql and the
 * generated contract give them (confirmed at the dry run, 34b26fa). Nothing else in the F05 tests
 * spells these out.
 */
export const PENDING_F05_S01 = {
  /** The newest migration before this stage: V23 (V24 adds branding_default_branch_id, V25 the catalogue). */
  migrationBefore: 23,
  /** The terms version table (one immutable row per version). Other catalogue tables are found by prefix. */
  termsTable: 'catalogue_terms_version',
  /** The category table, for the backfill check. */
  categoryTable: 'catalogue_category',
  /** Every table this stage adds (category list, category, photo, service, terms version) starts with this. */
  tablePrefix: 'catalogue_',
  /** Audit actions of this stage: catalogue.category_added, ..., catalogue.service_photo_removed (handoff, AC-15). */
  auditPrefix: 'catalogue.',
  /** Contract schemas (docs/api/openapi.json, components.schemas). */
  schemas: {
    categoryList: 'CatalogueCategoryList',
    category: 'CatalogueCategory',
    serviceList: 'CatalogueServiceList',
    service: 'CatalogueService',
    menu: 'Menu',
  },
  /** `GET /services` returns `{items: [...]}`, as the category list does. */
  serviceListItems: 'items',
  /** The multipart field of a photo upload. */
  photoField: 'file',
  /** A menu service's fields for price, the customer's time, the photo and the current terms version. */
  menuFields: { price: 'priceMinor', minutes: 'durationMinutes', photo: 'photoUrl', termsVersion: 'termsVersionId' },
  /** The web's photo route on a salon hostname (Corrections at close). */
  webPhotoPath: (photoId: string) => `/service-photos/${photoId}`,
  /** The browser's data calls for the Services page (`/identity-web/staff/catalogue-*`), for holding or failing them. */
  bffMatch: (url: URL) => url.pathname.startsWith('/identity-web/') && url.pathname.includes('/catalogue-'),
} as const;

export const CATALOGUE = '/api/v1/catalogue';
export const CATEGORIES = `${CATALOGUE}/categories`;
export const SERVICES = `${CATALOGUE}/services`;
export const MENU = `${CATALOGUE}/menu`;
export const categoryPath = (id: string) => `${CATEGORIES}/${id}`;
export const servicePath = (id: string) => `${SERVICES}/${id}`;
export const photoPath = (photoId: string) => `${CATALOGUE}/photos/${photoId}`;

/** AC-1: the prototype's ten categories, in this order. */
export const DEFAULT_CATEGORIES = [
  'Cuts & styling', 'Colour', 'Facials & skin', 'Nails', 'Brows & lashes', 'Waxing', 'Makeup & draping', 'Massage & spa',
  'Bridal', 'Consultations',
] as const;

/** The spec's limits (AC-2, AC-3, AC-10), in whole rupees and minutes. */
export const LIMITS = {
  categoryName: [2, 30], serviceName: [2, 40], description: 90, price: [100, 500_000], minutes: [5, 480], buffer: [0, 60],
  step: 5, categories: 50, services: 300, photoBytes: 5 * 1024 * 1024, photoMinSide: 320, photoMaxSide: 800,
} as const;

// ---- shapes, from the spec's "API and data changes" until the contract exists (then from @contracts/schema) ----

export type Category = { id: string; name: string; position: number; serviceCount: number; version: number };
export type CategoryList = { listVersion: number; items: Category[] };
export type Segment = { kind: string; minutes: number };
export type Terms = {
  versionId: string; number: number; priceMinor: number; currency: string; segments: Segment[]; durationMinutes: number; bufferMinutes: number;
};
export type Service = {
  id: string; name: string; categoryId: string; description: string | null; bookableOnline: boolean; photoUrl: string | null; version: number; terms: Terms;
};
export type ServiceInput = {
  name: string; categoryId: string; description?: string | null; priceMinor: number; durationMinutes: number; bufferMinutes: number; bookableOnline?: boolean;
};
export type MenuService = Record<string, unknown> & { id: string; name: string; description: string | null };
export type Menu = { currency: string; categories: { id: string; name: string; services: MenuService[] }[] };

/** A menu service's field by its meaning (`PENDING_F05_S01.menuFields`). */
export const menuField = (service: MenuService, field: keyof typeof PENDING_F05_S01.menuFields) => service[PENDING_F05_S01.menuFields[field]];

// ---- builders ----

/** A short random marker, for names that must be unique in a salon and findable afterwards. */
export const marker = () => randomBytes(3).toString('hex');

/** Whole rupees as minor units (LKR has two fraction digits). */
export const rupees = (whole: number) => whole * 100;

/** A valid service: Rs. 2,500, 45 minutes, a 10-minute buffer, bookable online. */
export const serviceInput = (categoryId: string, overrides: Partial<ServiceInput> = {}): ServiceInput => ({
  name: `QA service ${marker()}`, categoryId, description: 'A service QA made.', priceMinor: rupees(2500), durationMinutes: 45, bufferMinutes: 10,
  bookableOnline: true, ...overrides,
});

/** The fields a client sends back to update a service: its current values with changes applied. */
export const updateOf = (service: Service, change: Partial<ServiceInput> = {}) => ({
  name: service.name, categoryId: service.categoryId, description: service.description, priceMinor: service.terms.priceMinor,
  durationMinutes: service.terms.durationMinutes, bufferMinutes: service.terms.bufferMinutes, bookableOnline: service.bookableOnline,
  ...change, version: service.version,
});

/** AC-5: the segments a service's time and buffer must be stored as, in order. */
export const segmentsOf = (minutes: number, buffer: number): Segment[] =>
  buffer > 0 ? [{ kind: 'active', minutes }, { kind: 'buffer', minutes: buffer }] : [{ kind: 'active', minutes }];

// ---- the API ----

export class Catalogue {
  private uploads: APIRequestContext | null = null;
  constructor(readonly identity: Identity) {}

  call(salon: Salon, method: string, route: string, tokens?: Tokens, data?: unknown): Promise<APIResponse> {
    return onSalonHost(this.identity, salon, method, route, tokens, data);
  }

  // categories
  async categories(salon: Salon, tokens: Tokens): Promise<CategoryList> {
    return json<CategoryList>(await this.call(salon, 'GET', CATEGORIES, tokens), PENDING_F05_S01.schemas.categoryList);
  }
  addCategoryRaw(salon: Salon, tokens: Tokens | undefined, name: unknown) { return this.call(salon, 'POST', CATEGORIES, tokens, { name }); }
  async addCategory(salon: Salon, tokens: Tokens, name: string): Promise<Category> {
    return json<Category>(await this.addCategoryRaw(salon, tokens, name), PENDING_F05_S01.schemas.category, 201);
  }
  renameCategoryRaw(salon: Salon, tokens: Tokens | undefined, id: string, name: unknown, version: number) {
    return this.call(salon, 'PUT', categoryPath(id), tokens, { name, version });
  }
  async renameCategory(salon: Salon, tokens: Tokens, category: Category, name: string): Promise<Category> {
    return json<Category>(await this.renameCategoryRaw(salon, tokens, category.id, name, category.version), PENDING_F05_S01.schemas.category);
  }
  moveRaw(salon: Salon, tokens: Tokens | undefined, id: string, direction: string, listVersion: number) {
    return this.call(salon, 'POST', `${categoryPath(id)}/move`, tokens, { direction, listVersion });
  }
  async move(salon: Salon, tokens: Tokens, id: string, direction: 'up' | 'down', listVersion?: number): Promise<CategoryList> {
    const from = listVersion ?? (await this.categories(salon, tokens)).listVersion;
    return json<CategoryList>(await this.moveRaw(salon, tokens, id, direction, from), PENDING_F05_S01.schemas.categoryList);
  }
  deleteCategoryRaw(salon: Salon, tokens: Tokens | undefined, id: string, version: number) {
    return this.call(salon, 'DELETE', `${categoryPath(id)}?version=${version}`, tokens);
  }
  async deleteCategory(salon: Salon, tokens: Tokens, category: Category) {
    const r = await this.deleteCategoryRaw(salon, tokens, category.id, category.version);
    expect(r.status(), `delete category ${category.name} (body ${(await r.text()).slice(0, 200)})`).toBe(204);
  }
  /** The salon's category by name, failing when it is not there. */
  async category(salon: Salon, tokens: Tokens, name: string): Promise<Category> {
    const found = (await this.categories(salon, tokens)).items.find(c => c.name === name);
    expect(found, `category "${name}" exists`).toBeTruthy();
    return found!;
  }

  // services
  async services(salon: Salon, tokens: Tokens): Promise<Service[]> {
    const body = await json<Record<string, Service[]>>(await this.call(salon, 'GET', SERVICES, tokens), PENDING_F05_S01.schemas.serviceList);
    return body[PENDING_F05_S01.serviceListItems];
  }
  serviceRaw(salon: Salon, tokens: Tokens | undefined, id: string) { return this.call(salon, 'GET', servicePath(id), tokens); }
  async service(salon: Salon, tokens: Tokens, id: string): Promise<Service> {
    return json<Service>(await this.serviceRaw(salon, tokens, id), PENDING_F05_S01.schemas.service);
  }
  createRaw(salon: Salon, tokens: Tokens | undefined, input: unknown) { return this.call(salon, 'POST', SERVICES, tokens, input); }
  async create(salon: Salon, tokens: Tokens, input: ServiceInput): Promise<Service> {
    return json<Service>(await this.createRaw(salon, tokens, input), PENDING_F05_S01.schemas.service, 201);
  }
  updateRaw(salon: Salon, tokens: Tokens | undefined, id: string, body: unknown) { return this.call(salon, 'PUT', servicePath(id), tokens, body); }
  async update(salon: Salon, tokens: Tokens, service: Service, change: Partial<ServiceInput>): Promise<Service> {
    return json<Service>(await this.updateRaw(salon, tokens, service.id, updateOf(service, change)), PENDING_F05_S01.schemas.service);
  }
  duplicateRaw(salon: Salon, tokens: Tokens | undefined, id: string) { return this.call(salon, 'POST', `${servicePath(id)}/duplicate`, tokens); }
  async duplicate(salon: Salon, tokens: Tokens, id: string): Promise<Service> {
    return json<Service>(await this.duplicateRaw(salon, tokens, id), PENDING_F05_S01.schemas.service, 201);
  }
  onlineRaw(salon: Salon, tokens: Tokens | undefined, id: string, bookableOnline: boolean, version: number) {
    return this.call(salon, 'PUT', `${servicePath(id)}/online`, tokens, { bookableOnline, version });
  }
  async online(salon: Salon, tokens: Tokens, service: Service, bookableOnline: boolean): Promise<Service> {
    return json<Service>(await this.onlineRaw(salon, tokens, service.id, bookableOnline, service.version), PENDING_F05_S01.schemas.service);
  }
  deleteRaw(salon: Salon, tokens: Tokens | undefined, id: string, version: number) {
    return this.call(salon, 'DELETE', `${servicePath(id)}?version=${version}`, tokens);
  }
  async remove(salon: Salon, tokens: Tokens, service: Service) {
    const r = await this.deleteRaw(salon, tokens, service.id, service.version);
    expect(r.status(), `delete service ${service.name} (body ${(await r.text()).slice(0, 200)})`).toBe(204);
  }

  // photos
  /** A multipart photo upload on the salon's hostname; `file` null sends none, to test the refusal. */
  async uploadRaw(salon: Salon, tokens: Tokens | undefined, serviceId: string, file: FileUpload | null): Promise<APIResponse> {
    this.uploads ??= await request.newContext({ baseURL: apiUrl, timeout: 60_000, maxRedirects: 0 });
    const multipart: Record<string, FileUpload> = {};
    if (file) multipart[PENDING_F05_S01.photoField] = file;
    return this.uploads.fetch(`${servicePath(serviceId)}/photo`, { method: 'PUT', multipart,
      headers: { Host: salon.hostname, ...(tokens ? { Authorization: `Bearer ${tokens.accessToken}` } : {}) } });
  }
  async upload(salon: Salon, tokens: Tokens, serviceId: string, file: FileUpload): Promise<Service> {
    const service = await json<Service>(await this.uploadRaw(salon, tokens, serviceId, file), PENDING_F05_S01.schemas.service);
    expect(service.photoUrl, 'the service now has a photo').toBeTruthy();
    return service;
  }
  removePhotoRaw(salon: Salon, tokens: Tokens | undefined, serviceId: string) { return this.call(salon, 'DELETE', `${servicePath(serviceId)}/photo`, tokens); }
  /** A photo read on a hostname with no credential (photos are public on their own salon's host). */
  photo(host: string, photoId: string): Promise<APIResponse> { return this.identity.api.getOn(host, photoPath(photoId)); }

  // the public menu
  menuRaw(host: string): Promise<APIResponse> { return this.identity.api.getOn(host, MENU); }
  async menu(salon: Salon): Promise<Menu> { return json<Menu>(await this.menuRaw(salon.hostname), PENDING_F05_S01.schemas.menu); }

  /** Removes the services, then the categories, whose names carry `mark` (alpha and beta only; never fails the test). */
  async removeMarked(salon: Salon, tokens: Tokens, mark: string) {
    try {
      for (const s of (await this.services(salon, tokens)).filter(x => x.name.includes(mark))) await this.deleteRaw(salon, tokens, s.id, s.version);
      for (const c of (await this.categories(salon, tokens)).items.filter(x => x.name.includes(mark))) await this.deleteCategoryRaw(salon, tokens, c.id, c.version);
    } catch { /* cleanup is best effort; a leftover carries the marker and fails no other test */ }
  }

  async dispose() { await this.uploads?.dispose(); }
}

/** The photo id in a photo URL: its last path segment (the URL is the API's or the web's). */
export const photoIdOf = (url: string) => new URL(url, 'http://placeholder').pathname.split('/').filter(Boolean).pop()!;

/** A salon the operator creates now, with its Owner signed in (MFA enrolled). */
export async function newSalon(operator: Operator, identity: Identity, overrides: Partial<SalonInput> = {}) {
  const admin = await operator.actor();
  const plan = await operator.plan(admin);
  const { input, created, salon } = await operator.salon(admin, plan.id, overrides);
  const owner = await ownerSession(identity, salon, input);
  return { admin, input, created, salon, owner };
}

// ---- answers ----

/** A 400 bad_request whose every field error is on `field` (a dotted path may end in it). */
export async function fieldRefusal(response: APIResponse, field: string, label: string) {
  const { problem, answer } = await refused(response, 400, 'bad_request', label);
  const fields = (problem.errors ?? []).map(e => e.field ?? '');
  expect(fields.length, `${label}: names the field (${answer.body.slice(0, 300)})`).toBeGreaterThan(0);
  for (const f of fields) expect(f, `${label}: the error is on ${field}`).toMatch(new RegExp(`(^|\\.)${field}$`));
}

/** A problem body without its per-request fields, for "the same answer as an unknown id". */
export const comparable = (text: string) => text.replace(/"(traceId|instance|detail)":"[^"]*"/g, '');

// ---- database observations (owner role unless a tenant is given; never writes outside a rolled-back transaction) ----

/** Every table this stage added: the `catalogue_` tables with a `tenant_id`. */
export function catalogueTables(): string[] {
  const out = identitySql(`SELECT table_name FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'tenant_id'
    AND table_name LIKE ${sqlLiteral(`${PENDING_F05_S01.tablePrefix}%`)} ORDER BY 1`);
  const tables = out.split('\n').filter(Boolean);
  expect(tables.length, 'the catalogue tables (categories, services, terms versions, photos)').toBeGreaterThanOrEqual(4);
  return tables;
}

/** A digest of every catalogue row of a salon, to prove a refused call changed nothing. */
export function catalogueSnapshot(salonId: string): string {
  return catalogueTables().map(t => identitySql(
    `SELECT coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text)), 'none') FROM ${t} x WHERE tenant_id = ${sqlLiteral(salonId)}`)).join(',');
}

/** How many rows of a salon each catalogue table holds. */
export function catalogueRows(salonId: string): Record<string, number> {
  return Object.fromEntries(catalogueTables().map(t => [t, Number(identitySql(`SELECT count(*) FROM ${t} WHERE tenant_id = ${sqlLiteral(salonId)}`))]));
}

/** How many terms versions a salon has. */
export const termsRows = (salonId: string) => Number(identitySql(`SELECT count(*) FROM ${PENDING_F05_S01.termsTable} WHERE tenant_id = ${sqlLiteral(salonId)}`));

/** One terms version's whole stored row, as text (with its segments when they are a column), by its id. */
export const termsRow = (salonId: string, versionId: string) => identitySql(
  `SELECT row_to_json(x)::text FROM ${PENDING_F05_S01.termsTable} x WHERE tenant_id = ${sqlLiteral(salonId)} AND id = ${sqlLiteral(versionId)}`);

/** Every catalogue row of a salon whose stored text mentions `word` (segments wherever they are kept). */
export const rowsMentioning = (salonId: string, word: string) => catalogueTables().reduce((n, t) => n + Number(identitySql(
  `SELECT count(*) FROM ${t} x WHERE tenant_id = ${sqlLiteral(salonId)} AND row_to_json(x)::text ILIKE ${sqlLiteral(`%${word}%`)}`)), 0);

export type AuditRow = { action: string; actorKind: string; actorId: string; subjectId: string; at: string; details: string };

/** The salon's catalogue audit entries from `after` on, oldest first. */
export function catalogueAudit(salonId: string, after: string): AuditRow[] {
  const out = identitySql(`SELECT json_build_object('action', action, 'actorKind', actor_kind, 'actorId', actor_id, 'subjectId', subject_id,
      'at', created_at, 'details', row_to_json(a)::text)
    FROM audit_entry a WHERE tenant_id = ${sqlLiteral(salonId)} AND action LIKE ${sqlLiteral(`${PENDING_F05_S01.auditPrefix}%`)}
      AND created_at >= ${sqlLiteral(after)}::timestamptz ORDER BY created_at, id`);
  return out ? out.split('\n').map(line => JSON.parse(line) as AuditRow) : [];
}

// ================================================================ F05-S02: variants, Popular and a service's deposit

/**
 * Names the F05-S02 spec leaves to the build, from its "API and data changes" and the patterns of
 * F05-S01. Confirm each against the F05-S02 handoff and the generated contract at the dry run.
 */
export const PENDING_F05_S02 = {
  /** The child table of a terms version holding its variants (spec, "Design notes"). */
  variantTable: 'catalogue_terms_variant',
  /** The salon's Popular setting row. */
  popularTable: 'catalogue_popular_setting',
  /** A 400's field path for a variant's field: `variants.items[1].priceMinor`. */
  variantField: (index: number, field: string) => `variants\\.items\\[${index}\\]\\.${field}`,
  /** The default Popular setting of a salon that never saved one. */
  defaultMode: 'chosen',
  /** The deposit of a service saved without one. */
  defaultDeposit: { kind: 'salon_rule' },
  /** A menu service's lowest and highest price and time. */
  menuRange: { priceMin: 'priceMinMinor', priceMax: 'priceMaxMinor', durationMin: 'durationMinMinutes', durationMax: 'durationMaxMinutes' },
  /** A menu variant's fields. */
  menuVariant: { price: 'priceMinor', minutes: 'durationMinutes' },
  /** The schema of the Popular setting. */
  popularSettingSchema: 'CataloguePopularSetting',
} as const;

export const POPULAR_SETTING = `${CATALOGUE}/popular-setting`;
export const popularPath = (id: string) => `${servicePath(id)}/popular`;

export type VariantInput = { id?: string; name: string; priceMinor: number; durationMinutes: number };
export type Variant = { id: string; name: string; priceMinor: number; durationMinutes: number; segments: Segment[] };
export type Deposit = { kind: 'salon_rule' | 'none' } | { kind: 'percentage'; percent: number } | { kind: 'amount'; amountMinor: number };
export type TermsS02 = Terms & {
  variants: Variant[]; variantSetName: string | null; priceMinMinor: number; priceMaxMinor: number; durationMinMinutes: number; durationMaxMinutes: number;
};
export type ServiceS02 = Omit<Service, 'terms'> & { popular: boolean; deposit: Deposit; terms: TermsS02 };
export type PopularSetting = { mode: 'chosen' | 'most_booked'; version: number; popular: string[] };
export type MenuS02 = Menu & { popular: string[] };

/** A service input with a set of variants (Short, Medium, Long hair by default). */
export const variantInput = (categoryId: string, items: VariantInput[] = [
  { name: 'Short', priceMinor: rupees(2500), durationMinutes: 30 },
  { name: 'Medium', priceMinor: rupees(3000), durationMinutes: 45 },
  { name: 'Long', priceMinor: rupees(3500), durationMinutes: 60 },
], overrides: Record<string, unknown> = {}) => {
  const base: Record<string, unknown> = { ...serviceInput(categoryId), variants: { name: 'Hair length', items }, ...overrides };
  delete base.priceMinor;
  delete base.durationMinutes;
  return base;
};

/** What a client sends back to save an S02 service: its current values, variants (with ids) and deposit, with changes applied. */
export function saveOf(service: ServiceS02, change: Record<string, unknown> = {}) {
  const t = service.terms;
  const variants = t.variants.length
    ? { name: t.variantSetName, items: t.variants.map(v => ({ id: v.id, name: v.name, priceMinor: v.priceMinor, durationMinutes: v.durationMinutes })) }
    : null;
  return {
    name: service.name, categoryId: service.categoryId, description: service.description, bookableOnline: service.bookableOnline,
    bufferMinutes: t.bufferMinutes, deposit: service.deposit, variants,
    ...(variants ? {} : { priceMinor: t.priceMinor, durationMinutes: t.durationMinutes }),
    ...change, version: service.version,
  };
}

/** AC-2: a variant's segments: its own time, then the service's buffer when above zero. */
export const variantSegments = segmentsOf;

export class CatalogueS02 extends Catalogue {
  async serviceS02(salon: Salon, tokens: Tokens, id: string): Promise<ServiceS02> {
    return (await this.service(salon, tokens, id)) as unknown as ServiceS02;
  }
  async createS02(salon: Salon, tokens: Tokens, input: Record<string, unknown>): Promise<ServiceS02> {
    return json<ServiceS02>(await this.createRaw(salon, tokens, input), PENDING_F05_S01.schemas.service, 201);
  }
  async saveS02(salon: Salon, tokens: Tokens, service: ServiceS02, change: Record<string, unknown> = {}): Promise<ServiceS02> {
    return json<ServiceS02>(await this.updateRaw(salon, tokens, service.id, saveOf(service, change)), PENDING_F05_S01.schemas.service);
  }
  popularRaw(salon: Salon, tokens: Tokens | undefined, id: string, popular: unknown, version: number) {
    return this.call(salon, 'PUT', popularPath(id), tokens, { popular, version });
  }
  async choose(salon: Salon, tokens: Tokens, service: { id: string; version: number }, popular = true): Promise<ServiceS02> {
    return json<ServiceS02>(await this.popularRaw(salon, tokens, service.id, popular, service.version), PENDING_F05_S01.schemas.service);
  }
  settingRaw(salon: Salon, tokens: Tokens | undefined, method = 'GET', data?: unknown) { return this.call(salon, method, POPULAR_SETTING, tokens, data); }
  async setting(salon: Salon, tokens: Tokens): Promise<PopularSetting> {
    return json<PopularSetting>(await this.settingRaw(salon, tokens), PENDING_F05_S02.popularSettingSchema);
  }
  async setMode(salon: Salon, tokens: Tokens, mode: PopularSetting['mode'], version?: number): Promise<PopularSetting> {
    const from = version ?? (await this.setting(salon, tokens)).version;
    return json<PopularSetting>(await this.settingRaw(salon, tokens, 'PUT', { mode, version: from }), PENDING_F05_S02.popularSettingSchema);
  }
  async menuS02(salon: Salon): Promise<MenuS02> { return (await this.menu(salon)) as MenuS02; }
}

/** Every stored row of a salon's catalogue that names a terms version: the version and its variants. */
export const versionWithVariants = (salonId: string, versionId: string) => catalogueTables().map(t => identitySql(
  `SELECT coalesce(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text), '') FROM ${t} x
   WHERE tenant_id = ${sqlLiteral(salonId)} AND row_to_json(x)::text LIKE ${sqlLiteral(`%${versionId}%`)}`)).filter(Boolean).join('\n');
