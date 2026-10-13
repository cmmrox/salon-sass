import { afterEach, describe, expect, it, vi } from 'vitest';
import { ensurePrincipal, withIdentityLock } from './browser';
afterEach(() => { vi.unstubAllGlobals(); });
describe('cross-tab renewal', () => {
  it('rechecks live principal inside the lock so two tabs only renew once', async () => {
    let queue = Promise.resolve(); let renewed = false; let rotations = 0;
    vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => { const result = queue.then(work); queue = result.then(() => undefined, () => undefined); return result; } } });
    vi.stubGlobal('BroadcastChannel', undefined);
    vi.stubGlobal('fetch', async (url: string) => {
      if (url.endsWith('/refresh')) { rotations++; renewed = true; return Response.json({ ok: true }); }
      return renewed ? Response.json({ kind: 'staff', permissions: [] }) : Response.json({ code: 'invalid_session', renewable: true }, { status: 401 });
    });
    const result = await Promise.all([ensurePrincipal('staff'), ensurePrincipal('staff')]);
    expect(rotations).toBe(1); expect(result.every((p) => p.kind === 'staff')).toBe(true);
  });
  it('never retries an unknown refresh outcome', async () => {
    let rotations = 0;
    vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
    vi.stubGlobal('fetch', async (url: string) => { if (url.endsWith('/refresh')) { rotations++; throw new Error('connection closed'); } return Response.json({ code: 'invalid_session', renewable: true }, { status: 401 }); });
    await expect(ensurePrincipal('customer')).rejects.toMatchObject({ code: 'service_unavailable' }); expect(rotations).toBe(1);
  });
  it('rejects credential mutation without cross-tab locks', async () => { vi.stubGlobal('navigator', {}); const work = vi.fn(); await expect(withIdentityLock('staff', work)).rejects.toMatchObject({ code: 'browser_unsupported' }); expect(work).not.toHaveBeenCalled(); });
});
