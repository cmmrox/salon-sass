import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { holdAnswers, laterAnswerWins } from '@test/late-answers';
import { DataLayer } from '@/lib/api-core/data-layer';
import {
  BOOKINGS_POLL_MS,
  useApiOffset,
  useBookingDetail,
  useBookingList,
  useBookingRulesHistory,
  useBookingRulesRead,
  usePublicBookingRules,
} from './booking-data';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const wrapper = ({ children }: { children: ReactNode }) => <DataLayer scope="test">{children}</DataLayer>;

// Decision 121: an answer to a question that has been asked again, or replaced, is never shown.
describe('a later answer wins over an earlier one that arrives after it', () => {
  it('useBookingList, its day changed', async () => {
    const bff = await laterAnswerWins(
      (day: string) => {
        const read = useBookingList('staff', { from: day, to: day, page: 0, size: 10 });
        return { data: read.isPlaceholderData ? undefined : read.data };
      },
      '2026-11-12',
      '2026-11-13',
    );
    expect(bff.held.map((each) => each.url)).toEqual([
      '/identity-web/staff/bookings?from=2026-11-12&to=2026-11-12&page=0&size=10',
      '/identity-web/staff/bookings?from=2026-11-13&to=2026-11-13&page=0&size=10',
    ]);
  });

  it('useBookingDetail, another booking opened', async () => {
    const bff = await laterAnswerWins((id: string) => useBookingDetail('staff', id), 'a', 'b');
    expect(bff.held.map((each) => each.url)).toEqual([
      '/identity-web/staff/booking?id=a',
      '/identity-web/staff/booking?id=b',
    ]);
  });

  it('useBookingRulesRead, read again', async () => {
    await laterAnswerWins(
      () => useBookingRulesRead('staff'),
      0,
      0,
      (result) => void result.refetch?.(),
    );
  });

  it('useBookingRulesHistory, its page changed', async () => {
    const bff = await laterAnswerWins((page: number) => useBookingRulesHistory('staff', page, true), 0, 1);
    expect(bff.held.map((each) => each.url)).toEqual([
      '/identity-web/staff/booking-rules-history?page=0',
      '/identity-web/staff/booking-rules-history?page=1',
    ]);
  });

  it('usePublicBookingRules, read again', async () => {
    await laterAnswerWins(
      () => usePublicBookingRules('staff'),
      0,
      0,
      (result) => void result.refetch?.(),
    );
  });

  it('useApiOffset, read again', async () => {
    const bff = holdAnswers();
    const { result } = renderHook(() => useApiOffset('staff'), { wrapper });
    await waitFor(() => expect(bff.held).toHaveLength(1));
    await bff.answer(0, { now: new Date(Date.now()).toISOString() });
    await waitFor(() => expect(result.current.data).toBeDefined());
    act(() => void result.current.refetch());
    await waitFor(() => expect(bff.held).toHaveLength(2));
    act(() => void result.current.refetch());
    await waitFor(() => expect(bff.held).toHaveLength(3));
    // The later read says the API is an hour ahead; the earlier one, answering last, that it is an hour behind.
    await bff.answer(2, { now: new Date(Date.now() + 3_600_000).toISOString() });
    await waitFor(() => expect(result.current.data?.offset).toBeGreaterThan(3_500_000));
    await bff.answer(1, { now: new Date(Date.now() - 3_600_000).toISOString() });
    await act(async () => undefined);
    expect(result.current.data?.offset).toBeGreaterThan(3_500_000);
  });
});

describe('the day’s bookings follow other people’s changes (web M2)', () => {
  it(`reads the list again every ${BOOKINGS_POLL_MS / 1000} seconds without anyone acting`, async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let asked = 0;
    vi.stubGlobal('fetch', async () => {
      asked++;
      return Response.json({ items: [], page: 0, size: 10, total: asked });
    });
    const { result } = renderHook(() => useBookingList('staff', { from: '2026-11-12', to: '2026-11-12' }), {
      wrapper,
    });
    await waitFor(() => expect(result.current.data?.total).toBe(1));
    await vi.advanceTimersByTimeAsync(BOOKINGS_POLL_MS - 1000);
    expect(asked).toBe(1);
    await vi.advanceTimersByTimeAsync(1000);
    await waitFor(() => expect(result.current.data?.total).toBe(2));
  });
});
