import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HolidayYears, HolidayYearScreen } from '@/features/platform';
import { atWidth, axe, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { ConsoleShell } from './ConsoleShell';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/console/holidays',
}));
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F04-S03 AC-12: the console's Holiday calendar pages, in the console frame and its neutral theme, have no WCAG
 * 2.2 AA violation at every width: the years, a year's table, the refused import with its line list, and the
 * publication confirmation.
 */
const PERMISSIONS = ['operator.session.manage', 'operator.holiday.read', 'operator.holiday.manage'];
const DETAIL = {
  year: 2027,
  state: 'changed',
  revision: 1,
  version: 4,
  publishedAt: '2026-10-01T04:30:00Z',
  days: [
    { date: '2027-01-14', name: 'Tamil Thai Pongal Day', kind: 'public', change: 'unchanged' },
    { date: '2027-01-22', name: 'Duruthu Full Moon Poya Day', kind: 'poya', change: 'changed' },
    { date: '2027-02-04', name: 'Independence Day', kind: 'public', change: 'added' },
    { date: '2027-04-13', name: 'Day prior to Sinhala and Tamil New Year Day', kind: 'mercantile', change: 'added' },
  ],
  withdrawn: [{ date: '2027-03-01', name: 'Withdrawn Day', kind: 'bank' }],
  changes: { added: 2, changed: 1, withdrawn: 1 },
};
let answers: Record<string, () => Response>;
beforeEach(() => {
  answers = {
    principal: () =>
      Response.json({ id: 'o1', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: PERMISSIONS, mfaEnabled: true }),
    'holiday-years': () =>
      Response.json({
        items: [
          { year: 2027, state: 'changed', revision: 1, dayCount: 3, publishedAt: '2026-10-01T04:30:00Z' },
          { year: 2028, state: 'draft', revision: 0, dayCount: 0, publishedAt: null },
        ],
      }),
    'holiday-year': () => Response.json(DETAIL),
    'holiday-import': () =>
      Response.json(
        { code: 'bad_request', errors: [{ field: 'file', line: 2, detail: 'the date is outside 2027' }] },
        { status: 400 },
      ),
  };
  vi.stubGlobal('navigator', {
    ...navigator,
    locks: { request: (_n: string, work: () => Promise<unknown>) => work() },
  });
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!.split('?')[0]!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});

async function noViolations() {
  // Measured once the dialog has finished opening: mid-fade colours are not what anyone reads.
  await waitFor(() => expect(document.getAnimations().filter((a) => a.playState === 'running')).toHaveLength(0));
  expect(await axe(document.body)).toHaveNoViolations();
}

describe('the console Holiday calendar', () => {
  it.each(WIDTHS)('the years at %ipx', async (width) => {
    await atWidth(width);
    renderWithMessages(
      <ConsoleShell permissions={PERMISSIONS} view="holidays">
        <HolidayYears canManage />
      </ConsoleShell>,
    );
    await screen.findByTestId('console-holiday-years');
    await noViolations();
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);
  });

  it.each(WIDTHS)('a year, its refused import and the publication confirmation at %ipx', async (width) => {
    await atWidth(width);
    renderWithMessages(
      <ConsoleShell permissions={PERMISSIONS} view="holidays">
        <HolidayYearScreen year={2027} canManage />
      </ConsoleShell>,
    );
    await screen.findByTestId('console-holiday-days');
    await noViolations();
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);
    fireEvent.change(screen.getByTestId('console-holiday-file'), {
      target: { files: [new File(['x'], '2027.csv', { type: 'text/csv' })] },
    });
    await screen.findByTestId('console-holiday-import-refused');
    await noViolations();
    fireEvent.click(screen.getByTestId('console-holiday-publish'));
    const dialog = await screen.findByTestId('console-holiday-publish-dialog');
    expect(within(dialog).getByTestId('console-holiday-publish-confirm')).toBeInTheDocument();
    await noViolations();
  });

  // QA's F04-S03 dry run (TC-F04-S03-029): at 360px the days table was 505px wide inside its card, its Change,
  // Edit and Remove columns off-screen and names broken mid-word. Every day, its change and its actions fit.
  it.each(WIDTHS)('a year’s days fit the screen at %ipx, every action visible and names whole', async (width) => {
    await atWidth(width);
    renderWithMessages(
      <ConsoleShell permissions={PERMISSIONS} view="holidays">
        <HolidayYearScreen year={2027} canManage />
      </ConsoleShell>,
    );
    const days = await screen.findByTestId('console-holiday-days');
    const card = screen.getByTestId('console-holiday-days-card');
    expect(days.getBoundingClientRect().right).toBeLessThanOrEqual(card.getBoundingClientRect().right);
    // A visually hidden header (`sr-only`) is clipped on purpose; everything people see must fit.
    for (const scrolling of [days, ...days.querySelectorAll('*:not(.sr-only)')]) {
      expect(scrolling.scrollWidth, scrolling.tagName).toBeLessThanOrEqual(scrolling.clientWidth + 1);
    }
    for (const action of [
      ...within(days).getAllByTestId('console-holiday-day-edit'),
      ...within(days).getAllByTestId('console-holiday-day-remove'),
      ...within(days).getAllByTestId('console-holiday-day-change'),
    ]) {
      expect(action.getBoundingClientRect().right).toBeLessThanOrEqual(window.innerWidth);
    }
    for (const name of within(days).getAllByTestId('console-holiday-day-name')) {
      expect(getComputedStyle(name).overflowWrap).not.toBe('anywhere');
    }
    expect(within(days).getAllByTestId('console-holiday-day')).toHaveLength(4);
    await noViolations();
  });
});
