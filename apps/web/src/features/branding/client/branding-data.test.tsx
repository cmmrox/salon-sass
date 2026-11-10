import { afterEach, describe, expect, it, vi } from 'vitest';
import { laterAnswerWins } from '@test/late-answers';
import { useDraft } from './branding-data';

afterEach(() => {
  vi.unstubAllGlobals();
});

// Decision 121: an answer to a question that has been asked again is never shown.
describe('a later answer wins over an earlier one that arrives after it', () => {
  it.each(['profile', 'brand'] as const)('useDraft for the %s, read again', async (subject) => {
    const bff = await laterAnswerWins(
      () => useDraft('staff', subject),
      0,
      0,
      (result) => void result.refetch?.(),
    );
    expect(new Set(bff.held.map((each) => each.url))).toEqual(new Set([`/identity-web/staff/${subject}-draft`]));
  });
});
