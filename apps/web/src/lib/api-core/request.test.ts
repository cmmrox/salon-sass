import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  actionsOf,
  change,
  consoleSession,
  read,
  reasonOf,
  RequestFailure,
  workspaceSession,
  type FailureCode,
} from './request';

let calls: { url: string; method: string; body: unknown }[];
let answers: Record<string, () => Response | Promise<Response>>;
const expired = () => Response.json({ code: 'invalid_session', renewable: true }, { status: 401 });
beforeEach(() => {
  calls = [];
  answers = {};
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    calls.push({ url, method: init?.method ?? 'GET', body: init?.body ? JSON.parse(String(init.body)) : undefined });
    if (init?.signal?.aborted) throw new DOMException('aborted', 'AbortError');
    const action = url.split('?')[0]!.split('/').pop()!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});
const asked = () => calls.map((call) => call.url.split('?')[0]!.split('/').pop());

/** An access credential that lapsed: refused until `refresh` renews it. */
function expiredUntilRenewed(action: string, answer: () => Response) {
  let renewed = false;
  answers[action] = () => (renewed ? answer() : expired());
  answers.principal = () => (renewed ? Response.json({ kind: 'staff', permissions: [] }) : expired());
  answers.refresh = () => {
    renewed = true;
    return Response.json({ ok: true });
  };
}

describe('renewal (web H1, decision 121)', () => {
  it('renews an expired access credential once and asks the read again', async () => {
    expiredUntilRenewed('team-members', () => Response.json({ items: ['Nimali'] }));
    expect(await read(workspaceSession('staff'), 'team-members')).toEqual({ items: ['Nimali'] });
    expect(asked()).toEqual(['team-members', 'principal', 'principal', 'refresh', 'principal', 'team-members']);
  });

  it('renews once and makes a change again, with the same body', async () => {
    expiredUntilRenewed('team-member-save', () => Response.json({ id: 'a', version: 2 }));
    expect(await change(workspaceSession('staff'), 'team-member-save', { id: 'a', version: 1 })).toEqual({
      id: 'a',
      version: 2,
    });
    const saves = calls.filter((call) => call.url.endsWith('/team-member-save'));
    expect(saves.map((call) => call.body)).toEqual([
      { id: 'a', version: 1 },
      { id: 'a', version: 1 },
    ]);
  });

  it('does not renew twice: a read refused again after renewal is the refusal', async () => {
    answers['team-members'] = expired;
    let renewed = false;
    answers.principal = () => (renewed ? Response.json({ kind: 'staff', permissions: [] }) : expired());
    answers.refresh = () => {
      renewed = true;
      return Response.json({ ok: true });
    };
    await expect(read(workspaceSession('staff'), 'team-members')).rejects.toMatchObject({ code: 'invalid_session' });
    expect(asked().filter((action) => action === 'refresh')).toHaveLength(1);
    expect(asked().filter((action) => action === 'team-members')).toHaveLength(2);
  });

  it('clears a session that cannot be renewed, keeps its refusal and asks nothing again', async () => {
    answers['team-members'] = () => Response.json({ code: 'invalid_session', renewable: false }, { status: 401 });
    answers.clear = () => Response.json({ ok: true });
    await expect(read(workspaceSession('staff'), 'team-members')).rejects.toMatchObject({
      code: 'invalid_session',
      renewable: false,
    });
    expect(asked()).toEqual(['team-members', 'clear']);
  });

  it('never asks a change again after a lost or timed-out answer, whose outcome is unknown (lead, condition 1)', async () => {
    answers['booking-place'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    await expect(change(workspaceSession('staff'), 'booking-place', { idempotencyKey: 'k-1' })).rejects.toMatchObject({
      code: 'service_unavailable',
    });
    vi.stubGlobal('fetch', async (url: string) => {
      calls.push({ url, method: 'POST', body: undefined });
      throw new TypeError('connection reset');
    });
    await expect(change(workspaceSession('staff'), 'booking-place', { idempotencyKey: 'k-1' })).rejects.toMatchObject({
      code: 'service_unavailable',
    });
    expect(asked()).toEqual(['booking-place', 'booking-place']);
  });

  it('asks a refused change again once, with the same body and Idempotency-Key (lead, condition 2)', async () => {
    expiredUntilRenewed('booking-place', () => Response.json({ id: 'hold' }, { status: 201 }));
    const body = { idempotencyKey: 'k-7', memberId: 'm', starts: '2026-11-12T10:00:00+05:30' };
    await change(workspaceSession('staff'), 'booking-place', body);
    const places = calls.filter((call) => call.url.endsWith('/booking-place'));
    expect(places).toHaveLength(2);
    expect(places.map((call) => call.body)).toEqual([body, body]);
  });

  it('renews under the cross-tab lock, and a second tab that finds it renewed rotates nothing (lead, condition 3)', async () => {
    let queue = Promise.resolve();
    const held: string[] = [];
    vi.stubGlobal('navigator', {
      locks: {
        request: (name: string, work: () => Promise<unknown>) => {
          const result = queue.then(() => {
            held.push(name);
            return work();
          });
          queue = result.then(
            () => undefined,
            () => undefined,
          );
          return result;
        },
      },
    });
    expiredUntilRenewed('team-member-save', () => Response.json({ ok: true }));
    await Promise.all([
      change(workspaceSession('staff'), 'team-member-save', { id: 'a', version: 1 }),
      change(workspaceSession('staff'), 'team-member-save', { id: 'b', version: 4 }),
    ]);
    expect(asked().filter((action) => action === 'refresh')).toHaveLength(1);
    expect(held).toEqual(['salon:identity:staff', 'salon:identity:staff']);
    expect(calls.filter((call) => call.url.endsWith('/team-member-save'))).toHaveLength(4);
  });

  it('does not renew for any other refusal', async () => {
    answers['team-members'] = () => Response.json({ code: 'permission_denied' }, { status: 403 });
    await expect(read(workspaceSession('staff'), 'team-members')).rejects.toMatchObject({
      code: 'permission_denied',
      status: 403,
    });
    expect(asked()).toEqual(['team-members']);
  });

  it('renews the console the same way, on its own address', async () => {
    expiredUntilRenewed('salons', () => Response.json({ items: [] }));
    await read(consoleSession, 'salons');
    expect(calls.every((call) => call.url.startsWith('/operator-web/'))).toBe(true);
  });
});

describe('the address and the answer', () => {
  it('sends a query as values, leaving an absent one out', async () => {
    answers.bookings = () => Response.json({ items: [] });
    await read(workspaceSession('staff'), 'bookings', { date: '2026-11-12', memberId: undefined, page: 0 });
    expect(calls[0]!.url).toBe('/identity-web/staff/bookings?date=2026-11-12&page=0');
  });

  it('makes a lost connection service_unavailable, and a given-up read stays given up', async () => {
    vi.stubGlobal('fetch', async () => {
      throw new TypeError('network');
    });
    await expect(read(workspaceSession('staff'), 'bookings')).rejects.toMatchObject({ code: 'service_unavailable' });
    const controller = new AbortController();
    controller.abort();
    vi.stubGlobal('fetch', async () => {
      throw new DOMException('aborted', 'AbortError');
    });
    await expect(read(workspaceSession('staff'), 'bookings', {}, controller.signal)).rejects.toMatchObject({
      name: 'AbortError',
    });
  });

  it('carries the fields, problems, wait and lines a refusal names', async () => {
    answers.import = () =>
      Response.json(
        {
          code: 'bad_request',
          retryAfter: 3,
          errors: [
            { field: 'file', detail: 'bad date', line: 4 },
            { field: 'name', detail: 'is required' },
          ],
        },
        { status: 400 },
      );
    const failure = await change(consoleSession, 'import', {}).catch((error: RequestFailure) => error);
    expect(failure).toBeInstanceOf(RequestFailure);
    expect(failure).toMatchObject({
      fields: ['file', 'name'],
      retryAfter: 3,
      status: 400,
      lines: [{ line: 4, detail: 'bad date' }],
    });
  });
});

describe('one mapping of codes to what a screen does', () => {
  it.each<[FailureCode, ReturnType<typeof reasonOf>]>([
    ['invalid_session', 'ended'],
    ['conflict', 'conflict'],
    ['not_found', 'gone'],
    ['service_unavailable', 'unavailable'],
    ['browser_unsupported', 'unavailable'],
    ['bad_request', 'invalid'],
    ['permission_denied', 'refused'],
    ['salon_read_only', 'refused'],
    ['support_read_only', 'refused'],
    ['salon_suspended', 'suspended'],
    ['limit_reached', 'rule'],
    ['in_use', 'rule'],
    ['slot_taken', 'rule'],
  ])('%s is %s', (code, reason) => {
    expect(reasonOf(new RequestFailure(code))).toBe(reason);
  });
});

describe('typed actions', () => {
  type Reads = { 'team-schedule': { query: { memberId: string }; answer: { weekly: string[] } } };
  type Changes = { 'team-leave-add': { body: { memberId: string }; answer: { id: string } } };

  it('names each action, its query and its answer', async () => {
    answers['team-schedule'] = () => Response.json({ weekly: ['monday'] });
    const team = actionsOf<Reads, Changes>(workspaceSession('staff'));
    const schedule: { weekly: string[] } = await team.read('team-schedule', { memberId: 'n 1' });
    expect(schedule.weekly).toEqual(['monday']);
    expect(calls[0]!.url).toBe('/identity-web/staff/team-schedule?memberId=n+1');
    // @ts-expect-error an action the module does not declare does not compile
    await team.read('team-members').catch(() => undefined);
  });
});
