import 'server-only';

import { createApiClient, type components } from '@salon/api-contracts/catalogue';

import { createServerApiClient } from '@/lib/api-core/client';

type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };
type NewService = components['schemas']['NewCatalogueService'];
type Variants = components['schemas']['CatalogueVariantsInput'];
type Deposit = components['schemas']['CatalogueServiceDepositInput'];
type Product = components['schemas']['NewCatalogueProduct'];
type Tax = components['schemas']['SaveCatalogueTax'];

const record = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
const text = (value: unknown): string => (typeof value === 'string' ? value : '');
/** A value as the browser sent it; anything else is passed on as it came, for the API to refuse by name. */
const as = <T>(value: unknown) => value as T;

/** A field sent only when the browser sent it: absent stays absent, for the API to judge. */
const sent = <T>(value: unknown): T | undefined => (value === undefined ? undefined : (value as T));

/** A set of variants rebuilt from the contract's names (F05-S02); null or absent is none. */
function variantsOf(value: unknown): Variants | null {
  if (value === null || value === undefined) return null;
  const set = record(value);
  const items = Array.isArray(set.items) ? set.items : [];
  return {
    name: text(set.name),
    items: items.map((each) => {
      const item = record(each);
      return {
        ...(item.id === undefined || item.id === null ? {} : { id: text(item.id) }),
        name: text(item.name),
        priceMinor: as<number>(item.priceMinor),
        durationMinutes: as<number>(item.durationMinutes),
      };
    }),
  };
}

/** A deposit rebuilt from the contract's names (F05-S02): only the fields sent go on. */
function depositOf(value: unknown): Deposit | undefined {
  if (value === null || value === undefined) return undefined;
  const deposit = record(value);
  const percent = sent<number>(deposit.percent);
  const amountMinor = sent<number>(deposit.amountMinor);
  return {
    kind: text(deposit.kind) as Deposit['kind'],
    ...(percent === undefined ? {} : { percent }),
    ...(amountMinor === undefined ? {} : { amountMinor }),
  };
}

/** A service's fields, rebuilt from the contract's own names; nothing else of the browser's goes on. */
function serviceOf(body: Record<string, unknown>): NewService {
  const service: NewService = {
    name: text(body.name),
    categoryId: text(body.categoryId),
    description: body.description === null || body.description === undefined ? null : text(body.description),
    bufferMinutes: as<number>(body.bufferMinutes),
    bookableOnline: as<boolean>(body.bookableOnline),
  };
  const priceMinor = sent<number>(body.priceMinor);
  const durationMinutes = sent<number>(body.durationMinutes);
  if (priceMinor !== undefined) service.priceMinor = priceMinor;
  if (durationMinutes !== undefined) service.durationMinutes = durationMinutes;
  const variants = variantsOf(body.variants);
  if (variants) service.variants = variants;
  const deposit = depositOf(body.deposit);
  if (deposit) service.deposit = deposit;
  return service;
}

/** A product's fields, rebuilt from the contract's own names (F05-S03). */
function productOf(body: Record<string, unknown>): Product {
  return {
    name: text(body.name),
    priceMinor: as<number>(body.priceMinor),
    chargesVat: as<boolean>(body.chargesVat),
    soldAtCheckout: as<boolean>(body.soldAtCheckout),
  };
}

/** The tax settings' save, rebuilt from the contract's own names (F05-S03); a part's number or rate only when sent. */
function taxOf(body: Record<string, unknown>): Tax {
  const vat = record(body.vat);
  const charge = record(body.serviceCharge);
  const registrationNumber = sent<string | null>(vat.registrationNumber);
  const percent = sent<number | null>(charge.percent);
  return {
    version: as<number>(body.version),
    vat: {
      registered: as<boolean>(vat.registered),
      ...(registrationNumber === undefined ? {} : { registrationNumber }),
    },
    serviceCharge: {
      on: as<boolean>(charge.on),
      ...(percent === undefined ? {} : { percent }),
      onProducts: as<boolean>(charge.onProducts),
    },
  };
}

/**
 * The catalogue paths the workspace BFF reaches (F05-S01 to F05-S03): the categories and services, the Popular
 * setting, the products and the tax settings, every change to them, and a service's photo. Every request is rebuilt from the contract's own fields; the BFF checks the session and the
 * permission before any of them. Null when the API is not configured.
 */
export function salonCatalogueFor(hostname: string) {
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return null;
  const id = (body: unknown) => ({ path: { id: text(record(body).id) } });
  return {
    categories: (call: Call) => client.GET('/api/v1/catalogue/categories', call),
    services: (call: Call) => client.GET('/api/v1/catalogue/services', call),
    popularSetting: (call: Call) => client.GET('/api/v1/catalogue/popular-setting', call),
    products: (call: Call) => client.GET('/api/v1/catalogue/products', call),
    taxSettings: (call: Call) => client.GET('/api/v1/catalogue/tax/settings', call),
    taxHistory: (call: Call, page: number) =>
      client.GET('/api/v1/catalogue/tax/history', { ...call, params: { query: { page, size: 10 } } }),
    taxPublic: (call: Call) => client.GET('/api/v1/catalogue/tax', call),
    change: (call: Call, action: string, value: unknown) => {
      const body = record(value);
      switch (action) {
        case 'catalogue-category-add':
          return client.POST('/api/v1/catalogue/categories', { ...call, body: { name: text(body.name) } });
        case 'catalogue-category-rename':
          return client.PUT('/api/v1/catalogue/categories/{id}', {
            ...call,
            params: id(body),
            body: { name: text(body.name), version: as<number>(body.version) },
          });
        case 'catalogue-category-move':
          return client.POST('/api/v1/catalogue/categories/{id}/move', {
            ...call,
            params: id(body),
            body: { direction: as<'up' | 'down'>(body.direction), listVersion: as<number>(body.listVersion) },
          });
        case 'catalogue-category-delete':
          return client.DELETE('/api/v1/catalogue/categories/{id}', {
            ...call,
            params: { ...id(body), query: { version: as<number>(body.version) } },
          });
        case 'catalogue-service-create':
          return client.POST('/api/v1/catalogue/services', { ...call, body: serviceOf(body) });
        case 'catalogue-service-save':
          return client.PUT('/api/v1/catalogue/services/{id}', {
            ...call,
            params: id(body),
            body: {
              ...serviceOf(body),
              bookableOnline: as<boolean>(body.bookableOnline),
              version: as<number>(body.version),
            },
          });
        case 'catalogue-service-duplicate':
          return client.POST('/api/v1/catalogue/services/{id}/duplicate', { ...call, params: id(body) });
        case 'catalogue-service-online':
          return client.PUT('/api/v1/catalogue/services/{id}/online', {
            ...call,
            params: id(body),
            body: { bookableOnline: as<boolean>(body.bookableOnline), version: as<number>(body.version) },
          });
        case 'catalogue-service-popular':
          return client.PUT('/api/v1/catalogue/services/{id}/popular', {
            ...call,
            params: id(body),
            body: { popular: as<boolean>(body.popular), version: as<number>(body.version) },
          });
        case 'catalogue-popular-mode':
          return client.PUT('/api/v1/catalogue/popular-setting', {
            ...call,
            body: { mode: as<'chosen' | 'most_booked'>(body.mode), version: as<number>(body.version) },
          });
        case 'catalogue-product-add':
          return client.POST('/api/v1/catalogue/products', { ...call, body: productOf(body) });
        case 'catalogue-product-save':
          return client.PUT('/api/v1/catalogue/products/{id}', {
            ...call,
            params: id(body),
            body: { ...productOf(body), version: as<number>(body.version) },
          });
        case 'catalogue-product-delete':
          return client.DELETE('/api/v1/catalogue/products/{id}', {
            ...call,
            params: { ...id(body), query: { version: as<number>(body.version) } },
          });
        case 'catalogue-tax-save':
          return client.PUT('/api/v1/catalogue/tax/settings', { ...call, body: taxOf(body) });
        case 'catalogue-service-delete':
          return client.DELETE('/api/v1/catalogue/services/{id}', {
            ...call,
            params: { ...id(body), query: { version: as<number>(body.version) } },
          });
        default:
          return client.DELETE('/api/v1/catalogue/services/{id}/photo', { ...call, params: id(body) });
      }
    },
    uploadPhoto: (call: Call, serviceId: string, file: Blob) => {
      // The form is built here from the one field the contract names; nothing else of the browser's goes on.
      const form = new FormData();
      form.append('file', file, 'upload');
      return client.PUT('/api/v1/catalogue/services/{id}/photo', {
        ...call,
        params: { path: { id: serviceId } },
        body: form as unknown as { file: string },
        bodySerializer: (body) => body as unknown as FormData,
      });
    },
  };
}
