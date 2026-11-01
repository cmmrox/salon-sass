/**
 * F05-S03 fixtures: tax settings (public read, settings read and save, history), products, an
 * independent copy of the inclusive arithmetic (the oracle the preview is compared with), and
 * read-only database observations of the stage's tables.
 *
 * Routes, bodies, codes and limits come from the F05-S03 spec (acceptance criteria, "API and data
 * changes", "Design notes"), decisions 085, 091 and 095. Prepared before the build: names only the
 * build gives are in `PENDING_F05_S03`, confirmed against the F05-S03 handoff at the dry run.
 */
import { CATALOGUE, Catalogue } from './catalogue';
import { expect, json, type Tokens } from './identity';
import { identitySql } from './identity-storage';
import { schemaValidator } from './openapi';
import { sqlLiteral } from './operator';
import type { Salon } from './tenants';

export const PENDING_F05_S03 = {
  taxTable: 'catalogue_tax_version',
  productTable: 'catalogue_product',
  schemas: {
    publicTax: 'CatalogueTax', settings: 'CatalogueTaxSettings', history: 'CatalogueTaxHistory',
    productList: 'CatalogueProductList', product: 'CatalogueProduct',
  },
  /** The environment variable that sets the configured VAT rate for Sri Lanka (`salon.catalogue.tax.*`). */
  vatRateVariable: 'SALON_CATALOGUE_TAX_VAT_RATE_PERCENT',
  /** Audit actions of the tax settings (products use the catalogue prefix). */
  taxAuditPrefix: 'catalogue.tax',
  productAuditPrefix: 'catalogue.product',
} as const;

export const TAX = `${CATALOGUE}/tax`;
export const TAX_SETTINGS = `${TAX}/settings`;
export const TAX_HISTORY = `${TAX}/history`;
export const PRODUCTS = `${CATALOGUE}/products`;
export const productPath = (id: string) => `${PRODUCTS}/${id}`;

/** AC-2: Sri Lanka's configuration. */
export const CONFIGURED = { vatName: 'VAT', vatRatePercent: 18, registrationLabel: 'VAT registration number', serviceChargeName: 'Service charge', serviceChargeMaxPercent: 20 };

export type TaxInput = { vat: { registered: boolean; registrationNumber: string | null }; serviceCharge: { on: boolean; percent: number | null; onProducts: boolean } };
export type TaxSettingsView = { version: number; settings: TaxInput | null; configured: typeof CONFIGURED };
export type PublicTax = { versionId: string; vat: { name: string; ratePercent: number } | null; serviceCharge: { name: string; percent: number; onProducts: boolean } | null };
export type HistoryEntry = { versionId: string; version: number; createdAt: string; createdBy: string; createdByLabel: string | null; changedFields: string[] };
export type History = { items: HistoryEntry[]; page: number; size: number; total: number };
export type Product = { id: string; name: string; priceMinor: number; currency: string; chargesVat: boolean; soldAtCheckout: boolean; version: number };
export type ProductInput = { name: string; priceMinor: number; chargesVat: boolean; soldAtCheckout: boolean };

/** Tax settings with parts replaced: by default VAT off and no service charge. */
export const taxInput = (o: { vat?: string | null; sc?: number | null; onProducts?: boolean } = {}): TaxInput => ({
  vat: { registered: o.vat != null, registrationNumber: o.vat ?? null },
  serviceCharge: { on: o.sc != null, percent: o.sc ?? null, onProducts: o.onProducts ?? false },
});

/** Settings no earlier save of a salon can equal: VAT with a random registration number. */
export const distinctTax = (sc: number | null = null) => taxInput({ vat: `QA-${Math.random().toString(36).slice(2, 10).toUpperCase()}`, sc });

export const productInput = (o: Partial<ProductInput> = {}): ProductInput => ({
  name: `QA product ${Math.random().toString(16).slice(2, 8)}`, priceMinor: 320000, chargesVat: true, soldAtCheckout: true, ...o,
});

export class TaxCatalogue extends Catalogue {
  async publicRaw(host: string): Promise<{ status: number; text: string; body: PublicTax | null }> {
    const r = await this.identity.api.getOn(host, TAX);
    const text = await r.text();
    return { status: r.status(), text, body: text ? JSON.parse(text) : undefined };
  }
  /** The public current tax on the salon's hostname with no credential: JSON null before the first save, else contract-checked. */
  async published(salon: Salon): Promise<PublicTax | null> {
    const r = await this.identity.api.getOn(salon.hostname, TAX);
    expect(r.status(), 'public tax: 200').toBe(200);
    expect((r.headers()['content-type'] ?? '').split(';')[0]).toBe('application/json');
    const body = JSON.parse(await r.text()) as PublicTax | null;
    if (body === null) return null;
    const validate = schemaValidator(PENDING_F05_S03.schemas.publicTax);
    expect(Boolean(validate(body)), 'public tax: generated contract shape').toBe(true);
    return body;
  }
  async settings(salon: Salon, tokens: Tokens): Promise<TaxSettingsView> {
    return json<TaxSettingsView>(await this.call(salon, 'GET', TAX_SETTINGS, tokens), PENDING_F05_S03.schemas.settings);
  }
  saveRaw(salon: Salon, tokens: Tokens | undefined, version: number, input: unknown) {
    return this.call(salon, 'PUT', TAX_SETTINGS, tokens, { version, ...(input as object) });
  }
  async save(salon: Salon, tokens: Tokens, input: TaxInput, version?: number): Promise<TaxSettingsView> {
    const from = version ?? (await this.settings(salon, tokens)).version;
    return json<TaxSettingsView>(await this.saveRaw(salon, tokens, from, input), PENDING_F05_S03.schemas.settings);
  }
  historyRaw(salon: Salon, tokens: Tokens | undefined, query = '') { return this.call(salon, 'GET', `${TAX_HISTORY}${query}`, tokens); }
  async history(salon: Salon, tokens: Tokens, query = ''): Promise<History> {
    return json<History>(await this.historyRaw(salon, tokens, query), PENDING_F05_S03.schemas.history);
  }

  async products(salon: Salon, tokens: Tokens): Promise<Product[]> {
    return (await json<{ items: Product[] }>(await this.call(salon, 'GET', PRODUCTS, tokens), PENDING_F05_S03.schemas.productList)).items;
  }
  addProductRaw(salon: Salon, tokens: Tokens | undefined, input: unknown) { return this.call(salon, 'POST', PRODUCTS, tokens, input); }
  async addProduct(salon: Salon, tokens: Tokens, input: ProductInput): Promise<Product> {
    return json<Product>(await this.addProductRaw(salon, tokens, input), PENDING_F05_S03.schemas.product, 201);
  }
  editProductRaw(salon: Salon, tokens: Tokens | undefined, id: string, body: unknown) { return this.call(salon, 'PUT', productPath(id), tokens, body); }
  async editProduct(salon: Salon, tokens: Tokens, p: Product, change: Partial<ProductInput>): Promise<Product> {
    const body = { name: p.name, priceMinor: p.priceMinor, chargesVat: p.chargesVat, soldAtCheckout: p.soldAtCheckout, ...change, version: p.version };
    return json<Product>(await this.editProductRaw(salon, tokens, p.id, body), PENDING_F05_S03.schemas.product);
  }
  deleteProductRaw(salon: Salon, tokens: Tokens | undefined, id: string, version: number) { return this.call(salon, 'DELETE', `${productPath(id)}?version=${version}`, tokens); }
}

// ---- the arithmetic (AC-4), written here from the rule, never from the product ----

/** n / d rounded half to even, for non-negative integers. */
function halfEven(n: bigint, d: bigint): bigint {
  const q = n / d, r = n % d;
  if (2n * r > d) return q + 1n;
  if (2n * r === d) return q % 2n === 0n ? q : q + 1n;
  return q;
}

export type Parts = { vat: number; serviceCharge: number; net: number };

/** AC-4: the parts of an inclusive price P (minor units) under VAT v% and service charge s% (null when off). */
export function split(priceMinor: number, vatPercent: number | null, scPercent: number | null): Parts {
  const p = BigInt(priceMinor);
  const beforeVat = vatPercent ? halfEven(p * 100n, BigInt(100 + vatPercent)) : p;
  const net = scPercent ? halfEven(beforeVat * 100n, BigInt(100 + scPercent)) : beforeVat;
  const parts = { vat: Number(p - beforeVat), serviceCharge: Number(beforeVat - net), net: Number(net) };
  if (parts.vat + parts.serviceCharge + parts.net !== priceMinor) throw new Error('the oracle\'s parts do not add up');
  return parts;
}

/** An amount as the preview may write it: `Rs. 192.60`, and for whole rupees `Rs. 381` or `Rs. 381.00`. */
export function moneyText(minor: number): RegExp {
  const whole = new Intl.NumberFormat('en-LK', { maximumFractionDigits: 0 }).format(Math.floor(minor / 100));
  const cents = String(minor % 100).padStart(2, '0');
  return new RegExp(`Rs\\. ${whole.replace(/[.]/g, '\\.')}${cents === '00' ? '(\\.00)?' : `\\.${cents}`}(?!\\d)`);
}

// ---- database observations (owner role; never writes) ----

export const taxRows = (salonId: string) => Number(identitySql(`SELECT count(*) FROM ${PENDING_F05_S03.taxTable} WHERE tenant_id = ${sqlLiteral(salonId)}`));
export const taxSnapshot = (salonId: string) => identitySql(
  `SELECT coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text)), 'none') FROM ${PENDING_F05_S03.taxTable} x WHERE tenant_id = ${sqlLiteral(salonId)}`);
export const taxRow = (salonId: string, versionId: string) => identitySql(
  `SELECT row_to_json(x)::text FROM ${PENDING_F05_S03.taxTable} x WHERE tenant_id = ${sqlLiteral(salonId)} AND id = ${sqlLiteral(versionId)}`);
export const productSnapshot = (salonId: string) => identitySql(
  `SELECT coalesce(md5(string_agg(row_to_json(x)::text, '|' ORDER BY row_to_json(x)::text)), 'none') FROM ${PENDING_F05_S03.productTable} x WHERE tenant_id = ${sqlLiteral(salonId)}`);

export type AuditRow = { action: string; actorId: string; subjectId: string; at: string; details: string };
export function auditOf(salonId: string, prefix: string, after: string): AuditRow[] {
  const out = identitySql(`SELECT json_build_object('action', action, 'actorId', actor_id, 'subjectId', subject_id, 'at', created_at, 'details', row_to_json(a)::text)
    FROM audit_entry a WHERE tenant_id = ${sqlLiteral(salonId)} AND action LIKE ${sqlLiteral(`${prefix}%`)} AND created_at >= ${sqlLiteral(after)}::timestamptz ORDER BY created_at, id`);
  return out ? out.split('\n').map(line => JSON.parse(line) as AuditRow) : [];
}
