'use client';
import {
  QueryClient,
  QueryClientProvider,
  notifyManager,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryKey,
} from '@tanstack/react-query';
import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { change, read, type ChangeMap, type Query, type ReadMap, type RequestFailure, type Session } from './request';

/**
 * The client data layer (decision 121): TanStack Query over the one request module. One client per data scope (the
 * salon's hostname, the session's kind and the session itself, decided on the server), so nothing one person or one
 * salon read can be shown for another; every key starts with that scope; the client is cleared when its frame goes
 * (sign-out and an ended session leave the frame) and replaced when the scope changes.
 */
const ScopeContext = createContext<string | null>(null);

// An answer reaches its screen as soon as it arrives, in the same turn as the request's own promise, as it did before
// this layer: the library's default waits a further timer turn, in which a screen shows its loading state a moment
// longer and a person can act on what is about to change.
notifyManager.setScheduler(queueMicrotask);

/** Reads are not retried by the layer: a screen says at once that the API could not be reached, as it always has. */
export function newQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false },
      mutations: { retry: false },
    },
  });
}

function ScopedClient({ scope, children }: { scope: string; children: ReactNode }) {
  const [client] = useState(newQueryClient);
  useEffect(() => () => client.clear(), [client]);
  return (
    <ScopeContext.Provider value={scope}>
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    </ScopeContext.Provider>
  );
}

/** The data layer of one frame: a new, empty client whenever the scope changes. */
export function DataLayer({ scope, children }: { scope: string; children: ReactNode }) {
  return (
    <ScopedClient key={scope} scope={scope}>
      {children}
    </ScopedClient>
  );
}

/** The frame's data scope; every query key starts with it. */
export function useDataScope(): string {
  const scope = useContext(ScopeContext);
  if (scope === null) throw new Error('A query is used outside a DataLayer.');
  return scope;
}

/** What a read is asked with: its session, and the values that narrow it. */
type ReadOptions = { enabled?: boolean; refetchInterval?: number | false; keepPrevious?: boolean };

/**
 * A module's typed queries and changes. Each module declares its actions once (`ReadMap`, `ChangeMap`) and makes its
 * hooks with this, so an action it does not have, or a wrong answer type, does not compile, and each key names the
 * scope, the session's kind, the module, the action and the query: the question an answer belongs to. An answer to a
 * question that is no longer asked lands under its own key and is never shown for another; its request is aborted.
 */
export function moduleData<Reads extends ReadMap, Changes extends ChangeMap>(module: string) {
  const keyOf = (scope: string, session: Session, action?: string, query?: Query): QueryKey =>
    [scope, session.kind, module, action, query].filter((part) => part !== undefined);

  function useRead<A extends keyof Reads & string>(
    session: Session,
    action: A,
    query?: Reads[A] extends { query: infer Q } ? Q : undefined,
    options: ReadOptions = {},
  ) {
    const scope = useDataScope();
    return useQuery<Reads[A]['answer'], RequestFailure>({
      queryKey: keyOf(scope, session, action, query as Query | undefined),
      queryFn: ({ signal }) => read<Reads[A]['answer']>(session, action, query as Query | undefined, signal),
      enabled: options.enabled ?? true,
      refetchInterval: options.refetchInterval ?? false,
      placeholderData: options.keepPrevious ? (previous) => previous : undefined,
    });
  }

  /**
   * The module's changes on one session, as one mutation: `run(action, body)` makes a change and answers what the
   * API answered, typed by its action. Each change is made once; the screen reads again what it changed.
   */
  function useChanges(session: Session) {
    const mutation = useMutation<unknown, RequestFailure, { action: string; body: unknown }>({
      mutationFn: ({ action, body }) => change(session, action, body),
    });
    return {
      run: <A extends keyof Changes & string>(action: A, body: Changes[A]['body']) =>
        mutation.mutateAsync({ action, body }) as Promise<Changes[A]['answer']>,
      pending: mutation.isPending,
    };
  }

  /** Puts a change's answer in place of a read's (a new popular setting, the categories in their new order). */
  function useSetAnswer(session: Session) {
    const client = useQueryClient();
    const scope = useDataScope();
    return <A extends keyof Reads & string>(action: A, answer: Reads[A]['answer']) =>
      client.setQueryData(keyOf(scope, session, action), answer);
  }

  /**
   * A question asked when something happens rather than when a screen opens (a sheet opened, a period chosen): it is
   * read through the same cache and keys, asked again each time, and answers or throws. The screen keeps only the
   * answer to the latest question it asked.
   */
  function useAsk(session: Session) {
    const client = useQueryClient();
    const scope = useDataScope();
    return <A extends keyof Reads & string>(
      action: A,
      ...[query]: Reads[A] extends { query: infer Q } ? [query: Q] : []
    ): Promise<Reads[A]['answer']> =>
      client.fetchQuery({
        queryKey: keyOf(scope, session, action, query as Query | undefined),
        queryFn: ({ signal }) => read<Reads[A]['answer']>(session, action, query as Query | undefined, signal),
        staleTime: 0,
      });
  }

  return { keyOf, useRead, useChanges, useSetAnswer, useAsk };
}

/** What a read's result says about its failure. */
type Failed = { error: RequestFailure | null; errorUpdatedAt: number };

/**
 * Tells the screen once each time one of its reads fails, with the failure, so that it says why in its own words (and
 * an ended session leaves). A read that fails again tells it again.
 */
export function useReadFailure(reads: Failed[], handle: (failure: RequestFailure) => void) {
  const handler = useRef(handle);
  useEffect(() => {
    handler.current = handle;
  });
  const failure = reads.find((each) => each.error)?.error ?? null;
  const at = Math.max(0, ...reads.map((each) => each.errorUpdatedAt));
  useEffect(() => {
    if (failure) handler.current(failure);
  }, [failure, at]);
}
