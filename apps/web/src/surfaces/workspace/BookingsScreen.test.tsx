import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { chooseOption } from '@test/select';
import { setViewportWidth } from '@test/viewport';
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
  page,
} from './bookings-fixtures';
import { BookingsScreen } from './BookingsScreen';

vi.mock('next/navigation', async () => (await import('./bookings-fixtures')).navigation);
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

type Call = { action: string; query: URLSearchParams; body: Record<string, unknown> | null; keepalive?: boolean };
let calls: Call[];
let answers: Record<string, (call: Call) => Response | Promise<Response>>;
const FREE = {
  date: '2086-06-16',
  reason: null,
  times: [
    { time: '09:00', startsAt: '2086-06-16T09:00:00+05:30', member: ISHARA },
    { time: '10:30', startsAt: '2086-06-16T10:30:00+05:30', member: SANDUNI },
  ],
};
const HELD = booking({
  id: '66666666-0000-4000-8000-000000000001',
  reference: 'K7M-4QX',
  state: 'held',
  startsAt: '2086-06-16T10:30:00+05:30',
  endsAt: '2086-06-16T11:30:00+05:30',
  holdExpiresAt: '2086-06-15T08:10:00+05:30',
  customer: { name: 'Ruwini Fernando', mobile: '077 300 1188' },
  channel: 'phone',
});

beforeEach(() => {
  window.history.replaceState(null, '', '/workspace/bookings');
  calls = [];
  answers = {
    principal: () => Response.json({ kind: 'staff', permissions: [] }),
    'booking-clock': () => Response.json({ now: NOW }),
    bookings: () => Response.json(page([AMAYA, KAVINDI, NETHMI])),
    booking: () => Response.json(DETAIL),
    'team-members': () => Response.json(TEAM),
    'catalogue-services': () => Response.json(SERVICES),
    'booking-open-days': () => Response.json({ days: ['2086-06-15', '2086-06-16', '2086-06-17'] }),
    'booking-availability': () => Response.json(FREE),
    'booking-place': () => Response.json(HELD, { status: 201 }),
    'booking-confirm': () => Response.json({ ...HELD, state: 'confirmed', holdExpiresAt: null }),
    'booking-release': () => Response.json({ ok: true }),
    'booking-arrive': () => Response.json({ ...AMAYA, state: 'arrived' }),
    'booking-cancel': () => Response.json({ ...AMAYA, state: 'cancelled' }),
  };
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const [path, search] = url.split('?') as [string, string | undefined];
    const action = path.split('/').pop()!;
    const call = {
      action,
      query: new URLSearchParams(search ?? ''),
      body: init?.body ? JSON.parse(String(init.body)) : null,
      keepalive: init?.keepalive,
    };
    calls.push(call);
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))(call);
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const render = (props: Partial<React.ComponentProps<typeof BookingsScreen>> = {}) =>
  renderWithMessages(
    <BookingsScreen
      kind="staff"
      timeZone="Asia/Colombo"
      scope="salon"
      canBook
      canAct
      canSetRules={false}
      readOnly={null}
      phoneRegion="LK"
      principalKnown
      {...props}
    />,
  );
const sent = (action: string) => calls.filter((call) => call.action === action);
const problem =
  (code: string, status = 409, errors: unknown[] = []) =>
  () =>
    Response.json({ code, errors }, { status });
const heading = () => screen.getByTestId('bookings-heading');
async function ready() {
  await waitFor(() => expect(heading()).toHaveTextContent(/booking/));
}
async function openDesk(rendered = false) {
  if (!rendered) render();
  await ready();
  fireEvent.click(screen.getByTestId('bookings-new'));
  const sheet = await screen.findByTestId('new-appointment');
  await within(sheet).findByTestId('new-appointment-find');
  return sheet;
}
async function fillCustomer(sheet: HTMLElement) {
  fireEvent.change(within(sheet).getByTestId('new-appointment-name'), { target: { value: 'Ruwini Fernando' } });
  fireEvent.change(within(sheet).getByTestId('new-appointment-mobile'), { target: { value: '077 300 1188' } });
}

describe('the Bookings page (F07-S03 AC-2, AC-3)', () => {
  it('shows a loading state, then the API’s today in time order under "N bookings on <day>"', async () => {
    render();
    expect(screen.getByTestId('bookings-loading')).toHaveAttribute('aria-busy', 'true');
    await ready();
    expect(heading()).toHaveTextContent(/^3 bookings on Sat, (15 Jun|Jun 15), 2086$/);
    expect(sent('bookings')[0]?.query.get('from')).toBe('2086-06-15');
    const rows = screen.getAllByTestId('booking-row');
    expect(rows.map((row) => within(row).getByTestId('booking-reference').textContent)).toEqual([
      '2ZE-GC7',
      'W6S-2Z5',
      'Y3H-5TT',
    ]);
    expect(within(rows[0]!).getByTestId('booking-mobile')).toHaveTextContent('071 765 4321');
    expect(within(rows[0]!).getByTestId('booking-status')).toHaveTextContent('Confirmed');
    expect(within(rows[0]!).getByTestId('booking-source')).toHaveTextContent('WhatsApp');
    expect(within(rows[1]!).getByTestId('booking-status')).toHaveTextContent(/^Held · (10:00|9:\d\d) left$/);
    expect(screen.getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
      'When',
      'Customer',
      'Service',
      'Professional',
      'Status',
      'Reference',
    ]);
  });

  it('says one booking in the singular, and shows the prototype’s rows below 900 pixels', async () => {
    answers.bookings = () => Response.json(page([AMAYA]));
    setViewportWidth(360);
    render();
    await ready();
    expect(heading()).toHaveTextContent(/^1 booking on /);
    expect(screen.queryByTestId('bookings-table')).toBeNull();
    const item = screen.getByTestId('booking-item');
    for (const fact of [
      '10:00',
      'Amaya Rajapaksa',
      '071 765 4321',
      'Cut and blow-dry',
      'Sanduni Wickramasinghe',
      '2ZE-GC7',
      'Confirmed',
    ])
      expect(item).toHaveTextContent(fact);
  });

  it('reads the list again when a hold’s time is up, and shows what the API says', async () => {
    const expired = { ...KAVINDI, holdExpiresAt: '2086-06-15T08:00:01+05:30' };
    answers.bookings = () => Response.json(page(sent('bookings').length > 1 ? [AMAYA] : [AMAYA, expired]));
    render();
    await ready();
    await waitFor(() => expect(screen.getAllByTestId('booking-row')).toHaveLength(1), { timeout: 4000 });
    expect(sent('bookings').length).toBeGreaterThan(1);
  });

  it('asks again while the API still lists a hold whose time is up, until it no longer does (BUG-F07-001)', async () => {
    const expired = { ...KAVINDI, holdExpiresAt: '2086-06-15T08:00:01+05:30' };
    // The first read at zero lands a moment before the expiry by the API's clock, so it still lists the hold.
    answers.bookings = () => Response.json(page(sent('bookings').length > 2 ? [AMAYA] : [AMAYA, expired]));
    render();
    await ready();
    await waitFor(() => expect(sent('bookings').length).toBe(2), { timeout: 4000 });
    expect(screen.getAllByTestId('booking-row')).toHaveLength(2);
    await waitFor(() => expect(screen.getAllByTestId('booking-row')).toHaveLength(1), { timeout: 5000 });
    expect(sent('bookings')).toHaveLength(3);
  }, 10_000);

  it('searches as typed, through the API’s q, keeping it in the address', async () => {
    render();
    await ready();
    fireEvent.change(screen.getByTestId('bookings-search'), { target: { value: 'K7M' } });
    await waitFor(() => expect(sent('bookings').at(-1)?.query.get('q')).toBe('K7M'));
    expect(new URLSearchParams(window.location.search).get('q')).toBe('K7M');
  });

  it('filters by status and professional through the API, kept in the address', async () => {
    render();
    await ready();
    await chooseOption(screen.getByTestId('bookings-status'), 'Held');
    await waitFor(() => expect(sent('bookings').at(-1)?.query.getAll('state')).toEqual(['held']));
    expect(window.location.search).toBe('?status=held');
    await waitFor(() => expect(screen.getByTestId('bookings-professional')).toBeInTheDocument());
    await chooseOption(screen.getByTestId('bookings-professional'), 'Ishara Fonseka');
    await waitFor(() => expect(sent('bookings').at(-1)?.query.get('memberId')).toBe(ISHARA.id));
  });

  it('restores the day and filters from the address', async () => {
    window.history.replaceState(null, '', `/workspace/bookings?day=2086-06-16&status=cancelled&q=Nethmi`);
    render();
    await ready();
    const query = sent('bookings')[0]!.query;
    expect([query.get('from'), query.getAll('state'), query.get('q')]).toEqual(['2086-06-16', ['cancelled'], 'Nethmi']);
    expect(screen.getByTestId('bookings-search')).toHaveValue('Nethmi');
    expect(screen.getByTestId('bookings-day-today')).toBeInTheDocument();
  });

  it('opens the day’s Calendar with keyboard focus on the chosen day', async () => {
    render();
    await ready();
    fireEvent.click(screen.getByTestId('bookings-day'));
    const calendar = await screen.findByTestId('bookings-calendar');
    await waitFor(() => expect(within(calendar).getByRole('button', { name: /selected$/ })).toHaveFocus());
  });

  it('shows Empty with Clear filters when nothing matches', async () => {
    window.history.replaceState(null, '', '/workspace/bookings?status=no_show');
    answers.bookings = () => Response.json(page([]));
    render();
    const empty = await screen.findByTestId('bookings-empty');
    expect(empty).toHaveTextContent('No bookings match.');
    expect(empty).toHaveTextContent('Try another day, or clear the search.');
    fireEvent.click(within(empty).getByTestId('bookings-clear'));
    await waitFor(() => expect(window.location.search).toBe(''));
  });

  it('pages a long day with Pagination', async () => {
    answers.bookings = (call) => Response.json({ ...page([AMAYA], 54), page: Number(call.query.get('page')) });
    render();
    await ready();
    expect(heading()).toHaveTextContent(/^54 bookings on/);
    fireEvent.click(within(screen.getByTestId('bookings-pager')).getByTestId('bookings-next'));
    await waitFor(() => expect(sent('bookings').at(-1)?.query.get('page')).toBe('1'));
    expect(window.location.search).toBe('?page=2');
  });

  it('says when the bookings cannot be reached, and tries again', async () => {
    answers.bookings = problem('service_unavailable', 503);
    render();
    const unavailable = await screen.findByTestId('bookings-unavailable');
    answers.bookings = () => Response.json(page([AMAYA]));
    fireEvent.click(within(unavailable).getByTestId('bookings-retry'));
    await ready();
  });
});

describe('who sees what (F07-S03 AC-10)', () => {
  it('rendered from an expired access credential, renews in the browser and renders again with the caller’s permissions', async () => {
    const refresh = vi.spyOn((await import('./bookings-fixtures')).router, 'refresh');
    answers.principal = () => Response.json({ kind: 'staff', permissions: ['calendar.read', 'booking.manage'] });
    render({ principalKnown: false, canBook: false, scope: 'own' });
    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));
    expect(sent('principal')).toHaveLength(1);
  });

  it('a Professional with the own scope: no professional filter, no team read, no New appointment', async () => {
    render({ scope: 'own', canBook: false });
    await ready();
    expect(screen.queryByTestId('bookings-professional')).toBeNull();
    expect(screen.queryByTestId('bookings-new')).toBeNull();
    expect(sent('team-members')).toEqual([]);
    expect(sent('bookings')[0]!.query.has('memberId')).toBe(false);
  });

  it('a support session reads, read-only, with no actions offered', async () => {
    answers.booking = () => Response.json({ ...DETAIL, allowedActions: [] });
    render({ canBook: false, canAct: false, readOnly: 'support' });
    await ready();
    expect(screen.getByTestId('bookings-read-only')).toHaveTextContent('A support session can read bookings');
    fireEvent.click(screen.getAllByTestId('booking-open')[0]!);
    const sheet = await screen.findByTestId('appointment-sheet');
    await within(sheet).findByTestId('appointment-details');
    expect(within(sheet).queryByTestId('appointment-actions')).toBeNull();
  });
});

describe('New appointment (F07-S03 AC-4 to AC-6)', () => {
  it('offers open days, the services with prices and variants, who performs each, and the sources', async () => {
    const sheet = await openDesk();
    expect(sheet).toHaveTextContent('For phone calls, messages and front-desk bookings on any open day.');
    expect(sent('booking-open-days')[0]!.query.get('to')).toBe('2087-06-15');
    fireEvent.keyDown(within(sheet).getByTestId('new-appointment-service'), { key: 'Enter' });
    const services = await screen.findAllByTestId('new-appointment-service-option');
    expect(services.map((each) => each.textContent)).toEqual([
      'Cut and blow-dry · Rs. 4,500',
      'Hair colour · From Rs. 6,000',
    ]);
    fireEvent.keyDown(services[1]!, { key: 'Enter' });
    await within(sheet).findByTestId('new-appointment-variant');
    expect(sheet).toHaveTextContent('Hair length');
    fireEvent.keyDown(within(sheet).getByTestId('new-appointment-professional'), { key: 'Enter' });
    const people = await screen.findAllByRole('option');
    expect(people.map((each) => each.textContent)).toEqual(['No preference', 'Sanduni Wickramasinghe']);
  });

  it('says what is missing on the fields and runs no search', async () => {
    const sheet = await openDesk();
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    expect(within(sheet).getByTestId('new-appointment-name-error')).toHaveTextContent('Add the customer’s name.');
    expect(within(sheet).getByTestId('new-appointment-mobile-error')).toHaveTextContent(
      'Add a valid mobile number so the customer gets the confirmation.',
    );
    expect(within(sheet).getByTestId('new-appointment-name')).toHaveAttribute('aria-invalid', 'true');
    fireEvent.change(within(sheet).getByTestId('new-appointment-name'), { target: { value: 'Ruwini Fernando' } });
    fireEvent.change(within(sheet).getByTestId('new-appointment-mobile'), { target: { value: '0112345678' } });
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    expect(within(sheet).getByTestId('new-appointment-mobile-error')).toBeInTheDocument();
    expect(sent('booking-availability')).toEqual([]);
  });

  it('lists free times as chips with who would take them, cleared by any change', async () => {
    const sheet = await openDesk();
    await fillCustomer(sheet);
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    const chips = await within(sheet).findAllByTestId('new-appointment-time');
    expect(chips.map((chip) => chip.textContent)).toEqual(['09:00 · Ishara', '10:30 · Sanduni']);
    expect(sent('booking-availability')[0]!.query.has('memberId')).toBe(false);
    expect(within(sheet).getByTestId('new-appointment-times-intro')).toHaveTextContent(
      /^Free times on Sun, (16 Jun|Jun 16), 2086\. Choose one to hold it\.$/,
    );
    expect(within(sheet).getByTestId('new-appointment-no-preference')).toHaveTextContent(
      'Each time shows who would take it.',
    );
    fireEvent.change(within(sheet).getByTestId('new-appointment-name'), { target: { value: 'Ruwini F' } });
    expect(within(sheet).queryAllByTestId('new-appointment-time')).toEqual([]);
  });

  it.each([
    ['closed', null, /^The salon is closed on Sun, /],
    ['nobody_performs', null, /^Nobody on the team does this service\.$/],
    ['not_working', null, /^Nobody who does this service is working on Sun, /],
    ['not_working', ISHARA, /^Ishara Fonseka isn’t working on Sun, /],
    ['fully_booked', null, /^No free time on Sun, .* for this service\. Try another day or professional\.$/],
  ] as const)('says why there is no time: %s', async (reason, professional, words) => {
    answers['booking-availability'] = () => Response.json({ date: '2086-06-16', times: [], reason });
    const sheet = await openDesk();
    await fillCustomer(sheet);
    if (professional) await chooseOption(within(sheet).getByTestId('new-appointment-professional'), professional.name);
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    expect(await within(sheet).findByTestId('new-appointment-reason')).toHaveTextContent(words);
  });

  it('holds a chosen time with one key per attempt, shows the countdown from the API’s expiry, and confirms', async () => {
    const sheet = await openDesk();
    await fillCustomer(sheet);
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    const [, chip] = await within(sheet).findAllByTestId('new-appointment-time');
    fireEvent.click(chip!);
    fireEvent.click(chip!);
    const card = await within(sheet).findByTestId('new-appointment-hold');
    await waitFor(() => expect(within(card).getByTestId('new-appointment-holding')).toHaveFocus());
    expect(sent('booking-place')).toHaveLength(1);
    expect(sent('booking-place')[0]!.body).toMatchObject({
      memberId: null,
      time: '10:30',
      // The day chosen in the form (today by default), whatever the answer says.
      date: '2086-06-15',
      channel: 'phone',
      customer: { name: 'Ruwini Fernando', mobile: '077 300 1188' },
    });
    expect(sent('booking-place')[0]!.body?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(card).toHaveTextContent('Holding this time');
    expect(card).toHaveTextContent('Ruwini Fernando · 077 300 1188');
    expect(card).toHaveTextContent(
      'Nobody else can take this time while it is held. It is released by itself when the time runs out.',
    );
    const countdown = within(card).getByTestId('new-appointment-countdown');
    expect(countdown).toHaveAttribute('role', 'timer');
    expect(countdown).toHaveAttribute('aria-live', 'off');
    expect(countdown).toHaveTextContent(/^(10:00|9:\d\d) left$/);
    fireEvent.click(within(sheet).getByTestId('new-appointment-confirm'));
    await waitFor(() => expect(screen.queryByTestId('new-appointment')).toBeNull());
    expect(sent('booking-confirm')[0]!.body).toEqual({ id: HELD.id });
    expect(
      await screen.findByText(/^Booked for Sun, (16 Jun|Jun 16), 2086 at 10:30 with Sanduni\. Reference K7M-4QX\.$/),
    ).toBeInTheDocument();
    expect(sent('booking-release')).toEqual([]);
  });

  it('a time taken meanwhile says so and searches again; Choose another time releases the hold', async () => {
    answers['booking-place'] = problem('slot_taken');
    const sheet = await openDesk();
    await fillCustomer(sheet);
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    fireEvent.click((await within(sheet).findAllByTestId('new-appointment-time'))[0]!);
    expect(await within(sheet).findByTestId('new-appointment-refusal')).toHaveTextContent(
      'That time was just taken. Choose another.',
    );
    await waitFor(() => expect(sent('booking-availability')).toHaveLength(2));
    answers['booking-place'] = () => Response.json(HELD, { status: 201 });
    fireEvent.click((await within(sheet).findAllByTestId('new-appointment-time'))[1]!);
    await within(sheet).findByTestId('new-appointment-hold');
    fireEvent.click(within(sheet).getByTestId('new-appointment-another'));
    // No time from before the hold is offered while it is being released.
    expect(within(sheet).queryAllByTestId('new-appointment-time')).toEqual([]);
    await waitFor(() => expect(sent('booking-release')[0]?.body).toEqual({ id: HELD.id }));
    await within(sheet).findAllByTestId('new-appointment-time');
    expect(calls.findIndex((call) => call.action === 'booking-release')).toBeLessThan(
      calls.findLastIndex((call) => call.action === 'booking-availability'),
    );
  });

  it('confirming after the hold ran out says it ran out', async () => {
    answers['booking-confirm'] = problem('hold_expired');
    const sheet = await openDesk();
    await fillCustomer(sheet);
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    fireEvent.click((await within(sheet).findAllByTestId('new-appointment-time'))[0]!);
    fireEvent.click(await within(sheet).findByTestId('new-appointment-confirm'));
    expect(await within(sheet).findByTestId('new-appointment-refusal')).toHaveTextContent(
      'The hold ran out and the time was released. Find free times again.',
    );
    expect(within(sheet).queryByTestId('new-appointment-hold')).toBeNull();
  });

  it('at zero the countdown asks the API, and says the hold ran out when the API no longer holds it', async () => {
    answers['booking-place'] = () =>
      Response.json({ ...HELD, holdExpiresAt: '2086-06-15T08:00:01+05:30' }, { status: 201 });
    answers.booking = problem('not_found', 404);
    const sheet = await openDesk();
    await fillCustomer(sheet);
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    fireEvent.click((await within(sheet).findAllByTestId('new-appointment-time'))[0]!);
    expect(await within(sheet).findByTestId('new-appointment-refusal', {}, { timeout: 4000 })).toHaveTextContent(
      'The hold ran out and the time was released. Find free times again.',
    );
    expect(sent('booking').at(-1)?.query.get('id')).toBe(HELD.id);
  });

  it('closing a started form asks first, then releases the hold', async () => {
    const sheet = await openDesk();
    await fillCustomer(sheet);
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    fireEvent.click((await within(sheet).findAllByTestId('new-appointment-time'))[0]!);
    await within(sheet).findByTestId('new-appointment-hold');
    fireEvent.click(within(sheet).getByTestId('new-appointment-close'));
    fireEvent.click(await screen.findByTestId('new-appointment-leave-confirm'));
    await waitFor(() => expect(sent('booking-release')[0]?.body).toEqual({ id: HELD.id }));
    // Sent so that leaving the page at once does not cancel it.
    expect(sent('booking-release')[0]?.keepalive).toBe(true);
    await waitFor(() => expect(screen.queryByTestId('new-appointment')).toBeNull());
  });

  /** An answer the test holds back until it lets it go, as a slow connection does. */
  function heldBack() {
    let letGo!: () => void;
    const gate = new Promise<void>((resolve) => (letGo = resolve));
    return {
      answer: (respond: () => Response) => async () => {
        await gate;
        return respond();
      },
      letGo: async () => {
        letGo();
        // The answer reaches the screen, and whatever it starts (a release) is sent.
        await act(async () => new Promise((resolve) => setTimeout(resolve, 20)));
      },
    };
  }
  async function pick(sheet: HTMLElement, field: string, index: number) {
    fireEvent.keyDown(within(sheet).getByTestId(`new-appointment-${field}`), { key: 'Enter' });
    fireEvent.keyDown((await screen.findAllByTestId(`new-appointment-${field}-option`))[index]!, { key: 'Enter' });
  }
  async function pickDay(sheet: HTMLElement, day: string) {
    fireEvent.click(within(sheet).getByTestId('new-appointment-day'));
    const calendar = await screen.findByTestId('new-appointment-calendar');
    fireEvent.click(
      within(calendar)
        .getAllByRole('button')
        .find((each) => each.textContent === day)!,
    );
  }
  const CHANGES: [string, (sheet: HTMLElement) => Promise<void>][] = [
    ['the day', (sheet) => pickDay(sheet, '17')],
    ['the service', (sheet) => pick(sheet, 'service', 1)],
    ['the variant', (sheet) => pick(sheet, 'variant', 1)],
    [
      'the professional',
      (sheet) => chooseOption(within(sheet).getByTestId('new-appointment-professional'), 'Ishara Fonseka'),
    ],
    [
      'the customer’s name',
      async (sheet) =>
        void fireEvent.change(within(sheet).getByTestId('new-appointment-name'), { target: { value: 'Ruwini F' } }),
    ],
    ['the source', (sheet) => chooseOption(within(sheet).getByTestId('new-appointment-source'), 'WhatsApp')],
  ];

  it.each(CHANGES)(
    'free times answered after %s changed are not shown for the changed form (BUG-F07-002)',
    async (field, changeIt) => {
      const late = heldBack();
      answers['booking-availability'] = late.answer(() => Response.json(FREE));
      const sheet = await openDesk();
      await fillCustomer(sheet);
      // The variant has to be offered before it can change: the service with variants, chosen before the search.
      if (field === 'the variant') await pick(sheet, 'service', 1);
      fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
      await waitFor(() => expect(sent('booking-availability')).toHaveLength(1));
      await changeIt(sheet);
      await late.letGo();
      expect(within(sheet).queryAllByTestId('new-appointment-time')).toEqual([]);
      expect(within(sheet).queryByTestId('new-appointment-times-intro')).toBeNull();
    },
  );

  it('an earlier search answered last does not replace the times of the search after it (BUG-F07-002)', async () => {
    const late = heldBack();
    const LATER = { date: '2086-06-17', reason: null, times: [{ ...FREE.times[0]!, time: '11:00' }] };
    answers['booking-availability'] = (call) =>
      call.query.get('date') === '2086-06-15' ? late.answer(() => Response.json(FREE))() : Response.json(LATER);
    const sheet = await openDesk();
    await fillCustomer(sheet);
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    await pickDay(sheet, '17');
    fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
    await within(sheet).findAllByTestId('new-appointment-time');
    await late.letGo();
    expect(
      within(sheet)
        .getAllByTestId('new-appointment-time')
        .map((chip) => chip.textContent),
    ).toEqual(['11:00 · Ishara']);
    expect(within(sheet).getByTestId('new-appointment-times-intro')).toHaveTextContent(/(17 Jun|Jun 17), 2086/);
  });

  it('rules never saved: the words, and the link only for those who may set them', async () => {
    answers['booking-availability'] = problem('rules_not_set');
    for (const canSetRules of [true, false]) {
      const { unmount } = render({ canSetRules });
      const sheet = await openDesk(true);
      await fillCustomer(sheet);
      fireEvent.click(within(sheet).getByTestId('new-appointment-find'));
      const refusal = await within(sheet).findByTestId('new-appointment-refusal');
      expect(refusal).toHaveTextContent('Set your booking rules in Settings before taking bookings.');
      expect(within(refusal).queryByRole('link')?.getAttribute('href') ?? null).toBe(
        canSetRules ? '/workspace/settings/rules' : null,
      );
      unmount();
    }
  });
});

describe('the appointment sheet (F07-S03 AC-7 to AC-9)', () => {
  async function openAmaya() {
    render();
    await ready();
    fireEvent.click(screen.getAllByTestId('booking-row')[0]!);
    const sheet = await screen.findByTestId('appointment-sheet');
    await within(sheet).findByTestId('appointment-details');
    return sheet;
  }

  it('shows the booking, exactly the actions the API allows, the explanation and the history newest first', async () => {
    const sheet = await openAmaya();
    expect(window.location.search).toBe(`?booking=${AMAYA.id}`);
    expect(within(sheet).getByTestId('appointment-name')).toHaveTextContent('Amaya Rajapaksa');
    expect(
      within(sheet)
        .getAllByTestId('appointment-row')
        .map((row) => row.getAttribute('data-row')),
    ).toEqual(['reference', 'mobile', 'service', 'professional', 'date', 'time', 'source']);
    expect(
      within(within(sheet).getByTestId('appointment-actions'))
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Mark arrived', 'Cancel booking']);
    expect(within(sheet).getByTestId('appointment-explanation')).toHaveTextContent(
      'You can mark a no-show once the start time has passed.',
    );
    const history = within(sheet).getAllByTestId('appointment-history-row');
    expect(history[0]).toHaveTextContent(/^Confirmed.*A team member · /);
    expect(history[1]).toHaveTextContent(/^Held.*dilani\.fernando@alphasalon\.lk · /);
    expect(sheet).not.toHaveTextContent('You ·');
  });

  it('gives focus back to the name of the row it was opened from with the pointer', async () => {
    const sheet = await openAmaya();
    fireEvent.click(within(sheet).getByTestId('appointment-close'));
    await waitFor(() => expect(screen.queryByTestId('appointment-sheet')).toBeNull());
    await waitFor(() =>
      expect(within(screen.getAllByTestId('booking-row')[0]!).getByTestId('booking-open')).toHaveFocus(),
    );
  });

  it('sends a change, says so, and shows the new state in the sheet and the list', async () => {
    const sheet = await openAmaya();
    answers.booking = () =>
      Response.json({ ...DETAIL, booking: { ...AMAYA, state: 'arrived' }, allowedActions: ['start'] });
    answers.bookings = () => Response.json(page([{ ...AMAYA, state: 'arrived' }, KAVINDI, NETHMI]));
    fireEvent.click(within(sheet).getByTestId('appointment-arrive'));
    expect(await screen.findByText('Marked arrived.')).toBeInTheDocument();
    expect(sent('booking-arrive')[0]!.body).toEqual({ id: AMAYA.id });
    await waitFor(() => expect(within(sheet).getByTestId('appointment-status')).toHaveTextContent('Arrived'));
    expect(within(sheet).getByTestId('appointment-start')).toBeInTheDocument();
    await waitFor(() =>
      expect(within(screen.getAllByTestId('booking-row')[0]!).getByTestId('booking-status')).toHaveTextContent(
        'Arrived',
      ),
    );
  });

  it('a booking changed elsewhere says so and shows its current state', async () => {
    const sheet = await openAmaya();
    answers['booking-arrive'] = problem('invalid_state');
    answers.booking = () => Response.json({ ...DETAIL, booking: { ...AMAYA, state: 'cancelled' }, allowedActions: [] });
    fireEvent.click(within(sheet).getByTestId('appointment-arrive'));
    expect(await within(sheet).findByTestId('appointment-refusal')).toHaveTextContent(
      'This booking changed. Here is its current state.',
    );
    await waitFor(() => expect(within(sheet).getByTestId('appointment-status')).toHaveTextContent('Cancelled'));
  });

  it.each([
    ['permission_denied', 403, 'You can’t make this change. Ask the salon owner.'],
    ['support_read_only', 403, 'A support session can read bookings but cannot change them.'],
    ['service_unavailable', 503, 'The booking could not be reached. Nothing was changed. Try again in a moment.'],
  ])('shows the refusal %s', async (code, status, words) => {
    const sheet = await openAmaya();
    answers['booking-arrive'] = problem(code, status);
    fireEvent.click(within(sheet).getByTestId('appointment-arrive'));
    expect(await within(sheet).findByTestId('appointment-refusal')).toHaveTextContent(words);
  });

  it('cancels only with a reason, sending the reason and note', async () => {
    const sheet = await openAmaya();
    fireEvent.click(within(sheet).getByTestId('appointment-cancel'));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveTextContent('Cancel this booking?');
    expect(dialog).toHaveTextContent(/Amaya Rajapaksa, Sat, .*2086 at 10:00\. The time opens again for others\./);
    fireEvent.click(within(dialog).getByTestId('cancel-confirm'));
    expect(within(dialog).getByTestId('cancel-reason-error')).toHaveTextContent('Choose a reason.');
    expect(sent('booking-cancel')).toEqual([]);
    await chooseOption(within(dialog).getByTestId('cancel-reason'), 'Booked by mistake');
    fireEvent.change(within(dialog).getByTestId('cancel-note'), { target: { value: 'N'.repeat(250) } });
    expect(within(dialog).getByTestId('cancel-note')).toHaveValue('N'.repeat(200));
    fireEvent.change(within(dialog).getByTestId('cancel-note'), { target: { value: ' Typed twice ' } });
    fireEvent.click(within(dialog).getByTestId('cancel-confirm'));
    await waitFor(() =>
      expect(sent('booking-cancel')[0]?.body).toEqual({
        id: AMAYA.id,
        reason: 'booked_by_mistake',
        note: 'Typed twice',
      }),
    );
    expect(await screen.findByText('Cancelled. The time is open again.')).toBeInTheDocument();
  });

  it('releases a hold, says so and closes', async () => {
    answers.booking = () => Response.json({ ...DETAIL, booking: KAVINDI, allowedActions: ['confirm', 'release'] });
    render();
    await ready();
    fireEvent.click(screen.getAllByTestId('booking-row')[1]!);
    const sheet = await screen.findByTestId('appointment-sheet');
    fireEvent.click(await within(sheet).findByTestId('appointment-release'));
    expect(await screen.findByText('Hold released. The time is open again.')).toBeInTheDocument();
    await waitFor(() => expect(window.location.search).toBe(''));
    await act(async () => undefined);
  });
});
