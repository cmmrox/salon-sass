import { afterEach, describe, it, vi } from 'vitest';
import { laterAnswerWins } from '@test/late-answers';
import { usePrincipal } from './identity-data';

afterEach(() => {
  vi.unstubAllGlobals();
});

// Decision 121: an answer to a question that has been asked again is never shown.
describe('a later answer wins over an earlier one that arrives after it', () => {
  it('usePrincipal, read again', async () => {
    await laterAnswerWins(
      () => usePrincipal('staff'),
      0,
      0,
      (result) => void result.refetch?.(),
    );
  });
});
