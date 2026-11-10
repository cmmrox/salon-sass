import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { holdAnswers, laterAnswerWins } from '@test/late-answers';
import { DataLayer } from '@/lib/api-core/data-layer';
import {
  useClosures,
  useHolidays,
  useOpeningHours,
  useSignInAccounts,
  useStaffAsk,
  useTeamMembers,
} from './staff-data';

afterEach(() => {
  vi.unstubAllGlobals();
});

// Decision 121: an answer to a question that has been asked again is never shown.
describe('a later answer wins over an earlier one that arrives after it', () => {
  it.each([
    ['useTeamMembers', () => useTeamMembers('staff'), 'team-members'],
    ['useSignInAccounts', () => useSignInAccounts('staff', true), 'team-sign-in-accounts'],
    ['useOpeningHours', () => useOpeningHours('staff'), 'hours'],
    ['useHolidays', () => useHolidays('staff'), 'holidays'],
    ['useClosures', () => useClosures('staff'), 'closures'],
  ])('%s, read again', async (_name, use, action) => {
    const bff = await laterAnswerWins(use, 0, 0, (result) => void result.refetch?.());
    expect(new Set(bff.held.map((each) => each.url))).toEqual(new Set([`/identity-web/staff/${action}`]));
  });

  it('useStaffAsk: each question gets its own answer, whatever order they arrive in', async () => {
    const bff = holdAnswers();
    const wrapper = ({ children }: { children: ReactNode }) => <DataLayer scope="test">{children}</DataLayer>;
    const { result } = renderHook(() => useStaffAsk('staff'), { wrapper });
    const first = result.current('team-leave-affected', {
      memberId: 'm',
      firstDay: '2026-11-12',
      lastDay: '2026-11-14',
    });
    const second = result.current('team-leave-affected', {
      memberId: 'm',
      firstDay: '2026-11-12',
      lastDay: '2026-11-20',
    });
    await act(async () => undefined);
    await bff.answer(1, { affected: ['later'] });
    await bff.answer(0, { affected: ['earlier'] });
    expect(await second).toEqual({ affected: ['later'] });
    expect(await first).toEqual({ affected: ['earlier'] });
    expect(bff.held.map((each) => each.url)).toEqual([
      '/identity-web/staff/team-leave-affected?memberId=m&firstDay=2026-11-12&lastDay=2026-11-14',
      '/identity-web/staff/team-leave-affected?memberId=m&firstDay=2026-11-12&lastDay=2026-11-20',
    ]);
  });
});
