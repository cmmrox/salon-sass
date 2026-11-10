'use client';
import { useQuery } from '@tanstack/react-query';
import { moduleData, useDataScope } from '@/lib/api-core/data-layer';
import { read, workspaceSession, type Query, type RequestFailure, type WorkspaceKind } from '@/lib/api-core/request';
import type {
  Availability,
  Booking,
  BookingDetail,
  BookingPage,
  BookingRulesHistory,
  BookingRulesSettings,
  OpenDays,
  PublicBookingRules,
} from '../types';

/** The booking module's BFF reads: its rules (F04-S04) and its bookings (F07-S03), each with its query and answer. */
export type BookingReads = {
  'booking-rules': { answer: BookingRulesSettings };
  'booking-rules-history': { query: { page: number }; answer: BookingRulesHistory };
  /** The salon's rules as customers see them; null before the first save. */
  'booking-rules-public': { answer: { current: PublicBookingRules | null } };
  bookings: { query: Query; answer: BookingPage };
  booking: { query: { id: string }; answer: BookingDetail };
  /** The API's clock, so that a countdown follows the server's expiry (F07-S03). */
  'booking-clock': { answer: { now: string } };
  'booking-availability': { query: Query; answer: Availability };
  'booking-open-days': { query: { from: string; to: string }; answer: OpenDays };
};

type Body = Record<string, unknown>;

/** The booking module's BFF changes, each with what it answers. */
type BookingChanges = {
  'booking-rules-save': { body: Body; answer: BookingRulesSettings };
  'booking-place': { body: Body; answer: Booking };
  'booking-confirm': { body: Body; answer: Booking };
  'booking-release': { body: Body; answer: { ok: true } };
  'booking-arrive': { body: Body; answer: Booking };
  'booking-start': { body: Body; answer: Booking };
  'booking-cancel': { body: Body; answer: Booking };
  'booking-no-show': { body: Body; answer: Booking };
  'booking-undo-no-show': { body: Body; answer: Booking };
};

const booking = moduleData<BookingReads, BookingChanges>('booking');

/** The salon's booking rules as customers see them; the screens that only word a deposit work without them. */
export const usePublicBookingRules = (kind: WorkspaceKind) =>
  booking.useRead(workspaceSession(kind), 'booking-rules-public');

/** The salon's booking rules as Settings edits them (F04-S04). */
export const useBookingRulesRead = (kind: WorkspaceKind) => booking.useRead(workspaceSession(kind), 'booking-rules');

/** One page of the rules' history, read while it is asked for. */
export const useBookingRulesHistory = (kind: WorkspaceKind, page: number, enabled: boolean) =>
  booking.useRead(workspaceSession(kind), 'booking-rules-history', { page }, { enabled });

/** Puts a change's answer in place of a read's. */
export const useSetBookingAnswer = (kind: WorkspaceKind) => booking.useSetAnswer(workspaceSession(kind));

/**
 * How often the day's bookings are read again while the page is open and in view (decision 121, web M2): other
 * people book, confirm and cancel at the desk and online, and their changes appear within this long without anyone
 * acting. Also read again when the window comes back into view.
 */
export const BOOKINGS_POLL_MS = 30_000;

/**
 * One day's page of bookings, `query` being what the list asks (null until the day is known). It is read again every
 * {@link BOOKINGS_POLL_MS}; while another question is read the last page stays on screen, marked as a placeholder.
 */
export const useBookingList = (kind: WorkspaceKind, query: Query | null) =>
  booking.useRead(workspaceSession(kind), 'bookings', query ?? {}, {
    enabled: query !== null,
    refetchInterval: BOOKINGS_POLL_MS,
    refetchOnWindowFocus: true,
    keepPrevious: true,
  });

/**
 * One booking with its history and the changes it allows, read while its sheet is open (`enabled`); the answer stays
 * while the sheet closes, and is forgotten once another booking is shown.
 */
export const useBookingDetail = (kind: WorkspaceKind, id: string | null, enabled = id !== null) =>
  booking.useRead(
    workspaceSession(kind),
    'booking',
    { id: id ?? '' },
    { enabled: enabled && id !== null, forget: true },
  );

/**
 * The API's clock against the browser's (F07-S03): its offset in milliseconds, the answer's instant taken as the middle
 * of the round trip, and the API's now when it answered. Read once; `refetch` reads it again.
 */
export function useApiOffset(kind: WorkspaceKind) {
  const scope = useDataScope();
  const session = workspaceSession(kind);
  return useQuery<{ offset: number; now: number }, RequestFailure>({
    queryKey: booking.keyOf(scope, session, 'booking-clock'),
    queryFn: async ({ signal }) => {
      const asked = Date.now();
      const { now } = await read<BookingReads['booking-clock']['answer']>(session, 'booking-clock', undefined, signal);
      const offset = Date.parse(now) - (asked + Date.now()) / 2;
      return { offset, now: Date.now() + offset };
    },
    staleTime: Infinity,
  });
}

/** The booking questions asked when something happens: free times for a search, open days, a hold as it is now. */
export const useBookingAsk = (kind: WorkspaceKind) => booking.useAsk(workspaceSession(kind));

/** The booking module's changes, each made once and typed by its action. */
export const useBookingChanges = (kind: WorkspaceKind) => booking.useChanges(workspaceSession(kind));
