import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { holdAnswers, laterAnswerWins } from '@test/late-answers';
import { DataLayer } from '@/lib/api-core/data-layer';
import { useConsolePages, useConsoleRead, type ConsoleReads } from './console-data';

const router = { push: vi.fn(), replace: vi.fn(), refresh: vi.fn() };
vi.mock('next/navigation', () => ({ useRouter: () => router }));

afterEach(() => {
  vi.unstubAllGlobals();
  router.replace.mockReset();
});

const wrapper = ({ children }: { children: ReactNode }) => <DataLayer scope="test">{children}</DataLayer>;

/** A console read as the late-answer proof takes it: its answer, and asking it again. */
function asRead<A extends keyof ConsoleReads & string>(read: ReturnType<typeof useConsoleRead<A>>) {
  return { data: read.data ?? undefined, refetch: read.load };
}

// Decision 121: an answer to a question that has been asked again, or replaced, is never shown.
describe('a later answer wins over an earlier one that arrives after it', () => {
  it.each([
    ['salon', (id: string) => asRead(useConsoleRead('salon', { id })), '/operator-web/salon?id='],
    ['plan', (id: string) => asRead(useConsoleRead('plan', { id })), '/operator-web/plan?id='],
    ['subscription', (id: string) => asRead(useConsoleRead('subscription', { id })), '/operator-web/subscription?id='],
    ['export', (id: string) => asRead(useConsoleRead('export', { id })), '/operator-web/export?id='],
  ])('useConsoleRead(%s), another one opened', async (_name, use, address) => {
    const bff = await laterAnswerWins(use, 'a', 'b');
    expect(bff.held.map((each) => each.url)).toEqual([`${address}a`, `${address}b`]);
  });

  it('useConsoleRead(holiday-year), another year opened', async () => {
    const bff = await laterAnswerWins((year: number) => asRead(useConsoleRead('holiday-year', { year })), 2026, 2027);
    expect(bff.held.map((each) => each.url)).toEqual([
      '/operator-web/holiday-year?year=2026',
      '/operator-web/holiday-year?year=2027',
    ]);
  });

  it('useConsoleRead(holiday-years), read again', async () => {
    const bff = await laterAnswerWins(
      () => asRead(useConsoleRead('holiday-years')),
      0,
      0,
      (result) => void result.refetch?.(),
    );
    expect(new Set(bff.held.map((each) => each.url))).toEqual(new Set(['/operator-web/holiday-years']));
  });

  it.each<[string, () => { data: unknown; page: number; load: (page: number) => void }]>([
    ['salons', () => useConsolePages('salons', (page, q) => ({ page, size: 20, ...(q ? { q } : {}) }))],
    ['plans', () => useConsolePages('plans', (page, q) => ({ page, size: 20, ...(q ? { q } : {}) }))],
    ['invoices', () => useConsolePages('invoices', (page) => ({ id: 'a', page, size: 20 }))],
    ['lifecycle', () => useConsolePages('lifecycle', (page) => ({ id: 'a', page, size: 20 }))],
    ['support-sessions', () => useConsolePages('support-sessions', (page) => ({ page, size: 20 }))],
  ])(
    'useConsolePages(%s): the page on screen is the last one asked for, whatever order they answer in',
    async (action, use) => {
      const bff = holdAnswers();
      const { result } = renderHook(use, { wrapper });
      await waitFor(() => expect(bff.held).toHaveLength(1));
      await bff.answer(0, { items: ['page 0'], page: 0, size: 20, total: 60 });
      await waitFor(() => expect(result.current.page).toBe(0));
      act(() => result.current.load(1));
      await waitFor(() => expect(bff.held).toHaveLength(2));
      act(() => result.current.load(2));
      await waitFor(() => expect(bff.held).toHaveLength(3));
      // The page on screen does not move until the page asked for arrives.
      expect(result.current.data).toMatchObject({ items: ['page 0'] });
      await bff.answer(2, { items: ['page 2'], page: 2, size: 20, total: 60 });
      await waitFor(() => expect(result.current.page).toBe(2));
      await bff.answer(1, { items: ['page 1'], page: 1, size: 20, total: 60 });
      await act(async () => undefined);
      expect(result.current.page).toBe(2);
      expect(result.current.data).toMatchObject({ items: ['page 2'] });
      expect(bff.held[2]!.url).toContain(`/operator-web/${action}?`);
      expect(bff.held[2]!.url).toContain('page=2');
    },
  );
});

describe('an ended console session', () => {
  it('goes to sign-in, and is not said as a failure', async () => {
    vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
    vi.stubGlobal('BroadcastChannel', undefined);
    vi.stubGlobal('fetch', async () => Response.json({ code: 'invalid_session', renewable: false }, { status: 401 }));
    const { result } = renderHook(() => useConsoleRead('salon', { id: 'a' }), { wrapper });
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/console/sign-in'));
    expect(result.current.failure).toBeNull();
  });
});
