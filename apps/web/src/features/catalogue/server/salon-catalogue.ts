import 'server-only';

import { createApiClient, type components } from '@salon/api-contracts/catalogue';

import { createServerApiClient } from '@/lib/api-core/client';

type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };
type NewService = components['schemas']['NewCatalogueService'];

const record = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
const text = (value: unknown): string => (typeof value === 'string' ? value : '');
/** A value as the browser sent it; anything else is passed on as it came, for the API to refuse by name. */
const as = <T>(value: unknown) => value as T;

/** A service's fields, rebuilt from the contract's own names; nothing else of the browser's goes on. */
function serviceOf(body: Record<string, unknown>): NewService {
  return {
    name: text(body.name),
    categoryId: text(body.categoryId),
    description: body.description === null || body.description === undefined ? null : text(body.description),
    priceMinor: as<number>(body.priceMinor),
    durationMinutes: as<number>(body.durationMinutes),
    bufferMinutes: as<number>(body.bufferMinutes),
    bookableOnline: as<boolean>(body.bookableOnline),
  };
}

/**
 * The catalogue paths the workspace BFF reaches (F05-S01): the categories and services, every change to them, and
 * a service's photo. Every request is rebuilt from the contract's own fields; the BFF checks the session and the
 * permission before any of them. Null when the API is not configured.
 */
export function salonCatalogueFor(hostname: string) {
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return null;
  const id = (body: unknown) => ({ path: { id: text(record(body).id) } });
  return {
    categories: (call: Call) => client.GET('/api/v1/catalogue/categories', call),
    services: (call: Call) => client.GET('/api/v1/catalogue/services', call),
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
