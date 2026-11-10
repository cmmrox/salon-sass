'use client';
import { usePathname, useSearchParams } from 'next/navigation';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  listRequest,
  PAGE_SIZE,
  queryOf,
  searchOf,
  type Booking,
  type BookingPage,
  type BookingsQuery,
  useBookingList,
} from '@/features/booking';
import type { Kind } from '@/features/identity';
import { useTeamMembers } from '@/features/staff';
import { useReadFailure } from '@/lib/api-core/data-layer';

/** How long to wait before asking again whether a hold the API still lists has run out by its clock. */
const RECHECK_MS = 2000;

/** How long the search waits after the last key before it asks the API (AC-3, "applied as typed"). */
const SEARCH_WAIT_MS = 300;

/** Why the list is not shown: the API could not be reached, or refused this person. */
type ListProblem = 'unavailable' | 'refused' | null;

/**
 * The Bookings page's list (F07-S03 AC-2, AC-3): the day and filters kept in the address (pushed, so back restores
 * them; the search replaced as it is typed), one day's page read through the BFF, the team for the professional
 * filter, and the list read again when a hold on it runs out, so that its row leaves without a reload.
 */
export function useBookings(
  kind: Kind,
  today: string | null,
  now: number | null,
  withProfessionals: boolean,
  leave: (session: unknown) => void,
) {
  const params = useSearchParams();
  const pathname = usePathname();
  const query = queryOf(params);
  const day = query.day ?? today;
  const [typed, setTyped] = useState(query.q);

  /** Shows a query: pushed as a new entry, or replacing the current one (the search as it is typed, a sheet). */
  function show(next: BookingsQuery, push = true) {
    const url = `${pathname}${searchOf(next)}`;
    if (push) window.history.pushState(null, '', url);
    else window.history.replaceState(null, '', url);
  }
  const change = (patch: Partial<BookingsQuery>, push = true) => show({ ...queryOf(params), page: 1, ...patch }, push);

  // The address changed by back or forward: the search box follows it.
  const [shownQ, setShownQ] = useState(query.q);
  if (shownQ !== query.q) {
    setShownQ(query.q);
    setTyped(query.q);
  }

  useEffect(() => {
    if (typed === query.q) return;
    const id = setTimeout(() => change({ q: typed }, false), SEARCH_WAIT_MS);
    return () => clearTimeout(id);
    // The search waits for typing to pause; the rest of the query is read when it fires.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [typed]);

  const request = day ? listRequest(query, day, withProfessionals) : null;
  // The list's question as values; the same question asked again is one key, read again every BOOKINGS_POLL_MS.
  const asking = useMemo(
    () => (request ? Object.fromEntries(new URLSearchParams(request.slice(request.indexOf('?') + 1))) : null),
    [request],
  );
  const list = useBookingList(kind, asking);
  const [reloading, setReloading] = useState(false);
  const answer = list.isPlaceholderData ? undefined : list.data;
  // A later page emptied under the person (a change, a hold run out) while the day still has bookings: the last page
  // that exists is shown instead, the address corrected in place so back does not return to the empty one
  // (BUG-F07-004). The address is read as it is now, after whatever the change did to it (a sheet closed).
  const overshot = Boolean(
    answer &&
    answer.items.length === 0 &&
    answer.total > 0 &&
    query.page > Math.max(1, Math.ceil(answer.total / PAGE_SIZE)),
  );
  useEffect(() => {
    if (!overshot || !answer) return;
    const last = Math.max(1, Math.ceil(answer.total / PAGE_SIZE));
    const shown = queryOf(new URLSearchParams(window.location.search));
    if (shown.page > last)
      window.history.replaceState(null, '', `${window.location.pathname}${searchOf({ ...shown, page: last })}`);
  }, [overshot, answer]);
  // The page on screen: the last good answer, kept while another question is read and while a read fails.
  const [kept, setKept] = useState<BookingPage | null>(null);
  if (answer && !overshot && answer !== kept) setKept(answer);
  const page = answer && !overshot ? answer : kept;
  const failure = list.error;
  useReadFailure([list], (failed) => {
    if (failed.code === 'invalid_session') leave(failed);
  });
  // A read that fails while this question's last good list is on screen keeps it and says it may be out of date (web
  // M2); one that fails with nothing of it to show says why instead of the list.
  const stale = Boolean(failure && list.data && !list.isPlaceholderData);
  const problem: ListProblem =
    !failure || stale || failure.code === 'invalid_session'
      ? null
      : ['service_unavailable', 'browser_unsupported'].includes(failure.code)
        ? 'unavailable'
        : 'refused';

  const team = useTeamMembers(kind, withProfessionals);
  const members = withProfessionals && team.data ? team.data.items.map(({ id, name }) => ({ id, name })) : null;

  /** Reads the list again now, saying it is loading, as when the person asked for it. */
  const { refetch } = list;
  const reload = useCallback(() => {
    setReloading(true);
    void refetch().finally(() => setReloading(false));
  }, [refetch]);

  // A hold whose time has run out is no longer the API's to list: read the list again, and show what it says. The API
  // decides: while it still lists the hold (a read that reached it a moment before the expiry by its clock), ask again
  // every few seconds, as the sheet does (BUG-F07-001).
  const items = page?.items ?? [];
  const expired =
    now === null
      ? null
      : items.find((each) => each.state === 'held' && each.holdExpiresAt && Date.parse(each.holdExpiresAt) <= now);
  const expiredKey = expired ? `${expired.id}@${expired.holdExpiresAt}` : null;
  const askedAfter = useRef<string | null>(null);
  useEffect(() => {
    if (!expiredKey) return;
    // The first read at zero goes at once; each read that still lists the hold (a new answer) waits before the next.
    const id = setTimeout(
      () => {
        askedAfter.current = expiredKey;
        reload();
      },
      askedAfter.current === expiredKey ? RECHECK_MS : 0,
    );
    return () => clearTimeout(id);
  }, [expiredKey, list.dataUpdatedAt, reload]);

  return {
    query,
    day,
    typed,
    page,
    loading:
      reloading || overshot || (list.isFetching && (list.isPlaceholderData || !list.data)) || (!page && !problem),
    problem,
    /** The last good list is on screen, but reading it again failed: it may be out of date. */
    stale,
    members,
    holdsShown: items.some((each: Booking) => each.state === 'held'),
    search: setTyped,
    change,
    clear: () => {
      setTyped('');
      change({ q: '', status: 'all', professional: null });
    },
    openBooking: (id: string | null) => show({ ...queryOf(params), booking: id }, false),
    reload,
  };
}
