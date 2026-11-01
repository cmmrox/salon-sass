import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { components } from '@salon/api-contracts/platform';
import { renderWithMessages } from '@test/render';
import { HolidayYears, HolidayYearScreen } from './HolidayScreens';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

type Detail = components['schemas']['HolidayYearDetail'];
const detailOf = (overrides: Partial<Detail> = {}): Detail => ({
  year: 2027,
  state: 'changed',
  revision: 1,
  version: 4,
  publishedAt: '2026-10-01T04:30:00Z',
  days: [
    { date: '2027-01-14', name: 'Tamil Thai Pongal Day', kind: 'public', change: 'unchanged' },
    { date: '2027-01-22', name: 'Duruthu Full Moon Poya Day', kind: 'poya', change: 'changed' },
    { date: '2027-02-04', name: 'Independence Day', kind: 'public', change: 'added' },
  ],
  withdrawn: [{ date: '2027-03-01', name: 'Withdrawn Day', kind: 'bank' }],
  changes: { added: 1, changed: 1, withdrawn: 1 },
  ...overrides,
});

type Call = { url: string; body: unknown };
let calls: Call[];
let answers: Record<string, (body: unknown) => Response>;
beforeEach(() => {
  calls = [];
  push.mockReset();
  answers = {
    principal: () =>
      Response.json({ id: 'o1', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true }),
    'holiday-years': () =>
      Response.json({
        items: [{ year: 2027, state: 'changed', revision: 1, dayCount: 3, publishedAt: '2026-10-01T04:30:00Z' }],
      }),
    'holiday-year': () => Response.json(detailOf()),
  };
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const body = init?.body instanceof FormData ? init.body : init?.body ? JSON.parse(String(init.body)) : null;
    calls.push({ url, body });
    const action = url.split('/').pop()!.split('?')[0]!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))(body);
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('the console holiday calendar (F04-S03)', () => {
  it('lists the years with their state, and lets an Admin open a year', async () => {
    renderWithMessages(<HolidayYears canManage />);
    const row = await screen.findByTestId('console-holiday-year-row');
    expect(row).toHaveAttribute('href', '/console/holidays/2027');
    expect(row).toHaveTextContent('Changes not published');
    expect(row).toHaveTextContent('3 days');

    fireEvent.change(screen.getByTestId('console-holiday-start-year'), { target: { value: '2028' } });
    fireEvent.click(screen.getByTestId('console-holiday-open'));
    expect(push).toHaveBeenCalledWith('/console/holidays/2028');
  });

  it('shows Support the years and a year’s days with no control to change them', async () => {
    renderWithMessages(<HolidayYearScreen year={2027} canManage={false} />);
    expect(await screen.findByTestId('console-holiday-days')).toBeInTheDocument();
    expect(screen.queryByTestId('console-holiday-import')).not.toBeInTheDocument();
    expect(screen.queryByTestId('console-holiday-publish')).not.toBeInTheDocument();
    expect(screen.queryByTestId('console-holiday-day-remove')).not.toBeInTheDocument();
  });

  it('shows the days in a table, what a publication changes, and publishes after the confirmation', async () => {
    answers['holiday-publish'] = () =>
      Response.json(detailOf({ state: 'published', revision: 2, changes: { added: 0, changed: 0, withdrawn: 0 } }));
    renderWithMessages(<HolidayYearScreen year={2027} canManage />);
    const table = await screen.findByTestId('console-holiday-days');
    expect(within(table).getAllByTestId('console-holiday-day')).toHaveLength(3);
    expect(screen.getByTestId('console-holiday-counts')).toHaveTextContent(
      'Publishing adds 1 day, changes 1 day and withdraws 1 day.',
    );
    expect(screen.getByTestId('console-holiday-withdrawn')).toHaveTextContent('Withdrawn Day');

    fireEvent.click(screen.getByTestId('console-holiday-publish'));
    const dialog = await screen.findByTestId('console-holiday-publish-dialog');
    expect(dialog).toHaveTextContent('Publish the 2027 holidays?');
    expect(dialog).toHaveTextContent(
      'Every salon is asked to review them. Nothing changes for a salon until it chooses what it does on each day.',
    );
    fireEvent.click(within(dialog).getByTestId('console-holiday-publish-confirm'));

    expect(await screen.findByTestId('console-holiday-done')).toHaveTextContent('Published.');
    expect(calls.find((call) => call.url.endsWith('/holiday-publish'))!.body).toEqual({ year: '2027', version: 4 });
    expect(screen.getByTestId('console-holiday-publish')).toBeDisabled();
  });

  it('lists each bad line of a refused import, changing nothing', async () => {
    answers['holiday-import'] = () =>
      Response.json(
        {
          code: 'bad_request',
          errors: [
            { field: 'file', line: 2, detail: 'the date is outside 2027' },
            { field: 'file', line: 4, detail: 'the date 2027-01-14 is repeated' },
          ],
        },
        { status: 400 },
      );
    renderWithMessages(<HolidayYearScreen year={2027} canManage />);
    const input = await screen.findByTestId('console-holiday-file');
    fireEvent.change(input, { target: { files: [new File(['date,name,kind\n'], '2027.csv', { type: 'text/csv' })] } });

    const refused = await screen.findByTestId('console-holiday-import-refused');
    expect(
      within(refused)
        .getAllByTestId('console-holiday-bad-line')
        .map((line) => line.textContent),
    ).toEqual(['Line 2: the date is outside 2027', 'Line 4: the date 2027-01-14 is repeated']);
    const sent = calls.find((call) => call.url.endsWith('/holiday-import'))!.body as FormData;
    expect(sent.get('year')).toBe('2027');
    expect(sent.get('file')).toBeInstanceOf(File);
  });

  it('starts an empty year with import and add for an Admin', async () => {
    answers['holiday-year'] = () => Response.json({ code: 'not_found' }, { status: 404 });
    renderWithMessages(<HolidayYearScreen year={2028} canManage />);
    expect(await screen.findByTestId('console-holiday-year-empty')).toHaveTextContent('2028 has no days yet');
    await waitFor(() => expect(screen.getByTestId('console-holiday-choose-file')).toBeEnabled());
    expect(screen.getByTestId('console-holiday-add')).toBeEnabled();
  });
});
