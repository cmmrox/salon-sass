import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { atWidth, axe, BRANDS, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { AccountFrame } from './AccountFrame';
import {
  AMAYA,
  DETAIL,
  ISHARA,
  KAVINDI,
  NETHMI,
  NOW,
  SANDUNI,
  SERVICES,
  TEAM,
  booking,
  page as bookingPage,
} from './bookings-fixtures';
import { BookingsScreen } from './BookingsScreen';

vi.mock('next/navigation', async () => (await import('./bookings-fixtures')).navigation);
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F07-S03 AC-11: the Bookings page, New appointment, the appointment sheet and the cancel confirmation have no WCAG 2.2
 * AA violation, colour contrast included, at every width and with a dark and a light brand, rendered in real Chromium
 * in the workspace frame, with no horizontal scrolling; a long customer name wraps; the sheets keep focus inside and
 * give it back to what opened them.
 */
const LONG = booking({
  id: '55555555-0000-4000-8000-000000000009',
  reference: 'HB6-X45',
  startsAt: '2086-06-15T09:00:00+05:30',
  endsAt: '2086-06-15T10:00:00+05:30',
  member: ISHARA,
  customer: { name: 'Nanayakkara Wanigasekara Basnayake Dharmasena', mobile: '077 123 9876' },
  service: { id: 's', name: 'Hair colour', variantId: 'v', variantName: 'Long' },
  channel: 'instagram',
});
const HELD = booking({
  id: '66666666-0000-4000-8000-000000000001',
  reference: 'K7M-4QX',
  state: 'held',
  startsAt: '2086-06-15T10:30:00+05:30',
  endsAt: '2086-06-15T11:30:00+05:30',
  holdExpiresAt: '2086-06-15T08:10:00+05:30',
  customer: { name: 'Ruwini Fernando', mobile: '077 300 1188' },
  channel: 'phone',
});
let answers: Record<string, () => Response | Promise<Response>>;
const start = window.location.pathname + window.location.search;
beforeEach(() => {
  window.history.replaceState(null, '', start);
  answers = {
    principal: () => Response.json({ kind: 'staff', permissions: [] }),
    'booking-clock': () => Response.json({ now: NOW }),
    bookings: () => Response.json(bookingPage([LONG, AMAYA, KAVINDI, NETHMI], 54)),
    booking: () =>
      Response.json({
        ...DETAIL,
        history: [
          ...DETAIL.history,
          {
            at: '2086-06-15T07:52:00+05:30',
            from: 'confirmed',
            to: 'cancelled',
            by: { kind: 'system', label: null },
            reason: 'other',
            note: 'A very long note '.repeat(10).trim(),
          },
        ],
      }),
    'team-members': () => Response.json(TEAM),
    'catalogue-services': () => Response.json(SERVICES),
    'booking-open-days': () => Response.json({ days: ['2086-06-15', '2086-06-16'] }),
    'booking-availability': () =>
      Response.json({
        date: '2086-06-15',
        reason: null,
        times: ['09:00', '09:15', '09:30', '09:45', '10:00', '10:15', '10:30', '10:45', '11:00'].map((time, n) => ({
          time,
          startsAt: `2086-06-15T${time}:00+05:30`,
          member: n % 2 ? SANDUNI : ISHARA,
        })),
      }),
    'booking-place': () => Response.json(HELD, { status: 201 }),
    'booking-release': () => Response.json({ ok: true }),
  };
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!.split('?')[0]!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', start);
});

function render(brand: string, readOnly: 'support' | 'salon' | null = null) {
  return renderWithMessages(
    <AccountFrame
      salon={{ slug: 'alpha', name: 'Alpha Studio', brandColour: brand, timeZone: 'Asia/Colombo' }}
      permissions={['session.manage', 'calendar.read', 'booking.manage', 'team.read']}
      view="bookings"
    >
      <BookingsScreen
        kind="staff"
        timeZone="Asia/Colombo"
        scope="salon"
        canBook={readOnly === null}
        canAct={readOnly === null}
        canSetRules
        readOnly={readOnly}
        phoneRegion="LK"
      />
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
const ready = () => waitFor(() => expect(screen.getByTestId('bookings-heading').textContent).toMatch(/booking/));

async function newAppointment() {
  fireEvent.click(screen.getByTestId('bookings-new'));
  const sheet = await screen.findByTestId('new-appointment');
  await within(sheet).findByTestId('new-appointment-find');
  return sheet;
}

describe('the Bookings page', () => {
  it.each(BRANDS.flatMap((brand) => WIDTHS.map((width) => [brand, width] as const)))(
    'a day with a hold counting down, a long name and pages, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      render(brand);
      await ready();
      await screen.findByTestId('bookings-pager');
      await noViolations();
      noHorizontalScroll();
    },
  );

  it('loading', async () => {
    answers.bookings = () => new Promise<Response>(() => undefined);
    render(BRANDS[1]);
    await screen.findByTestId('bookings-loading');
    await noViolations();
  });

  it.each(BRANDS)('nothing matches, brand %s', async (brand) => {
    window.history.replaceState(null, '', `${window.location.pathname}?status=no_show`);
    answers.bookings = () => Response.json(bookingPage([]));
    render(brand);
    await screen.findByTestId('bookings-clear');
    await noViolations();
  });

  it.each(BRANDS)('unavailable, and read-only for a support session, brand %s', async (brand) => {
    answers.bookings = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    render(brand, 'support');
    await screen.findByTestId('bookings-unavailable');
    screen.getByTestId('bookings-read-only');
    await noViolations();
  });
});

describe('New appointment', () => {
  it.each(BRANDS.flatMap((brand) => [360, 768, 1280].map((width) => [brand, width] as const)))(
    'the form with its problems, the free times and the hold, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      render(brand);
      await ready();
      const opener = screen.getByTestId('bookings-new');
      opener.focus();
      const sheet = await newAppointment();
      await waitFor(() => expect(sheet.contains(document.activeElement)).toBe(true));
      fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
      await within(sheet).findByTestId('new-appointment-mobile-error');
      await noViolations();
      fireEvent.change(within(sheet).getByTestId('new-appointment-name'), { target: { value: 'Ruwini Fernando' } });
      fireEvent.change(within(sheet).getByTestId('new-appointment-mobile'), { target: { value: '077 300 1188' } });
      fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
      await within(sheet).findAllByTestId('new-appointment-time');
      await noViolations();
      noHorizontalScroll();
      fireEvent.click(within(sheet).getAllByTestId('new-appointment-time')[0]!);
      await within(sheet).findByTestId('new-appointment-countdown');
      await noViolations();
      noHorizontalScroll();
    },
  );

  it.each(BRANDS)('a refusal with the link to the rules, and a day with no time, brand %s', async (brand) => {
    answers['booking-availability'] = () => Response.json({ code: 'rules_not_set' }, { status: 409 });
    render(brand);
    await ready();
    const sheet = await newAppointment();
    fireEvent.change(within(sheet).getByTestId('new-appointment-name'), { target: { value: 'Ruwini Fernando' } });
    fireEvent.change(within(sheet).getByTestId('new-appointment-mobile'), { target: { value: '077 300 1188' } });
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    await within(sheet).findByTestId('new-appointment-rules-link');
    await noViolations();
    answers['booking-availability'] = () => Response.json({ date: '2086-06-15', times: [], reason: 'fully_booked' });
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    await within(sheet).findByTestId('new-appointment-reason');
    await noViolations();
  });

  it('the day’s Calendar of open days', async () => {
    render(BRANDS[0]);
    await ready();
    const sheet = await newAppointment();
    fireEvent.click(within(sheet).getByTestId('new-appointment-day'));
    const calendar = await screen.findByTestId('new-appointment-calendar');
    await waitFor(() => expect(document.getAnimations().filter((a) => a.playState === 'running')).toHaveLength(0));
    expect(await axe(calendar)).toHaveNoViolations();
  });
});

describe('the appointment sheet', () => {
  it.each(BRANDS.flatMap((brand) => [360, 768, 1280].map((width) => [brand, width] as const)))(
    'its facts, actions, explanation and history, brand %s at %ipx, keeping focus and giving it back',
    async (brand, width) => {
      await atWidth(width);
      render(brand);
      await ready();
      const opener = screen.getAllByTestId('booking-open')[1]!;
      opener.focus();
      fireEvent.click(opener);
      const sheet = await screen.findByTestId('appointment-sheet');
      await within(sheet).findByTestId('appointment-history');
      await waitFor(() => expect(sheet.contains(document.activeElement)).toBe(true));
      await noViolations();
      noHorizontalScroll();
      fireEvent.keyDown(document.activeElement ?? sheet, { key: 'Escape' });
      await waitFor(() => expect(screen.queryByTestId('appointment-sheet')).toBeNull());
      await waitFor(() => expect(document.activeElement).toBe(screen.getAllByTestId('booking-open')[1]));
    },
  );

  it.each(BRANDS)('the cancel confirmation with its reason missing, brand %s', async (brand) => {
    render(brand);
    await ready();
    fireEvent.click(screen.getAllByTestId('booking-open')[1]!);
    const sheet = await screen.findByTestId('appointment-sheet');
    fireEvent.click(await within(sheet).findByTestId('appointment-cancel'));
    const dialog = await screen.findByRole('alertdialog');
    fireEvent.click(within(dialog).getByTestId('cancel-confirm'));
    await within(dialog).findByTestId('cancel-reason-error');
    await noViolations();
  });
});
