import type { components } from '@salon/api-contracts/shared';

/**
 * The one request module for a screen's reads and changes after first render (decision 121, docs/engineering/web.md,
 * "Structure"). Every call goes to a BFF (the workspace's `/identity-web/{kind}` or the console's `/operator-web`);
 * a call refused for an expired access credential renews the session once, under the session's cross-tab lock, and is
 * asked again, so no screen sends a signed-in person to sign-in while the session can be renewed. Failures arrive as
 * one class with the API's stable code, and {@link reasonOf} sorts the codes for every screen in one place.
 */

/** The API's stable codes (the contract's `Problem.code`), and the browser's own: no Web Locks to renew under. */
export type FailureCode = components['schemas']['Problem']['code'] | 'browser_unsupported';

/** A BFF session: where its actions are answered, the Web Lock its renewals take, and who it belongs to. */
export type Session = { readonly path: string; readonly lock: string; readonly kind: string };

/** A salon's workspace session: its staff, or the operator's read-only support view (F03-S04). */
export function workspaceSession(kind: 'staff' | 'support'): Session {
  return { path: `/identity-web/${kind}`, lock: `salon:identity:${kind}`, kind };
}

/** The operator console's session, on the console's own host and cookies. */
export const consoleSession: Session = { path: '/operator-web', lock: 'salon:identity:operator', kind: 'operator' };

/** What a failure carries beside its code. */
type FailureDetail = {
  /** The fields a `bad_request` named. */
  fields?: string[];
  /** Seconds to wait, from `Retry-After`. */
  retryAfter?: number;
  /** For `invalid_session`: whether the refresh credential may renew the access one. */
  renewable?: boolean;
  /** The API's `errors` items whole, for a refusal whose detail the screen says (F06-S01: `in_use`). */
  problems?: { field: string; detail?: string }[];
  /** The HTTP status, 0 when nothing was answered. */
  status?: number;
  /** For a file read line by line (a holiday calendar CSV, F04-S03): each bad line and why. */
  lines?: { line: number; detail: string }[];
};

/** A refused or failed call: its stable code and what the answer said beside it. */
export class RequestFailure extends Error {
  readonly fields: string[];
  readonly retryAfter: number;
  readonly renewable: boolean;
  readonly problems: { field: string; detail?: string }[];
  readonly status: number;
  readonly lines: { line: number; detail: string }[];
  constructor(
    public readonly code: FailureCode,
    detail: FailureDetail = {},
  ) {
    super(code);
    this.fields = detail.fields ?? [];
    this.retryAfter = detail.retryAfter ?? 0;
    this.renewable = detail.renewable ?? false;
    this.problems = detail.problems ?? [];
    this.status = detail.status ?? 0;
    this.lines = detail.lines ?? [];
  }
}

/** Any thrown value as a failure: what is not one is an outcome nobody can know, `service_unavailable`. */
export const failureOf = (error: unknown): RequestFailure =>
  error instanceof RequestFailure ? error : new RequestFailure('service_unavailable');

/**
 * What a screen does about a failure, decided once for every screen:
 *
 * - `ended`: the session is over and cannot be renewed; the person signs in again.
 * - `conflict`: someone changed it first; read it again.
 * - `gone`: it no longer exists.
 * - `unavailable`: the API could not be reached, or the outcome is unknown; what is on screen stays.
 * - `invalid`: a field was refused; say so next to the field.
 * - `refused`: this person or salon may not do it (a permission, a read-only salon, a support view).
 * - `rule`: a business rule refused it; the screen says which in its own words.
 */
export type FailureReason = 'ended' | 'conflict' | 'gone' | 'unavailable' | 'invalid' | 'refused' | 'rule';

const REFUSALS = new Set<FailureCode>([
  'permission_denied',
  'salon_read_only',
  'support_read_only',
  'request_rejected',
  'salon_suspended',
  'method_not_allowed',
]);

export function reasonOf(failure: RequestFailure): FailureReason {
  switch (failure.code) {
    case 'invalid_session':
      return 'ended';
    case 'conflict':
      return 'conflict';
    case 'not_found':
      return 'gone';
    case 'service_unavailable':
    case 'browser_unsupported':
    case 'internal_error':
      return 'unavailable';
    case 'bad_request':
      return 'invalid';
    default:
      return REFUSALS.has(failure.code) ? 'refused' : 'rule';
  }
}

/** Whether the outcome of a change is unknown: it may have been made although no answer said so (BUG-F07-006). */
export const outcomeUnknown = (failure: RequestFailure) => reasonOf(failure) === 'unavailable';

export const supportsLocks = () => typeof navigator !== 'undefined' && Boolean(navigator.locks);

/** A read's query: each value as it is sent, an absent one left out. */
export type Query = Record<string, string | number | boolean | null | undefined>;

function addressOf(session: Session, action: string, query?: Query): string {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(query ?? {}))
    if (value !== undefined && value !== null && value !== '') params.set(name, String(value));
  const search = params.toString();
  return `${session.path}/${action}${search ? `?${search}` : ''}`;
}

/**
 * One call to the BFF, without renewal: a read when there is no body, a change otherwise. A form (an upload) goes as
 * multipart with the browser's own boundary; anything else as JSON. A lost connection is `service_unavailable`.
 */
export async function send<T>(
  session: Session,
  action: string,
  options: { body?: unknown; query?: Query; signal?: AbortSignal; keepalive?: boolean } = {},
): Promise<T> {
  const { body, query, signal, keepalive } = options;
  let response: Response;
  try {
    const form = body instanceof FormData;
    response = await fetch(addressOf(session, action, query), {
      method: body === undefined ? 'GET' : 'POST',
      headers: body === undefined || form ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : form ? body : JSON.stringify(body),
      cache: 'no-store',
      ...(signal ? { signal } : {}),
      ...(keepalive ? { keepalive } : {}),
    });
  } catch (error) {
    // A call its caller gave up on stays given up: nobody is waiting for its answer.
    if (signal?.aborted) throw error;
    throw new RequestFailure('service_unavailable');
  }
  const data = await response.json().catch(() => ({ code: 'service_unavailable' }));
  if (!response.ok) throw failureFrom(data, response.status);
  return data as T;
}

type Problem = {
  code?: FailureCode;
  errors?: { field: string; detail?: string; line?: unknown }[];
  retryAfter?: number;
  renewable?: boolean;
};

function failureFrom(data: Problem, status: number): RequestFailure {
  const errors = Array.isArray(data.errors) ? data.errors : [];
  return new RequestFailure(data.code ?? 'service_unavailable', {
    fields: errors.map((each) => each.field),
    retryAfter: data.retryAfter ?? 0,
    renewable: data.renewable ?? false,
    problems: errors,
    status,
    lines: errors
      .filter((each): each is { field: string; detail?: string; line: number } => typeof each.line === 'number')
      .map((each) => ({ line: each.line, detail: each.detail ?? '' })),
  });
}

/**
 * Runs `work` holding the session's cross-tab Web Lock, then tells the other tabs that the session changed. Every
 * renewal, sign-in and sign-out runs under it, so two tabs never rotate one refresh credential twice. Without Web
 * Locks it is refused, `browser_unsupported`.
 */
export async function withSessionLock<T>(session: Session, work: () => Promise<T>): Promise<T> {
  if (!supportsLocks()) throw new RequestFailure('browser_unsupported');
  return navigator.locks.request(session.lock, async () => {
    const result = await work();
    if (typeof BroadcastChannel !== 'undefined') {
      const channel = new BroadcastChannel('salon:identity');
      channel.postMessage({ kind: session.kind, event: 'changed' });
      channel.close();
    }
    return result;
  });
}

/**
 * Called only while holding the session's lock: the principal, renewing the access credential once when it has
 * expired and the refresh credential may renew it. A spent family is cleared and never renewed again, and an unknown
 * renewal outcome is never retried (the server may have rotated before the connection was lost): fresh sign-in is the
 * recovery then. An ordinary outage of the principal stays an outage.
 */
export async function principalInsideLock<P>(session: Session): Promise<P> {
  try {
    return await send<P>(session, 'principal');
  } catch (current) {
    if (!(current instanceof RequestFailure) || current.code !== 'invalid_session') throw current;
    if (!current.renewable) {
      await send(session, 'clear', { body: {} }).catch(() => undefined);
      throw current;
    }
    try {
      await send(session, 'refresh', { body: {} });
    } catch {
      await send(session, 'clear', { body: {} }).catch(() => undefined);
      throw new RequestFailure('invalid_session');
    }
    return send<P>(session, 'principal');
  }
}

/** The current principal: read first, and renewed under the cross-tab lock only when its access has expired. */
export async function ensurePrincipal<P>(session: Session): Promise<P> {
  try {
    return await send<P>(session, 'principal');
  } catch (failure) {
    if (!(failure instanceof RequestFailure) || failure.code !== 'invalid_session') throw failure;
    return withSessionLock(session, () => principalInsideLock<P>(session));
  }
}

/**
 * A call that renews once. It is asked again only after the one answer that says nothing was done: 401
 * `invalid_session` with `renewable`, the access credential refused before the API acted (the BFF and the API
 * authenticate before anything else). The session is renewed under its cross-tab lock first; another tab may have
 * done it already, which the principal read inside the lock finds, and then nothing is rotated again. The call is
 * asked again once, with the same body (and so the same `Idempotency-Key` where the action has one). A lost or
 * timed-out answer is never asked again here: its outcome is unknown, and the caller's idempotency rules apply
 * (BUG-F07-006). A session that cannot be renewed is cleared and its refusal stands.
 */
async function renewingOnce<T>(session: Session, call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch (error) {
    if (!(error instanceof RequestFailure) || error.code !== 'invalid_session') throw error;
    if (!error.renewable) {
      await send(session, 'clear', { body: {} }).catch(() => undefined);
      throw error;
    }
    await ensurePrincipal(session);
    return call();
  }
}

/** A read after first render, its query given as values; `signal` ends it when its question is no longer asked. */
export function read<T>(session: Session, action: string, query?: Query, signal?: AbortSignal): Promise<T> {
  return renewingOnce(session, () => send<T>(session, action, { query, signal }));
}

/** A change: the body as JSON, or a form for an upload. */
export function change<T>(session: Session, action: string, body: unknown): Promise<T> {
  return renewingOnce(session, () => send<T>(session, action, { body }));
}

/**
 * A module's BFF actions, typed: each read names its query and answer, each change its body and answer. A module
 * declares its map once beside its types, and its hooks call {@link actionsOf}'s functions, so an action that does not
 * exist, or a wrong answer type, does not compile.
 */
export type ReadMap = Record<string, { query?: Query; answer: unknown }>;
export type ChangeMap = Record<string, { body: unknown; answer: unknown }>;

type QueryArg<R> = R extends { query: infer Q }
  ? [query: Q, signal?: AbortSignal]
  : [query?: undefined, signal?: AbortSignal];

export type Actions<Reads extends ReadMap, Changes extends ChangeMap> = {
  read<A extends keyof Reads & string>(action: A, ...rest: QueryArg<Reads[A]>): Promise<Reads[A]['answer']>;
  change<A extends keyof Changes & string>(action: A, body: Changes[A]['body']): Promise<Changes[A]['answer']>;
};

/** The typed reads and changes of one module's actions, on one session. */
export function actionsOf<Reads extends ReadMap, Changes extends ChangeMap>(session: Session): Actions<Reads, Changes> {
  return {
    read: (action, ...[query, signal]) => read(session, action, query as Query | undefined, signal),
    change: (action, body) => change(session, action, body),
  };
}
