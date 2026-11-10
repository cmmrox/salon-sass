'use client';
import { moduleData } from '@/lib/api-core/data-layer';
import { workspaceSession, type Query, type WorkspaceKind } from '@/lib/api-core/request';
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
export type BookingChanges = {
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

export const booking = moduleData<BookingReads, BookingChanges>('booking');

/** The salon's booking rules as customers see them; the screens that only word a deposit work without them. */
export const usePublicBookingRules = (kind: WorkspaceKind) =>
  booking.useRead(workspaceSession(kind), 'booking-rules-public');
