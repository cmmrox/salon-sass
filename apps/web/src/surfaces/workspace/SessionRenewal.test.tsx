import { render, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionRenewal } from './SessionRenewal';

const router = { push: vi.fn(), replace: vi.fn(), refresh: vi.fn() };
vi.mock('next/navigation', () => ({ useRouter: () => router }));

let calls: string[];
let answers: Record<string, () => Response>;
beforeEach(() => {
  router.refresh.mockReset();
  router.replace.mockReset();
  calls = [];
  answers = {};
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!.split('?')[0]!;
    calls.push(action);
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});
const sent = (action: string) => calls.filter((each) => each === action);

// Moved from BookingsScreen.test.tsx with the effect it tests (F07-S03 AC-10), which every page's frame now has
// (BUG-F05-002, BUG-F06-001).
describe('who sees what (F07-S03 AC-10)', () => {
  it('rendered from an expired access credential, renews in the browser and renders again with the caller’s permissions', async () => {
    const refresh = router.refresh;
    answers.principal = () => Response.json({ kind: 'staff', permissions: ['calendar.read', 'booking.manage'] });
    render(<SessionRenewal kind="staff" />);
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
    expect(sent('principal')).toHaveLength(1);
  });
});

describe('a page rendered without a principal (BUG-F05-002, BUG-F06-001)', () => {
  it('renews an expired access credential once, then renders the page again', async () => {
    let renewed = false;
    answers.principal = () =>
      renewed
        ? Response.json({ kind: 'staff', permissions: ['team.read', 'team.manage'] })
        : Response.json({ code: 'invalid_session', renewable: true }, { status: 401 });
    answers.refresh = () => {
      renewed = true;
      return Response.json({ ok: true });
    };
    render(<SessionRenewal kind="staff" />);
    await waitFor(() => expect(router.refresh).toHaveBeenCalledTimes(1));
    expect(sent('refresh')).toHaveLength(1);
    expect(router.replace).not.toHaveBeenCalled();
  });

  it('sends a session that cannot be renewed to sign-in', async () => {
    answers.principal = () => Response.json({ code: 'invalid_session', renewable: false }, { status: 401 });
    answers.clear = () => Response.json({ ok: true });
    render(<SessionRenewal kind="staff" />);
    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/workspace/sign-in'));
    expect(sent('refresh')).toHaveLength(0);
    expect(router.refresh).toHaveBeenCalledTimes(1);
  });
});
