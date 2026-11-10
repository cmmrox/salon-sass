import { afterEach, describe, expect, it, vi } from 'vitest';
import { laterAnswerWins } from '@test/late-answers';
import { useEntitlements, useInvoices, useSupportSessions } from './salon-account-data';

afterEach(() => {
  vi.unstubAllGlobals();
});

const shown = (read: { data: unknown; isPlaceholderData: boolean }) => ({
  data: read.isPlaceholderData ? undefined : read.data,
});

// Decision 121: an answer to a question that has been asked again, or replaced, is never shown.
describe('a later answer wins over an earlier one that arrives after it', () => {
  it('useEntitlements, read again', async () => {
    await laterAnswerWins(
      () => useEntitlements('staff'),
      0,
      0,
      (result) => void result.refetch?.(),
    );
  });

  it.each([
    ['useInvoices', (page: number) => shown(useInvoices('staff', page)), 'invoices'],
    ['useSupportSessions', (page: number) => shown(useSupportSessions('staff', page)), 'support-sessions'],
  ])('%s, its page changed', async (_name, use, action) => {
    const bff = await laterAnswerWins(use, 0, 1);
    expect(bff.held.map((each) => each.url)).toEqual([
      `/identity-web/staff/${action}?page=0`,
      `/identity-web/staff/${action}?page=1`,
    ]);
  });
});
