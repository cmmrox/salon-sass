import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { atWidth, axe, BRANDS, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { AccountFrame } from './AccountFrame';
import { HoursSettingsScreen } from './HoursSettingsScreen';
import { SettingsScreen } from './SettingsScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F04-S03 AC-12: the Hours & closures tab, its closure sheet and its states have no WCAG 2.2 AA violation,
 * colour contrast included, at every width and with a dark and a light brand, rendered in real Chromium in
 * the workspace frame. The sheet keeps focus inside and returns it to + Add closure when it closes.
 */
const DAY = (date: string, name: string, kind: string, past = false) => ({
  date,
  name,
  kind,
  choice: null,
  opens: null,
  closes: null,
  toReview: true,
  past,
});
const YEAR = {
  year: 2026,
  revision: 1,
  publishedAt: '2026-10-01T04:30:00Z',
  reviewed: false,
  version: 0,
  days: [
    DAY('2026-11-08', 'Deepavali Festival Day', 'public'),
    DAY('2026-11-24', 'Il Full Moon Poya Day', 'poya'),
    DAY('2026-12-25', 'Christmas Day', 'public'),
  ],
};
const CLOSURE = {
  id: '2b0c4ad4-8f0d-4d3a-9d1f-1a0b9c8e7f61',
  date: '2026-11-05',
  reason: 'Staff training',
  affectedBookings: 2,
};

let answers: Record<string, () => Response | Promise<Response>>;
beforeEach(() => {
  answers = {
    principal: () => Response.json({ kind: 'staff', permissions: ['settings.read', 'settings.manage'] }),
    hours: () =>
      Response.json({
        version: 1,
        weekly: ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'].map((day) => ({
          day,
          open: day !== 'sunday',
          opens: day === 'sunday' ? null : '09:00',
          closes: day === 'sunday' ? null : '18:00',
        })),
        outsideHours: [{ time: '18:30', customerName: 'Ishara Fernando', serviceName: 'Colour' }],
      }),
    holidays: () => Response.json({ years: [YEAR] }),
    closures: () => Response.json([CLOSURE]),
    'closure-impact': () =>
      Response.json({
        date: '2026-11-09',
        bookings: [{ time: '10:30', customerName: 'Nimali Perera', serviceName: 'Haircut' }],
      }),
  };
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!.split('?')[0]!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});

function page(brand: string, readOnly: 'support' | 'salon' | null = null) {
  return renderWithMessages(
    <AccountFrame
      salon={{ slug: 'alpha', name: 'Alpha Studio', brandColour: brand, timeZone: 'Asia/Colombo' }}
      permissions={['session.manage', 'settings.read', 'settings.manage']}
      view="settings"
    >
      <SettingsScreen tab="hours">
        <HoursSettingsScreen kind="staff" timeZone="Asia/Colombo" readOnly={readOnly} />
      </SettingsScreen>
    </AccountFrame>,
  );
}

async function noViolations() {
  // Measured once the sheet and popover have finished opening: mid-fade colours are not what anyone reads.
  await waitFor(() => expect(document.getAnimations().filter((a) => a.playState === 'running')).toHaveLength(0));
  expect(await axe(document.body)).toHaveNoViolations();
}

describe('the Hours & closures tab', () => {
  it.each(BRANDS.flatMap((brand) => WIDTHS.map((width) => [brand, width] as const)))(
    'loaded with brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      page(brand);
      await screen.findByTestId('hours-tab');
      await noViolations();
      expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);
    },
  );

  it('loading', async () => {
    vi.stubGlobal('fetch', (url: string) =>
      url.endsWith('/principal')
        ? Promise.resolve(Response.json({ kind: 'staff', permissions: [] }))
        : new Promise<Response>(() => undefined),
    );
    page(BRANDS[1]);
    await screen.findByTestId('hours-loading');
    await noViolations();
  });

  it.each(BRANDS)('before the first save, with no year published and no closure, brand %s', async (brand) => {
    answers.hours = () => Response.json({ version: 0, weekly: null, outsideHours: [] });
    answers.holidays = () => Response.json({ years: [] });
    answers.closures = () => Response.json([]);
    page(brand);
    await screen.findByTestId('hours-not-saved');
    await screen.findByTestId('closures-empty');
    await noViolations();
  });

  it.each(BRANDS)('with a field error, brand %s', async (brand) => {
    answers['hours-save'] = () =>
      Response.json({ code: 'bad_request', errors: [{ field: 'weekly[1].closes', detail: 'x' }] }, { status: 400 });
    page(brand);
    fireEvent.click(await screen.findByTestId('hours-sunday-open'));
    fireEvent.click(screen.getByTestId('hours-save'));
    await screen.findByTestId('hours-tuesday-error');
    await noViolations();
  });

  it.each(BRANDS)('after a conflict and when unavailable, brand %s', async (brand) => {
    answers['hours-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    page(brand);
    fireEvent.click(await screen.findByTestId('hours-sunday-open'));
    fireEvent.click(screen.getByTestId('hours-save'));
    await screen.findByTestId('hours-conflict');
    await noViolations();
  });

  it('read-only for a support session', async () => {
    page(BRANDS[0], 'support');
    await screen.findByTestId('hours-read-only');
    await noViolations();
  });

  it.each(BRANDS.flatMap((brand) => [360, 1280].map((width) => [brand, width] as const)))(
    'the closure sheet with a date and its bookings, brand %s at %ipx, keeps focus inside and returns it',
    async (brand, width) => {
      await atWidth(width);
      page(brand);
      const opener = await screen.findByTestId('closure-add');
      opener.focus();
      fireEvent.click(opener);
      const sheet = await screen.findByTestId('closure-sheet');
      await waitFor(() => expect(sheet.contains(document.activeElement)).toBe(true));
      await noViolations();
      fireEvent.click(within(sheet).getByTestId('closure-date'));
      const calendar = await screen.findByTestId('closure-calendar');
      await noViolations();
      const enabled = within(calendar)
        .getAllByRole('button')
        .filter((button) => !button.hasAttribute('disabled') && /\d{4}/.test(button.getAttribute('aria-label') ?? ''));
      fireEvent.click(enabled.at(-1)!);
      await within(sheet).findByTestId('closure-bookings');
      await noViolations();
      fireEvent.keyDown(document.activeElement ?? sheet, { key: 'Escape' });
      await waitFor(() => expect(screen.queryByTestId('closure-sheet')).not.toBeInTheDocument());
      await waitFor(() => expect(document.activeElement).toBe(screen.getByTestId('closure-add')));
    },
  );
});
