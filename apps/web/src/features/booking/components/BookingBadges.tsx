'use client';
import { useTranslations } from 'next-intl';
import { Badge } from '@/components/ui/badge';
import { countdownText, secondsLeft } from '../client/booking-time';
import type { Booking, BookingState } from '../types';

/** The prototype's status tones (`ST_LBL`, the F07 block): ok, warning for a hold, information, neutral and error. */
const TONES: Record<BookingState, 'positive' | 'caution' | 'info' | 'secondary' | 'destructive'> = {
  held: 'caution',
  pending_payment: 'caution',
  confirmed: 'positive',
  arrived: 'info',
  in_service: 'info',
  completed: 'secondary',
  cancelled: 'destructive',
  no_show: 'destructive',
};

/**
 * A booking's status Badge (the prototype's `f7Badges`): its state in words, with a hold's time left by the API's
 * clock ("Held · 8:41 left"), counting down as `now` moves.
 */
export function StatusBadge({
  booking,
  now,
  testId,
}: {
  booking: Pick<Booking, 'state' | 'holdExpiresAt'>;
  /** The API's now, in milliseconds; null before it is known. */
  now: number | null;
  testId: string;
}) {
  const t = useTranslations('bookings');
  const expires = booking.state === 'held' && now !== null ? booking.holdExpiresAt : null;
  return (
    <Badge variant={TONES[booking.state]} data-testid={testId} data-state={booking.state}>
      {expires && now !== null
        ? t('heldLeft', { left: countdownText(secondsLeft(expires, now)) })
        : t(`states.${booking.state}`)}
    </Badge>
  );
}

/** Where a booking came from, as the prototype's neutral pill. */
export function SourceBadge({ booking, testId }: { booking: Pick<Booking, 'channel'>; testId: string }) {
  const t = useTranslations('bookings');
  return (
    <Badge variant="secondary" data-testid={testId} data-channel={booking.channel}>
      {t(`channels.${booking.channel}`)}
    </Badge>
  );
}
