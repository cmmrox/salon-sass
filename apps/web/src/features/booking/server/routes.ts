import 'server-only';
import { apiTime } from '@/lib/api-core/meta';
import { moduleRoutes, type BffChange, type BffRead, type Call } from '@/lib/api-core/routes';
import { salonBookingRulesFor } from './salon-booking-rules';
import { salonBookingsFor } from './salon-bookings';

type Bookings = NonNullable<ReturnType<typeof salonBookingsFor>>;
type Rules = NonNullable<ReturnType<typeof salonBookingRulesFor>>;

const bookingsRead = (
  permission: string,
  ask: (bookings: Bookings, call: Call, query: URLSearchParams) => ReturnType<BffRead['read']>,
): BffRead => ({
  permission,
  read: (hostname, call, query) => {
    const bookings = salonBookingsFor(hostname);
    return bookings ? ask(bookings, call, query) : null;
  },
});
const rulesRead = (
  ask: (rules: Rules, call: Call, query: URLSearchParams) => ReturnType<BffRead['read']>,
  open = false,
): BffRead => ({
  permission: open ? undefined : 'settings.read',
  open,
  read: (hostname, call, query) => {
    const rules = salonBookingRulesFor(hostname);
    return rules ? ask(rules, call, query) : null;
  },
});

/** The changes a booking takes after it is placed, by the API's own names. */
const ACTIONS = ['confirm', 'release', 'arrive', 'start', 'cancel', 'no-show', 'undo-no-show'] as const;

const bookingChange = (action: (typeof ACTIONS)[number]): [string, BffChange] => [
  `booking-${action}`,
  {
    // Arriving and starting are `booking.attend` (a Professional's own bookings); every other change `booking.manage`.
    permission: action === 'arrive' || action === 'start' ? 'booking.attend' : 'booking.manage',
    change: (hostname, call, body) => salonBookingsFor(hostname)?.act(call, action, body) ?? null,
  },
];

/**
 * The booking module's workspace pages and BFF actions: the Bookings page (F07-S03), its list, one booking and the
 * API's clock with `calendar.read` (the API applies its data scope), what New appointment offers and every change but
 * arriving and starting with `booking.manage`; and the Booking rules tab of Settings (F04-S04). The rules customers see
 * and the API's clock answer anyone.
 */
export const bookingRoutes = moduleRoutes('booking', {
  pages: [
    {
      path: '/workspace/bookings',
      view: 'bookings',
      permission: 'calendar.read',
      title: 'bookings',
      // After Today's place in the prototype's order, which F10 delivers, so first after Account.
      nav: { order: 10, label: 'bookingsNav', icon: 'bookings', testId: 'workspace-bookings' },
    },
    { path: '/workspace/settings/rules', view: 'settings', permission: 'settings.read', title: 'settings' },
  ],
  reads: {
    bookings: bookingsRead('calendar.read', (bookings, call, query) => bookings.list(call, query)),
    booking: bookingsRead('calendar.read', (bookings, call, query) => bookings.detail(call, query.get('id'))),
    'booking-availability': bookingsRead('booking.manage', (bookings, call, query) =>
      bookings.availability(call, query),
    ),
    'booking-open-days': bookingsRead('booking.manage', (bookings, call, query) => bookings.openDays(call, query)),
    // The API's clock, read so that a countdown follows the server's expiry rather than the browser's.
    'booking-clock': {
      permission: 'calendar.read',
      open: true,
      read: (hostname) =>
        apiTime(hostname).then((now) =>
          now
            ? { data: { now }, response: new Response(null, { status: 200 }) }
            : { response: new Response(null, { status: 503 }) },
        ),
    },
    'booking-rules': rulesRead((rules, call) => rules.rules(call)),
    'booking-rules-history': rulesRead((rules, call, query) => {
      const page = Number(query.get('page') ?? 0);
      return rules.history(call, Number.isInteger(page) && page >= 0 ? page : 0);
    }),
    // JSON null before the rules are first saved is an answer: it is passed on as `{ current: null }`.
    'booking-rules-public': rulesRead(async (rules, call) => {
      const answer = await rules.publicRules(call);
      return answer.response.ok ? { data: { current: answer.data ?? null }, response: answer.response } : answer;
    }, true),
  },
  changes: {
    'booking-place': {
      permission: 'booking.manage',
      change: (hostname, call, body) => salonBookingsFor(hostname)?.place(call, body) ?? null,
    },
    ...Object.fromEntries(ACTIONS.map(bookingChange)),
    'booking-rules-save': {
      permission: 'settings.manage',
      change: (hostname, call, body) =>
        salonBookingRulesFor(hostname)?.saveRules(call, body.version, body.rules) ?? null,
    },
  },
});
