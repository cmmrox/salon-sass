import type { BookingAction, BookingDetail, BookingHistoryEntry, BookingState } from '../types';
import { localDate } from './booking-time';

/** The actions the appointment sheet offers, in the prototype's order (`openA`); confirming is New appointment's. */
export const SHEET_ACTIONS = ['arrive', 'start', 'no-show', 'undo-no-show', 'cancel', 'release'] as const;
export type SheetAction = (typeof SHEET_ACTIONS)[number];

/** Exactly the actions the API allows on the booking now (`allowedActions`), never one it does not (AC-7, AC-10). */
export const offeredActions = (allowed: BookingAction[]): SheetAction[] =>
  SHEET_ACTIONS.filter((action) => allowed.includes(action));

/** The prototype's explanation under the actions (`openA`'s `why`), by the booking's state and what the API allows. */
export type Explanation =
  | 'arriveOnDay'
  | 'noShowAfterStart'
  | 'noCancelAfterArrival'
  | 'completedAtCheckout'
  | 'undoToday'
  | 'cancelledFinal'
  | 'completed'
  | 'held';

/**
 * Which explanation the sheet shows, or none: a confirmed booking on a later day is arrived on the day; on its day,
 * before its start, a no-show waits for the start; once the API allows a no-show nothing needs saying. The day is the
 * salon's today by the API's clock.
 */
export function explanationOf(detail: BookingDetail, today: string): Explanation | null {
  const { booking, allowedActions } = detail;
  const day = localDate(booking.startsAt);
  switch (booking.state) {
    case 'confirmed':
      if (allowedActions.includes('no-show')) return null;
      if (day > today) return 'arriveOnDay';
      return day === today ? 'noShowAfterStart' : null;
    case 'arrived':
      return 'noCancelAfterArrival';
    case 'in_service':
      return 'completedAtCheckout';
    case 'no_show':
      return allowedActions.includes('undo-no-show') ? 'undoToday' : null;
    case 'cancelled':
      return 'cancelledFinal';
    case 'completed':
      return 'completed';
    case 'held':
      return 'held';
    default:
      return null;
  }
}

/** What a change in the history says, in the prototype's words (`hist.t`): by where it went, and from where. */
export type Change =
  | 'held'
  | 'confirmed'
  | 'arrived'
  | 'started'
  | 'noShow'
  | 'noShowUndone'
  | 'cancelled'
  | 'paymentStarted'
  | 'paymentReturned'
  | 'completed';

export function changeOf(entry: Pick<BookingHistoryEntry, 'from' | 'to'>): Change {
  const to: BookingState = entry.to;
  switch (to) {
    case 'held':
      return entry.from === 'pending_payment' ? 'paymentReturned' : 'held';
    case 'confirmed':
      return entry.from === 'no_show' ? 'noShowUndone' : 'confirmed';
    case 'arrived':
      return 'arrived';
    case 'in_service':
      return 'started';
    case 'no_show':
      return 'noShow';
    case 'cancelled':
      return 'cancelled';
    case 'pending_payment':
      return 'paymentStarted';
    default:
      return 'completed';
  }
}

/** Who made a change (decision 111): the staff account's label, a team member no longer known, or the system. */
export type Actor = { kind: 'label'; label: string } | { kind: 'teamMember' } | { kind: 'system' };
export const actorOf = (entry: Pick<BookingHistoryEntry, 'by'>): Actor =>
  entry.by.kind === 'system'
    ? { kind: 'system' }
    : entry.by.label
      ? { kind: 'label', label: entry.by.label }
      : { kind: 'teamMember' };

/** The history newest first, as the sheet lists it (the API answers it oldest first). */
export const newestFirst = (history: BookingHistoryEntry[]) => [...history].reverse();
