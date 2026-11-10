'use client';
import { useEffect, useState } from 'react';
import { dateInZone } from '@/features/booking';
import type { Kind } from '@/features/identity';
import { bookingRead } from './booking-requests';

/**
 * The API's clock in the browser (F07-S03): its offset from the browser's, read once when the page opens, so that a
 * countdown follows the server's expiry and "today" is the salon's by the API (whose clock a test may move). `now`
 * moves each second while `ticking`. Null until read; `failed` when it could not be.
 */
export function useApiClock(kind: Kind, timeZone: string, ticking: boolean) {
  const [offset, setOffset] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const [now, setNow] = useState<number | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    const asked = Date.now();
    bookingRead<{ now: string }>(kind, 'booking-clock')
      .then(({ now: server }) => {
        if (!active) return;
        // The answer's instant is taken as the middle of the round trip.
        const next = Date.parse(server) - (asked + Date.now()) / 2;
        setOffset(next);
        setNow(Date.now() + next);
        setFailed(false);
      })
      .catch(() => active && setFailed(true));
    return () => {
      active = false;
    };
  }, [kind, attempt]);

  useEffect(() => {
    if (offset === null || !ticking) return;
    const id = setInterval(() => setNow(Date.now() + offset), 1000);
    return () => clearInterval(id);
  }, [offset, ticking]);

  return {
    /** The API's now when last ticked, in milliseconds. */
    now,
    /** The API's now at the moment of asking. */
    current: () => (offset === null ? null : Date.now() + offset),
    today: now === null ? null : dateInZone(timeZone, now),
    failed,
    retry: () => setAttempt((n) => n + 1),
  };
}
