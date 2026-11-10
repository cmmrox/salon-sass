'use client';
import { useEffect, useState } from 'react';
import { dateInZone, useApiOffset } from '@/features/booking';
import type { Kind } from '@/features/identity';

/**
 * The API's clock in the browser (F07-S03): its offset from the browser's, read once when the page opens, so that a
 * countdown follows the server's expiry and "today" is the salon's by the API (whose clock a test may move). `now`
 * moves each second while `ticking`. Null until read; `failed` when it could not be.
 */
export function useApiClock(kind: Kind, timeZone: string, ticking: boolean) {
  const clock = useApiOffset(kind);
  const offset = clock.data?.offset ?? null;
  // The last tick, for the offset it was made with: a clock read again starts from its own answer.
  const [tick, setTick] = useState<{ of: number; now: number } | null>(null);
  useEffect(() => {
    if (offset === null || !ticking) return;
    const id = setInterval(() => setTick({ of: offset, now: Date.now() + offset }), 1000);
    return () => clearInterval(id);
  }, [offset, ticking]);
  const now = clock.data ? (tick?.of === offset ? tick.now : clock.data.now) : null;
  const failed = clock.isError;

  return {
    /** The API's now when last ticked, in milliseconds. */
    now,
    /** The API's now at the moment of asking. */
    current: () => (offset === null ? null : Date.now() + offset),
    today: now === null ? null : dateInZone(timeZone, now),
    failed,
    retry: () => void clock.refetch(),
  };
}
