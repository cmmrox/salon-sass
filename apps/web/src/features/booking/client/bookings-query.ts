import type { BookingState } from '../types';

/** The states the status filter offers, in the spec's order (F07-S03 AC-3); `all` is no filter. */
export const STATUS_FILTERS = [
  'held',
  'confirmed',
  'arrived',
  'in_service',
  'completed',
  'cancelled',
  'no_show',
] as const;
export type StatusFilter = 'all' | (typeof STATUS_FILTERS)[number];

/** A page of the list: the API's default, which the heading's count spans. */
export const PAGE_SIZE = 50;
/** The longest search the API takes. */
export const SEARCH_MAX = 60;

/**
 * What the Bookings page shows, kept in its address so that reload and back restore it (AC-3): the day (`day`, today
 * when absent), the search (`q`), the status (`status`), the professional (`professional`, a team member's id), the
 * page from 1 (`page`) and the booking whose sheet is open (`booking`).
 */
export type BookingsQuery = {
  day: string | null;
  q: string;
  status: StatusFilter;
  professional: string | null;
  page: number;
  booking: string | null;
};

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** The query an address holds; anything malformed is left at its default. */
export function queryOf(params: { get(name: string): string | null }): BookingsQuery {
  const day = params.get('day');
  const status = params.get('status');
  const professional = params.get('professional');
  const page = Number(params.get('page') ?? '1');
  const booking = params.get('booking');
  return {
    day: day && DATE.test(day) && !Number.isNaN(Date.parse(`${day}T00:00:00Z`)) ? day : null,
    q: (params.get('q') ?? '').slice(0, SEARCH_MAX),
    status: (STATUS_FILTERS as readonly string[]).includes(status ?? '') ? (status as StatusFilter) : 'all',
    professional: professional && UUID.test(professional) ? professional : null,
    page: Number.isInteger(page) && page >= 1 ? page : 1,
    booking: booking && UUID.test(booking) ? booking : null,
  };
}

/** The address's query for what is shown, defaults left out: `?day=2026-11-12&status=held`. */
export function searchOf(query: BookingsQuery): string {
  const params = new URLSearchParams();
  if (query.day) params.set('day', query.day);
  if (query.q) params.set('q', query.q);
  if (query.status !== 'all') params.set('status', query.status);
  if (query.professional) params.set('professional', query.professional);
  if (query.page > 1) params.set('page', String(query.page));
  if (query.booking) params.set('booking', query.booking);
  const text = params.toString();
  return text ? `?${text}` : '';
}

/** Whether anything narrows the list beyond its day. */
export const filtered = (query: BookingsQuery) =>
  query.q.trim() !== '' || query.status !== 'all' || query.professional !== null;

/**
 * The BFF list read for a query on its day: that one date, the state, the professional and the search as typed, and
 * the page from 0. The screen never filters for security: the API applies the caller's data scope.
 */
export function listRequest(query: BookingsQuery, day: string, professionals: boolean): string {
  const params = new URLSearchParams({ from: day, to: day, page: String(query.page - 1), size: String(PAGE_SIZE) });
  if (query.status !== 'all') params.append('state', query.status satisfies BookingState);
  if (professionals && query.professional) params.set('memberId', query.professional);
  if (query.q.trim()) params.set('q', query.q.trim());
  return `bookings?${params.toString()}`;
}
