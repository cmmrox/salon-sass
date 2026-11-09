import 'server-only';

import { createApiClient, type components } from '@salon/api-contracts/booking';

import { createServerApiClient } from '@/lib/api-core/client';

type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };
type PlaceBooking = components['schemas']['PlaceBooking'];
type CancelBooking = components['schemas']['CancelBooking'];
type Action = 'confirm' | 'release' | 'arrive' | 'start' | 'cancel' | 'no-show' | 'undo-no-show';

const record = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
/** A value as the browser sent it; anything else is passed on as it came, for the API to refuse by name. */
const as = <T>(value: unknown) => value as T;

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
/** An id for a path, checked for its form, so nothing else reaches the path; the API answers 404 for the rest. */
const idOf = (value: unknown) => (typeof value === 'string' && UUID.test(value) ? value : '0');
/** A query's value as text, or left out when the browser sent none. */
const optional = (query: URLSearchParams, name: string) => {
  const value = query.get(name);
  return value === null || value === '' ? undefined : value;
};
/** A required query value: nothing at all is sent as empty, for the API to refuse by name. */
const required = (query: URLSearchParams, name: string) => query.get(name) ?? '';

/** A hold's fields, rebuilt from the contract's own names; nothing else of the browser's goes on. */
function holdOf(body: Record<string, unknown>): PlaceBooking {
  const customer = record(body.customer);
  return {
    memberId: as<string | null>(body.memberId ?? null),
    serviceId: as<string>(body.serviceId),
    ...(body.variantId === undefined || body.variantId === null ? {} : { variantId: as<string>(body.variantId) }),
    date: as<string>(body.date),
    time: as<string>(body.time),
    customer: { name: as<string>(customer.name), mobile: as<string>(customer.mobile) },
    channel: as<PlaceBooking['channel']>(body.channel),
  };
}

/** A cancellation's reason and note, as the contract names them. */
function cancellationOf(body: Record<string, unknown>): CancelBooking {
  return {
    reason: as<CancelBooking['reason']>(body.reason),
    note: typeof body.note === 'string' && body.note.trim() !== '' ? body.note : null,
  };
}

/**
 * The booking paths the workspace BFF reaches for the Bookings page, under the salon's hostname (F07-S03). Every
 * request is rebuilt from the contract's own fields; the BFF checks the session and the permission before any of
 * them. The hold's `Idempotency-Key` is the browser's, one per attempt, so a double click places one hold. Null when
 * the API is not configured.
 */
export function salonBookingsFor(hostname: string) {
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return null;
  const at = (id: unknown) => ({ path: { id: idOf(id) } });
  return {
    list: (call: Call, query: URLSearchParams) =>
      client.GET('/api/v1/bookings', {
        ...call,
        params: {
          query: {
            from: required(query, 'from'),
            to: required(query, 'to'),
            ...(query.getAll('state').some(Boolean) ? { state: query.getAll('state').filter(Boolean) } : {}),
            memberId: optional(query, 'memberId'),
            q: optional(query, 'q'),
            page: optional(query, 'page'),
            size: optional(query, 'size'),
          },
        },
      }),
    detail: (call: Call, id: unknown) => client.GET('/api/v1/bookings/{id}', { ...call, params: at(id) }),
    availability: (call: Call, query: URLSearchParams) =>
      client.GET('/api/v1/bookings/availability', {
        ...call,
        params: {
          query: {
            serviceId: required(query, 'serviceId'),
            variantId: optional(query, 'variantId'),
            date: required(query, 'date'),
            memberId: optional(query, 'memberId'),
          },
        },
      }),
    openDays: (call: Call, query: URLSearchParams) =>
      client.GET('/api/v1/bookings/open-days', {
        ...call,
        params: { query: { from: required(query, 'from'), to: required(query, 'to') } },
      }),
    place: (call: Call, body: Record<string, unknown>) =>
      client.POST('/api/v1/bookings', {
        ...call,
        params: {
          header: { 'Idempotency-Key': typeof body.idempotencyKey === 'string' ? body.idempotencyKey : '' },
        },
        body: holdOf(body),
      }),
    act: (call: Call, action: Action, body: Record<string, unknown>) => {
      const params = at(body.id);
      switch (action) {
        case 'confirm':
          return client.POST('/api/v1/bookings/{id}/confirm', { ...call, params });
        case 'release':
          return client.POST('/api/v1/bookings/{id}/release', { ...call, params });
        case 'arrive':
          return client.POST('/api/v1/bookings/{id}/arrive', { ...call, params });
        case 'start':
          return client.POST('/api/v1/bookings/{id}/start', { ...call, params });
        case 'cancel':
          return client.POST('/api/v1/bookings/{id}/cancel', { ...call, params, body: cancellationOf(body) });
        case 'no-show':
          return client.POST('/api/v1/bookings/{id}/no-show', { ...call, params });
        default:
          return client.POST('/api/v1/bookings/{id}/undo-no-show', { ...call, params });
      }
    },
  };
}
