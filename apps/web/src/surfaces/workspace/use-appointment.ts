'use client';
import { useState } from 'react';
import {
  useBookingChanges,
  useBookingDetail,
  type AppointmentRefusal,
  type Booking,
  type CancelReason,
  type SheetAction,
} from '@/features/booking';
import type { Kind } from '@/features/identity';
import { useReadFailure } from '@/lib/api-core/data-layer';
import { failureOf } from './booking-requests';

/** The refusal each API code is said with on the appointment sheet (AC-9). */
const REFUSALS: Record<string, AppointmentRefusal> = {
  invalid_state: 'changed',
  not_found: 'gone',
  hold_expired: 'gone',
  slot_taken: 'taken',
  permission_denied: 'permission',
  support_read_only: 'support',
  salon_read_only: 'salonReadOnly',
  service_unavailable: 'offline',
  browser_unsupported: 'offline',
};

/**
 * The appointment sheet's state (F07-S03 AC-7 to AC-9): the booking named in the address, its detail read through the
 * BFF, each allowed change sent and the sheet and list then showing the new state; a refusal said and, when the
 * booking changed meanwhile, the booking read again.
 */
export function useAppointment(
  kind: Kind,
  id: string | null,
  onDone: (action: SheetAction, booking: Booking | null) => void,
  onStale: () => void,
  leave: (session: unknown) => void,
) {
  const [refusal, setRefusal] = useState<AppointmentRefusal | null>(null);
  const [busy, setBusy] = useState<SheetAction | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [cancelOpened, setCancelOpened] = useState(0);
  // The sheet keeps the booking it showed while it closes, so focus can go back to its row.
  const [shown, setShown] = useState<string | null>(id);
  if (id && id !== shown) {
    setShown(id);
    setRefusal(null);
  }
  const read = useBookingDetail(kind, shown, id !== null);
  const changes = useBookingChanges(kind);
  const gone = read.error?.code === 'not_found';
  const detail = gone ? null : (read.data ?? null);
  const loading = read.isFetching;
  const unavailable = Boolean(read.error && !gone && read.error.code !== 'invalid_session');
  useReadFailure([read], (failure) => {
    if (failure.code === 'invalid_session') leave(failure);
    else if (failure.code === 'not_found') setRefusal('gone');
  });
  const readAgain = () => void read.refetch();

  async function send(action: SheetAction, body: Record<string, unknown> = {}) {
    if (!detail || busy) return;
    const booking = detail.booking;
    setBusy(action);
    setRefusal(null);
    try {
      const answer: Booking | { ok: true } = await changes.run(`booking-${action}`, { id: booking.id, ...body });
      onDone(action, 'reference' in answer ? answer : null);
      if (action !== 'release') readAgain();
    } catch (error) {
      const failure = failureOf(error);
      if (failure.code === 'invalid_session') leave(failure);
      else {
        setRefusal(REFUSALS[failure.code] ?? 'failed');
        // The booking changed elsewhere, or went: show it as it is now, and the list with it.
        if (['invalid_state', 'not_found', 'hold_expired', 'slot_taken'].includes(failure.code)) {
          readAgain();
          onStale();
        }
      }
    }
    setBusy(null);
  }

  return {
    detail,
    loading,
    unavailable,
    refusal,
    busy,
    cancelling,
    cancelOpened,
    retry: readAgain,
    act: (action: SheetAction) => {
      if (action === 'cancel') {
        setCancelOpened((n) => n + 1);
        setCancelling(true);
      } else void send(action);
    },
    keep: () => setCancelling(false),
    cancel: (reason: CancelReason, note: string) => {
      setCancelling(false);
      void send('cancel', { reason, note });
    },
  };
}
