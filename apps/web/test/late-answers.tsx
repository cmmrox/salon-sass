import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { expect, vi } from 'vitest';

import { DataLayer } from '@/lib/api-core/data-layer';

type Held = { url: string; answer: (body: unknown) => void; signal: AbortSignal | null };

/**
 * A BFF whose answers are held until the test releases them, in any order: each request waits in `held` until
 * `answer(index, body)` sends its JSON. An aborted request is rejected as the browser rejects it.
 */
export function holdAnswers() {
  const held: Held[] = [];
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal(
    'fetch',
    (url: string, init?: RequestInit) =>
      new Promise<Response>((resolve, reject) => {
        const signal = init?.signal ?? null;
        signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
        held.push({ url, signal, answer: (body) => resolve(Response.json(body)) });
      }),
  );
  return {
    held,
    /** Sends the answer of the `index`th request made, in the order they were made. */
    answer: (index: number, body: unknown) =>
      act(async () => {
        held[index]!.answer(body);
      }),
  };
}

const wrapper = ({ children }: { children: ReactNode }) => <DataLayer scope="test">{children}</DataLayer>;

/**
 * The stale-answer proof every query hook carries (decision 121): the hook asks once, is asked again (a new question,
 * or the same one again), the later request answers first and the earlier one last; what the hook shows is the later
 * answer, never the earlier one.
 */
export async function laterAnswerWins<P>(
  use: (props: P) => { data: unknown },
  first: P,
  second: P,
  ask: (result: { refetch?: () => unknown }) => void = () => undefined,
) {
  const bff = holdAnswers();
  const hook = renderHook((props: P) => use(props) as { data: unknown; refetch?: () => unknown }, {
    wrapper,
    initialProps: first,
  });
  await waitFor(() => expect(bff.held).toHaveLength(1));
  let earlier = 0;
  if (first === second) {
    // Read, then read again twice: the second time's request answers first, the first time's last.
    await bff.answer(0, { answer: 'first' });
    await waitFor(() => expect(hook.result.current.data).toEqual({ answer: 'first' }));
    act(() => ask(hook.result.current));
    await waitFor(() => expect(bff.held).toHaveLength(2));
    act(() => ask(hook.result.current));
    earlier = 1;
  } else hook.rerender(second);
  await waitFor(() => expect(bff.held).toHaveLength(earlier + 2));
  await bff.answer(earlier + 1, { answer: 'later' });
  await waitFor(() => expect(hook.result.current.data).toEqual({ answer: 'later' }));
  await bff.answer(earlier, { answer: 'earlier' });
  // The earlier answer arrives after the later one and changes nothing on screen.
  await act(async () => undefined);
  expect(hook.result.current.data).toEqual({ answer: 'later' });
  return bff;
}
