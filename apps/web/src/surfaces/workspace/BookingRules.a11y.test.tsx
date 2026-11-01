import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { atWidth, axe, BRANDS, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { AccountFrame } from './AccountFrame';
import { BookingRulesSettingsScreen } from './BookingRulesSettingsScreen';
import { SettingsScreen } from './SettingsScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F04-S04 AC-10: the Booking rules tab, its history sheet and its states have no WCAG 2.2 AA violation, colour
 * contrast included, at every width and with a dark and a light brand, rendered in real Chromium in the
 * workspace frame, with no horizontal scrolling. The history sheet keeps focus inside and returns it to
 * History when it closes.
 */
const ME = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const RULES = {
  slotIntervalMinutes: 15,
  minimumNoticeMinutes: 60,
  bookingHorizonDays: 60,
  deposit: { kind: 'percentage', percent: 20, capMinor: 200_000 },
  freeCancellationHours: 24,
  lateCancellation: { kind: 'share', percent: 50 },
  noShow: { kind: 'share', percent: 100 },
  reschedule: { limit: 2, untilHours: 12 },
};
const HISTORY = {
  items: [3, 2, 1].map((version) => ({
    versionId: `00000000-0000-4000-8000-00000000000${version}`,
    version,
    createdAt: '2026-11-01T04:30:00Z',
    createdBy: version === 3 ? ME : 'someone-else',
    createdByLabel: version === 1 ? null : 'a-very-long-manager-address@example-salon-domain.test',
    changedFields:
      version === 1
        ? [
            'slotIntervalMinutes',
            'minimumNoticeMinutes',
            'bookingHorizonDays',
            'deposit',
            'freeCancellationHours',
            'lateCancellation',
            'noShow',
            'reschedule',
          ]
        : ['deposit'],
  })),
  page: 0,
  size: 3,
  total: 7,
};

let answers: Record<string, () => Response | Promise<Response>>;
beforeEach(() => {
  answers = {
    principal: () => Response.json({ kind: 'staff', staffId: ME, permissions: ['settings.read', 'settings.manage'] }),
    'booking-rules': () => Response.json({ version: 3, rules: RULES, currency: 'LKR' }),
    'booking-rules-history': () => Response.json(HISTORY),
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
      <SettingsScreen tab="rules">
        <BookingRulesSettingsScreen kind="staff" timeZone="Asia/Colombo" readOnly={readOnly} />
      </SettingsScreen>
    </AccountFrame>,
  );
}

async function noViolations() {
  // Measured once overlays have finished opening: mid-fade colours are not what anyone reads.
  await waitFor(() => expect(document.getAnimations().filter((a) => a.playState === 'running')).toHaveLength(0));
  expect(await axe(document.body)).toHaveNoViolations();
}

const noHorizontalScroll = () =>
  expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);

describe('the Booking rules tab', () => {
  it.each(BRANDS.flatMap((brand) => WIDTHS.map((width) => [brand, width] as const)))(
    'saved, with every typed field shown, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      page(brand);
      await screen.findByTestId('rules-tab');
      await screen.findByTestId('rules-noshow-percent');
      await noViolations();
      noHorizontalScroll();
    },
  );

  it('loading', async () => {
    vi.stubGlobal('fetch', (url: string) =>
      url.endsWith('/principal')
        ? Promise.resolve(Response.json({ kind: 'staff', permissions: [] }))
        : new Promise<Response>(() => undefined),
    );
    page(BRANDS[1]);
    await screen.findByTestId('rules-loading');
    await noViolations();
  });

  it.each(BRANDS.flatMap((brand) => [360, 1280].map((width) => [brand, width] as const)))(
    'before the first save (Not saved yet) and with field errors, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      answers['booking-rules'] = () => Response.json({ version: 0, rules: null, currency: 'LKR' });
      page(brand);
      await screen.findByTestId('rules-not-saved');
      await noViolations();
      fireEvent.change(screen.getByTestId('rules-cap'), { target: { value: '499' } });
      fireEvent.change(screen.getByTestId('rules-percent'), { target: { value: '101' } });
      await screen.findByTestId('rules-cap-error');
      await noViolations();
      noHorizontalScroll();
    },
  );

  it.each(BRANDS)('a fixed amount, no deposit and not online, brand %s', async (brand) => {
    page(brand);
    fireEvent.click(await screen.findByTestId('rules-deposit-amount'));
    await screen.findByTestId('rules-amount');
    await noViolations();
    fireEvent.click(screen.getByTestId('rules-deposit-none'));
    await waitFor(() => expect(screen.queryByTestId('rules-amount')).toBeNull());
    await noViolations();
  });

  it.each(BRANDS)('after a conflict, when nothing changed and when unavailable, brand %s', async (brand) => {
    answers['booking-rules-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    page(brand);
    fireEvent.click(await screen.findByTestId('rules-deposit-none'));
    fireEvent.click(screen.getByTestId('rules-save'));
    await screen.findByTestId('rules-conflict');
    await noViolations();
    answers['booking-rules-save'] = () => Response.json({ code: 'invalid_state' }, { status: 409 });
    fireEvent.click(screen.getByTestId('rules-save'));
    await screen.findByTestId('rules-unchanged');
    await noViolations();
    answers['booking-rules-save'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    fireEvent.click(screen.getByTestId('rules-save'));
    await screen.findByTestId('rules-unavailable');
    await noViolations();
  });

  it('read-only for a support session', async () => {
    page(BRANDS[0], 'support');
    await screen.findByTestId('rules-read-only');
    await noViolations();
  });

  it('a Select opened with the keyboard: its options', async () => {
    page(BRANDS[1]);
    const trigger = await screen.findByTestId('rules-interval');
    trigger.focus();
    fireEvent.keyDown(trigger, { key: 'Enter' });
    const options = await screen.findByRole('listbox');
    await waitFor(() => expect(document.getAnimations().filter((a) => a.playState === 'running')).toHaveLength(0));
    // The list alone: while it is open Radix hides the rest of the page from assistive technology (its modal
    // behaviour), which axe reports on the hidden, inert page rather than on the list.
    expect(await axe(options)).toHaveNoViolations();
  });

  it.each(BRANDS.flatMap((brand) => [360, 1280].map((width) => [brand, width] as const)))(
    'the history sheet, brand %s at %ipx, keeps focus inside and returns it',
    async (brand, width) => {
      await atWidth(width);
      page(brand);
      const opener = await screen.findByTestId('rules-history');
      opener.focus();
      fireEvent.click(opener);
      const sheet = await screen.findByTestId('rules-history-sheet');
      await within(sheet).findByTestId('rules-history-list');
      await waitFor(() => expect(sheet.contains(document.activeElement)).toBe(true));
      await noViolations();
      noHorizontalScroll();
      fireEvent.keyDown(document.activeElement ?? sheet, { key: 'Escape' });
      await waitFor(() => expect(screen.queryByTestId('rules-history-sheet')).not.toBeInTheDocument());
      await waitFor(() => expect(document.activeElement).toBe(screen.getByTestId('rules-history')));
    },
  );
});
