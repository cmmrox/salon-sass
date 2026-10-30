import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { components } from '@salon/api-contracts/platform';
import { expectListOfItems } from '@test/list';
import { renderWithMessages } from '@test/render';
import { ExportCard, LifecycleCard } from './LifecycleScreens';

const replace = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace, refresh: vi.fn() }) }));

const SALON: components['schemas']['SalonDetail'] = { id: '22222222-0000-4000-8000-000000000002', version: 3, name: 'Synthetic Salon', slug: 'synthetic', hostname: 'synthetic.localhost', status: 'active', country: 'LK', currency: 'LKR', timeZone: 'Asia/Colombo', defaultLanguage: 'en', contactEmail: null, contactPhone: null, address: null, plan: { id: 'p1', key: 'basic', name: 'Basic' }, ownerEmail: 'owner@example.test', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', closedAt: null, deleteAt: null, deletedAt: null };
const CLOSED: components['schemas']['SalonDetail'] = { ...SALON, status: 'closed', closedAt: '2026-01-02T00:00:00Z', deleteAt: '2026-02-01T00:00:00Z' };
const EVENT = { id: 'e1', kind: 'suspended', reason: 'Repeated chargeback disputes', actor: { kind: 'operator', id: 'op', displayName: 'Synthetic Admin' }, at: '2026-01-01T10:00:00Z' };
type Call = { url: string; body: Record<string, unknown> | null };
let calls: Call[];

function stubApi(answer: (url: string, body: Record<string, unknown> | null) => Response | Promise<Response>) {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const body = init?.body ? JSON.parse(String(init.body)) : null;
    calls.push({ url, body });
    return answer(url, body);
  });
}
const history = (items: unknown[] = [EVENT]) => Response.json({ items, page: 0, size: 10, total: items.length });

beforeEach(() => {
  calls = [];
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
});
afterEach(() => { vi.unstubAllGlobals(); replace.mockReset(); });

describe('the lifecycle card', () => {
  it('shows the state as a word, the actions it allows and the history with reason, actor and time', async () => {
    stubApi(() => history());
    renderWithMessages(<LifecycleCard salon={SALON} onChanged={() => undefined} />);
    expect(screen.getByTestId('console-lifecycle-status')).toHaveTextContent('Active');
    expect(screen.getByTestId('console-lifecycle-status')).toHaveAttribute('data-slot', 'badge');
    expect(screen.getByTestId('console-suspend')).toBeInTheDocument();
    expect(screen.queryByTestId('console-reactivate')).not.toBeInTheDocument();
    expect(screen.getByTestId('console-close')).toBeInTheDocument();
    const row = await screen.findByTestId('console-lifecycle-row');
    expect(row).toHaveAttribute('data-slot', 'item');
    expect(row).toHaveTextContent('Suspended');
    expect(row).toHaveTextContent('Synthetic Admin');
    expect(within(row).getByTestId('console-lifecycle-reason')).toHaveTextContent('Repeated chargeback disputes');
    expectListOfItems(screen.getByTestId('console-lifecycle-list'), screen.getAllByTestId('console-lifecycle-row').length);
  });

  it('suspends with a reason and the version, and names a refused reason', async () => {
    const changed = vi.fn();
    stubApi((url, body) => url === '/operator-web/salon-suspend'
      ? (String(body?.reason).length < 10 ? Response.json({ code: 'bad_request', errors: [{ field: 'reason' }] }, { status: 400 }) : Response.json({ ...SALON, status: 'suspended' }))
      : url === '/operator-web/principal' ? Response.json({ id: 'op', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true }) : history([]));
    renderWithMessages(<LifecycleCard salon={SALON} onChanged={changed} />);
    expect(await screen.findByTestId('console-lifecycle-empty')).toHaveAttribute('data-slot', 'empty');
    fireEvent.click(screen.getByTestId('console-suspend'));
    const sheet = await screen.findByTestId('console-suspend-sheet');
    expect(sheet).toHaveAttribute('data-slot', 'sheet-content');
    expect(within(sheet).getByTestId('console-suspend-reason')).toHaveAttribute('data-slot', 'textarea');
    fireEvent.change(within(sheet).getByTestId('console-suspend-reason'), { target: { value: 'short' } });
    fireEvent.submit(within(sheet).getByTestId('console-suspend-submit').closest('form')!);
    expect(await within(sheet).findByTestId('console-suspend-reason-error')).toBeInTheDocument();
    fireEvent.change(within(sheet).getByTestId('console-suspend-reason'), { target: { value: 'Repeated chargeback disputes' } });
    fireEvent.submit(within(sheet).getByTestId('console-suspend-submit').closest('form')!);
    expect(await screen.findByTestId('console-lifecycle-notice')).toHaveTextContent('suspended');
    expect(changed).toHaveBeenCalled();
    expect(calls.filter((call) => call.url === '/operator-web/salon-suspend').at(-1)!.body).toEqual({ id: SALON.id, version: 3, reason: 'Repeated chargeback disputes' });
  });

  it('closure states that it is irreversible and its consequences, and needs the exact slug', async () => {
    stubApi((url) => url === '/operator-web/salon-close' ? Response.json({ code: 'bad_request', errors: [{ field: 'confirmSlug' }] }, { status: 400 })
      : url === '/operator-web/principal' ? Response.json({ id: 'op', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true }) : history([]));
    renderWithMessages(<LifecycleCard salon={SALON} onChanged={() => undefined} />);
    fireEvent.click(screen.getByTestId('console-close'));
    const sheet = await screen.findByTestId('console-close-sheet');
    const consequences = within(sheet).getByTestId('console-close-consequences');
    expect(consequences).toHaveAttribute('data-slot', 'alert');
    expect(consequences).toHaveTextContent('This cannot be undone');
    expect(consequences).toHaveTextContent('synthetic.localhost');
    expect(within(sheet).getByTestId('console-close-deletion-date')).toHaveTextContent(/30 days after closure, on .*\d{4}/);
    expect(within(sheet).getByTestId('console-confirm-slug')).toHaveAttribute('data-slot', 'input');
    fireEvent.change(within(sheet).getByTestId('console-confirm-slug'), { target: { value: 'SYNTHETIC' } });
    fireEvent.change(within(sheet).getByTestId('console-close-reason'), { target: { value: 'The owner asked us to close' } });
    fireEvent.submit(within(sheet).getByTestId('console-close-submit').closest('form')!);
    expect(await within(sheet).findByTestId('console-confirm-slug-error')).toBeInTheDocument();
    expect(calls.filter((call) => call.url === '/operator-web/salon-close').at(-1)!.body).toEqual({ id: SALON.id, version: 3, confirmSlug: 'SYNTHETIC', reason: 'The owner asked us to close' });
  });

  it('a closed salon offers no action and says when its data is deleted', async () => {
    stubApi(() => history([]));
    renderWithMessages(<LifecycleCard salon={CLOSED} onChanged={() => undefined} />);
    expect(screen.getByTestId('console-lifecycle-status')).toHaveTextContent('Closed');
    expect(screen.getByTestId('console-lifecycle-closed')).toHaveTextContent('cannot be reopened');
    expect(screen.queryByTestId('console-suspend')).not.toBeInTheDocument();
    expect(screen.queryByTestId('console-close')).not.toBeInTheDocument();
  });

  it('shows a conflict as a reloadable refusal', async () => {
    stubApi((url) => url === '/operator-web/salon-suspend' ? Response.json({ code: 'conflict' }, { status: 409 })
      : url === '/operator-web/principal' ? Response.json({ id: 'op', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true }) : history([]));
    renderWithMessages(<LifecycleCard salon={SALON} onChanged={() => undefined} />);
    fireEvent.click(screen.getByTestId('console-suspend'));
    const sheet = await screen.findByTestId('console-suspend-sheet');
    fireEvent.change(within(sheet).getByTestId('console-suspend-reason'), { target: { value: 'Repeated chargeback disputes' } });
    fireEvent.submit(within(sheet).getByTestId('console-suspend-submit').closest('form')!);
    expect(await within(sheet).findByTestId('console-error')).toHaveAttribute('data-code', 'conflict');
  });
});

describe('the export card', () => {
  it('shows a ready export with its size, checksum and deletion date, and downloads it', async () => {
    const created: Blob[] = [];
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: (blob: Blob) => { created.push(blob); return 'blob:x'; }, revokeObjectURL: () => undefined }));
    stubApi((url) => url.startsWith('/operator-web/export?') ? Response.json({ status: 'ready', createdAt: '2026-01-02T00:00:00Z', readyAt: '2026-01-02T00:01:00Z', deleteAt: '2026-02-01T00:00:00Z', sizeBytes: 12345, sha256: 'ab'.repeat(32) })
      : url.startsWith('/operator-web/export-archive') ? new Response(new Uint8Array([80, 75, 3, 4]), { headers: { 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="salon-export-synthetic.zip"' } })
        : Response.json({ id: 'op', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true }));
    renderWithMessages(<ExportCard salon={CLOSED} />);
    expect(await screen.findByTestId('console-export-status')).toHaveTextContent('Ready');
    expect(screen.getByTestId('console-export-size')).toHaveTextContent('12.3 kB');
    expect(screen.getByTestId('console-export-sha256')).toHaveTextContent('ab'.repeat(32));
    expect(screen.getByTestId('console-export-delete-at')).toHaveTextContent('2026');
    fireEvent.click(screen.getByTestId('console-export-download'));
    expect(await screen.findByTestId('console-export-downloaded')).toBeInTheDocument();
    expect(created).toHaveLength(1);
  });

  it('shows a pending export with a refresh, and an unavailable service with a retry', async () => {
    stubApi(() => Response.json({ status: 'pending', createdAt: '2026-01-02T00:00:00Z', readyAt: null, deleteAt: '2026-02-01T00:00:00Z', sizeBytes: null, sha256: null }));
    const { unmount } = renderWithMessages(<ExportCard salon={CLOSED} />);
    expect(await screen.findByTestId('console-export-pending')).toBeInTheDocument();
    expect(screen.getByTestId('console-export-refresh')).toBeInTheDocument();
    expect(screen.queryByTestId('console-export-download')).not.toBeInTheDocument();
    unmount();
    stubApi(() => Response.json({ code: 'service_unavailable' }, { status: 503 }));
    renderWithMessages(<ExportCard salon={CLOSED} />);
    expect(await screen.findByTestId('console-error')).toHaveAttribute('data-code', 'service_unavailable');
    await waitFor(() => expect(screen.getByTestId('console-retry')).toBeEnabled());
  });
});
