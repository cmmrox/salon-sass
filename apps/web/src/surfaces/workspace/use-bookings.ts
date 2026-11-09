'use client';
import { usePathname, useSearchParams } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { listRequest, queryOf, searchOf, type Booking, type BookingPage, type BookingsQuery } from '@/features/booking';
import type { Kind } from '@/features/identity';
import type { TeamMemberList } from '@/features/staff';
import { bookingRead, failureOf } from './booking-requests';

/** How long the search waits after the last key before it asks the API (AC-3, "applied as typed"). */
const SEARCH_WAIT_MS = 300;

/** Why the list is not shown: the API could not be reached, or refused this person. */
export type ListProblem = 'unavailable' | 'refused' | null;

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
  const [page, setPage] = useState<BookingPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [problem, setProblem] = useState<ListProblem>(null);
  const [members, setMembers] = useState<{ id: string; name: string }[] | null>(null);
  const [reloads, setReloads] = useState(0);
  const asked = useRef(0);

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
  useEffect(() => {
    if (!request) return;
    const mine = ++asked.current;
    let active = true;
    Promise.resolve().then(() => active && setLoading(true));
    bookingRead<BookingPage>(kind, request)
      .then((answer) => {
        if (mine !== asked.current) return;
        setPage(answer);
        setProblem(null);
      })
      .catch((error) => {
        if (mine !== asked.current) return;
        const failure = failureOf(error);
        if (failure.code === 'invalid_session') return leave(failure);
        setProblem(
          failure.code === 'service_unavailable' || failure.code === 'browser_unsupported' ? 'unavailable' : 'refused',
        );
      })
      .finally(() => mine === asked.current && setLoading(false));
    return () => {
      active = false;
    };
  }, [kind, request, reloads, leave]);

  useEffect(() => {
    if (!withProfessionals) return;
    let active = true;
    bookingRead<TeamMemberList>(kind, 'team-members')
      .then((team) => active && setMembers(team.items.map(({ id, name }) => ({ id, name }))))
      .catch(() => active && setMembers(null));
    return () => {
      active = false;
    };
  }, [kind, withProfessionals]);

  // A hold whose time has run out is no longer the API's to list: read the list again, and show what it says.
  const items = page?.items ?? [];
  const expired =
    now === null
      ? null
      : items.find((each) => each.state === 'held' && each.holdExpiresAt && Date.parse(each.holdExpiresAt) <= now);
  const expiredKey = expired ? `${expired.id}@${expired.holdExpiresAt}` : null;
  useEffect(() => {
    if (!expiredKey) return;
    let active = true;
    Promise.resolve().then(() => active && setReloads((n) => n + 1));
    return () => {
      active = false;
    };
  }, [expiredKey]);

  return {
    query,
    day,
    typed,
    page,
    loading: loading || (!page && !problem),
    problem,
    members,
    holdsShown: items.some((each: Booking) => each.state === 'held'),
    search: setTyped,
    change,
    clear: () => {
      setTyped('');
      change({ q: '', status: 'all', professional: null });
    },
    openBooking: (id: string | null) => show({ ...queryOf(params), booking: id }, false),
    reload: () => setReloads((n) => n + 1),
  };
}
