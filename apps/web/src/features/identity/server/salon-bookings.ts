import 'server-only';

/** What a call the workspace BFF hands on answers: its data or the API's problem, and the response. */
type Answer = {
  data?: unknown;
  error?: { code?: string; errors?: unknown };
  response: Response;
};

/** The caller's credential and the request's shared API budget, for one call. */
type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };

/**
 * The booking paths the workspace BFF reaches for the Bookings page (F07-S03): a day's list, one booking with its
 * history and allowed actions, the free times and open days New appointment offers, a hold, and each change of state.
 * Identity checks the session and the permission first; booking makes the calls and builds each request from the
 * contract's own fields, and the route serving the BFF hands its implementation in, so that neither module imports the
 * other.
 */
export interface SalonBookings {
  list(call: Call, query: URLSearchParams): Promise<Answer>;
  detail(call: Call, id: unknown): Promise<Answer>;
  availability(call: Call, query: URLSearchParams): Promise<Answer>;
  openDays(call: Call, query: URLSearchParams): Promise<Answer>;
  place(call: Call, body: Record<string, unknown>): Promise<Answer>;
  act(call: Call, action: BookingAction, body: Record<string, unknown>): Promise<Answer>;
}

/** The booking calls for one salon's hostname; null when the API is not configured. */
export type SalonBookingsFor = (hostname: string) => SalonBookings | null;

/** The changes a booking takes after it is placed, by the API's own names. */
export type BookingAction = 'confirm' | 'release' | 'arrive' | 'start' | 'cancel' | 'no-show' | 'undo-no-show';

/** The API's clock, read so that a countdown follows the server's expiry rather than the browser's (F07-S03). */
export const BOOKING_CLOCK = 'booking-clock';

/**
 * The BFF's reads of bookings: the list, one booking and the clock with `calendar.read` (the API applies its data
 * scope), and what New appointment offers with `booking.manage`.
 */
export const BOOKING_READS = ['bookings', 'booking', BOOKING_CLOCK, 'booking-availability', 'booking-open-days'];

/** The BFF's action for each change: `booking-<the API's name>`. */
const ACTIONS: BookingAction[] = ['confirm', 'release', 'arrive', 'start', 'cancel', 'no-show', 'undo-no-show'];
export const BOOKING_WRITES = ['booking-place', ...ACTIONS.map((action) => `booking-${action}`)];

/** Arriving and starting are `booking.attend` (a Professional's own bookings); every other change `booking.manage`. */
export const BOOKING_PERMISSIONS: Record<string, string> = Object.fromEntries([
  ['bookings', 'calendar.read'],
  ['booking', 'calendar.read'],
  [BOOKING_CLOCK, 'calendar.read'],
  ['booking-availability', 'booking.manage'],
  ['booking-open-days', 'booking.manage'],
  ['booking-place', 'booking.manage'],
  ...ACTIONS.map((action) => [
    `booking-${action}`,
    action === 'arrive' || action === 'start' ? 'booking.attend' : 'booking.manage',
  ]),
]);

/** One read, its query passed to booking, which forwards only the contract's own parameters. */
export function readBookings(
  bookings: SalonBookings,
  action: string,
  call: Call,
  query: URLSearchParams,
  clock: () => Promise<string | null>,
): Promise<Answer> {
  switch (action) {
    case 'booking':
      return bookings.detail(call, query.get('id'));
    case 'booking-availability':
      return bookings.availability(call, query);
    case 'booking-open-days':
      return bookings.openDays(call, query);
    case BOOKING_CLOCK:
      return clock().then((now) =>
        now
          ? { data: { now }, response: new Response(null, { status: 200 }) }
          : { response: new Response(null, { status: 503 }) },
      );
    default:
      return bookings.list(call, query);
  }
}

/** One change: a hold placed, or a change of state, the booking named in the body. */
export function changeBooking(
  bookings: SalonBookings,
  action: string,
  call: Call,
  body: Record<string, unknown>,
): Promise<Answer> {
  if (action === 'booking-place') return bookings.place(call, body);
  return bookings.act(call, action.slice('booking-'.length) as BookingAction, body);
}
