import { vi } from 'vitest';

/**
 * Makes `AbortSignal.timeout` follow Vitest's fake clock. Node's own timeout signal runs on a timer that the fake
 * timers do not control, so a test that advances the fake clock would otherwise wait out the real time limit (and
 * fail on a slow runner). Call it after `vi.useFakeTimers()`; `vi.restoreAllMocks()` puts the original back.
 */
export function abortTimeoutsOnTheFakeClock() {
  vi.spyOn(AbortSignal, 'timeout').mockImplementation((milliseconds: number) => {
    const controller = new AbortController();
    setTimeout(
      () => controller.abort(new DOMException('The operation was aborted due to timeout', 'TimeoutError')),
      milliseconds,
    );
    return controller.signal;
  });
}
