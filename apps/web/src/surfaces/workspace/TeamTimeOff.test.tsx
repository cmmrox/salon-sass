import { fireEvent, isInaccessible, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { expireAccessBefore } from '@test/renewal';
import { chooseOption } from '@test/select';
import { CATEGORIES, member, NIMALI, SERVICES, week, workday } from './team-fixtures';
import { TeamScreen } from './TeamScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));

const OPEN = { weekly: null, exceptions: [] };
const sickLeave = {
  id: 'leave-1',
  firstDay: '2026-11-12',
  lastDay: '2026-11-14',
  type: 'annual',
  note: 'Family trip',
  bookingsToResolve: 2,
};
const aBlock = {
  id: 'block-1',
  date: '2026-11-05',
  starts: '10:00',
  finishes: '12:00',
  label: 'Colour training',
  bookingsToResolve: 0,
};
const schedule = (overrides: Record<string, unknown> = {}) => ({
  version: 3,
  today: '2026-11-02',
  weekly: week(workday('monday', '09:00', '18:00')),
  dateChanges: [],
  leave: [],
  blockedTime: [],
  ...overrides,
});
const booking = (customerName: string) => ({
  date: '2026-11-12',
  time: '10:30',
  customerName,
  serviceName: 'Balayage',
});

type Call = { action: string; url: string; body: unknown };
let calls: Call[];
let answers: Record<string, (body: unknown) => Response | Promise<Response>>;
beforeEach(() => {
  calls = [];
  answers = {
    principal: () =>
      Response.json({ kind: 'staff', staffId: 'me', permissions: ['team.read', 'team.manage', 'team.block'] }),
    'team-members': () => Response.json({ items: [member(NIMALI, 'Nimali Perera')] }),
    'team-sign-in-accounts': () => Response.json({ items: [] }),
    'catalogue-categories': () => Response.json(CATEGORIES),
    'catalogue-services': () => Response.json(SERVICES),
    'team-schedule': () => Response.json(schedule()),
    'team-opening-hours': () => Response.json(OPEN),
    'team-leave-affected': () => Response.json({ affected: [] }),
    'team-block-affected': () => Response.json({ affected: [] }),
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
    const body = init?.body ? JSON.parse(String(init.body)) : null;
    const action = url.split('/').pop()!.split('?')[0]!;
    calls.push({ action, url, body });
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))(body);
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

type Roles = { canManage?: boolean; canBlock?: boolean; readOnly?: 'support' | 'salon' | null };
const render = ({ canManage = true, canBlock = true, readOnly = null }: Roles = {}) =>
  renderWithMessages(
    <TeamScreen
      kind="staff"
      timeZone="Asia/Colombo"
      canManage={canManage}
      canBlock={canBlock}
      canSeeAccounts={canManage}
      readOnly={readOnly}
    />,
  );
const sent = (action: string) =>
  calls.filter((call) => call.action === action).map((call) => call.body) as Record<string, unknown>[];
const problem =
  (code: string, status: number, errors: unknown[] = []) =>
  () =>
    Response.json({ code, errors }, { status });
const openTab = async (tab: 'leave' | 'blocked') => {
  fireEvent.click(await screen.findByTestId('team-card-schedule'));
  await screen.findByTestId('schedule-week');
  const trigger = screen.getByTestId(`schedule-tab-${tab}`);
  fireEvent.mouseDown(trigger, { button: 0 });
  fireEvent.focus(trigger);
  return screen.findByTestId(tab === 'leave' ? 'schedule-leave' : 'schedule-blocks');
};
const pick = async (control: string, date: string) => {
  fireEvent.click(screen.getByTestId(control));
  const calendar = await screen.findByTestId(`${control}-calendar`);
  const [, , day] = date.split('-');
  // The first accessible, enabled day button that says the day. The buttons saying it are found by their text
  // first: a role query over the whole month worked out the style of every day, which was most of a test's time.
  const button = within(calendar)
    .queryAllByText(String(Number(day)))
    .map((text) => text.closest('button'))
    .find(
      (each) =>
        each !== null &&
        calendar.contains(each) &&
        each.textContent === String(Number(day)) &&
        !each.hasAttribute('disabled') &&
        !isInaccessible(each),
    );
  fireEvent.click(button!);
};
const leaveFilled = async (first = '2026-11-12', last = '2026-11-14') => {
  fireEvent.click(screen.getByTestId('leave-add'));
  await screen.findByTestId('leave-form');
  await pick('leave-first', first);
  await pick('leave-last', last);
};
const blockFilled = async (label = 'Training') => {
  fireEvent.click(screen.getByTestId('block-add'));
  await screen.findByTestId('block-form');
  await pick('block-date', '2026-11-12');
  fireEvent.change(screen.getByTestId('block-label'), { target: { value: label } });
};
const added = {
  leave: { id: 'new', firstDay: '2026-11-12', lastDay: '2026-11-14', type: 'annual', note: null, bookingsToResolve: 0 },
  affected: [],
};
const blocked = {
  block: { id: 'new', date: '2026-11-12', starts: '10:00', finishes: '11:00', label: 'Training', bookingsToResolve: 0 },
  affected: [],
};
const toastSays = (text: string) => screen.findByText(text);

describe('the Leave tab (F06-S03 AC-1 to AC-3)', () => {
  it('says the banner and that no leave is planned', async () => {
    render();
    await openTab('leave');

    expect(screen.getByTestId('leave-banner').textContent).toBe(
      'Customers can’t book this person on leave days. Existing bookings are kept and flagged, never cancelled for you. For part of a day, use Blocked time.',
    );
    expect(screen.getByTestId('leave-empty').textContent).toBe('No leave planned.');
  });

  it('lists days, type, note and the bookings to resolve', async () => {
    answers['team-schedule'] = () => Response.json(schedule({ leave: [sickLeave] }));
    render();
    await openTab('leave');

    const row = screen.getByTestId('leave-row');
    expect(within(row).getByTestId('leave-row-days').textContent).toBe('Thu, Nov 12 to Sat, Nov 14');
    expect(within(row).getByTestId('leave-row-type').textContent).toBe('Annual leave');
    expect(within(row).getByTestId('leave-row-note').textContent).toBe('Family trip');
    expect(within(row).getByTestId('leave-row-resolve').textContent).toBe('2 bookings to resolve');
  });

  it('says one day alone and a single booking, and nothing when none is to resolve', async () => {
    answers['team-schedule'] = () =>
      Response.json(
        schedule({
          leave: [
            { ...sickLeave, id: 'a', firstDay: '2026-11-12', lastDay: '2026-11-12', bookingsToResolve: 1 },
            { ...sickLeave, id: 'b', firstDay: '2026-11-20', lastDay: '2026-11-20', bookingsToResolve: 0, note: null },
          ],
        }),
      );
    render();
    await openTab('leave');

    const [one, two] = screen.getAllByTestId('leave-row');
    expect(within(one!).getByTestId('leave-row-days').textContent).toBe('Thu, Nov 12');
    expect(within(one!).getByTestId('leave-row-resolve').textContent).toBe('1 booking to resolve');
    expect(within(two!).queryByTestId('leave-row-resolve')).toBeNull();
    expect(within(two!).queryByTestId('leave-row-note')).toBeNull();
  });

  it('writes the year on both ends of a period reaching into another year, and on its Remove (AC-16)', async () => {
    answers['team-schedule'] = () =>
      Response.json(
        schedule({
          leave: [
            { ...sickLeave, id: 'a', firstDay: '2026-11-12', lastDay: '2026-11-14' },
            { ...sickLeave, id: 'b', firstDay: '2026-12-30', lastDay: '2027-01-02' },
            { ...sickLeave, id: 'c', firstDay: '2027-01-05', lastDay: '2027-01-05' },
          ],
        }),
      );
    render();
    await openTab('leave');

    const [thisYear, across, nextYear] = screen.getAllByTestId('leave-row');
    expect(within(thisYear!).getByTestId('leave-remove')).toHaveAccessibleName(
      'Remove leave, Thu, Nov 12 to Sat, Nov 14',
    );
    expect(within(across!).getByTestId('leave-row-days').textContent).toBe('Wed, Dec 30, 2026 to Sat, Jan 2, 2027');
    expect(within(nextYear!).getByTestId('leave-row-days').textContent).toBe('Tue, Jan 5, 2027');
  });

  it('for a role without team.manage shows only the days, with nothing to change', async () => {
    answers['team-schedule'] = () =>
      Response.json(
        schedule({
          leave: [{ id: 'leave-1', firstDay: '2026-11-12', lastDay: '2026-11-14', bookingsToResolve: 0 }],
        }),
      );
    render({ canManage: false, canBlock: true });
    await openTab('leave');

    const row = screen.getByTestId('leave-row');
    expect(within(row).getByTestId('leave-row-days').textContent).toBe('Thu, Nov 12 to Sat, Nov 14');
    expect(within(row).queryByTestId('leave-row-type')).toBeNull();
    expect(within(row).queryByTestId('leave-row-note')).toBeNull();
    expect(within(row).queryByTestId('leave-remove')).toBeNull();
    expect(screen.queryByTestId('leave-add')).toBeNull();
  });

  it('adds leave and says so, and the list and the team follow', async () => {
    answers['team-leave-add'] = () => Response.json(added, { status: 201 });
    render();
    await openTab('leave');
    await leaveFilled();
    await chooseOption(screen.getByTestId('leave-type'), 'Sick leave');
    fireEvent.change(screen.getByTestId('leave-note'), { target: { value: ' Mine ' } });
    const before = calls.filter((call) => call.action === 'team-members').length;

    fireEvent.click(screen.getByTestId('leave-save'));

    await toastSays('Leave added.');
    expect(sent('team-leave-add')).toEqual([
      { id: NIMALI, firstDay: '2026-11-12', lastDay: '2026-11-14', type: 'sick', note: 'Mine' },
    ]);
    expect(screen.queryByTestId('leave-form')).toBeNull();
    expect(screen.getByTestId('leave-row-days').textContent).toBe('Thu, Nov 12 to Sat, Nov 14');
    await waitFor(() => expect(calls.filter((call) => call.action === 'team-members').length).toBeGreaterThan(before));
  });

  it.each([
    [1, 'Leave added. The existing booking was kept and flagged.'],
    [2, 'Leave added. Existing bookings were kept and flagged.'],
  ])('says %i kept bookings', async (count, words) => {
    answers['team-leave-add'] = () =>
      Response.json(
        { ...added, affected: Array.from({ length: count }, (_, i) => booking(`Customer ${i}`)) },
        { status: 201 },
      );
    render();
    await openTab('leave');
    await leaveFilled();

    fireEvent.click(screen.getByTestId('leave-save'));

    await toastSays(words);
  });

  it('asks for the days without asking the API', async () => {
    render();
    await openTab('leave');
    fireEvent.click(screen.getByTestId('leave-add'));
    await screen.findByTestId('leave-form');

    fireEvent.click(screen.getByTestId('leave-save'));
    expect((await screen.findByTestId('leave-problem')).textContent).toContain('Choose the first and last day.');
    expect(sent('team-leave-add')).toHaveLength(0);
  });

  const dayButton = (calendar: HTMLElement, name: string) =>
    calendar.querySelector<HTMLButtonElement>(`button[aria-label*="${name}"]`)!;
  /** The first day, in the month after today's. */
  const pickFirstNextMonth = async (name: string) => {
    fireEvent.click(screen.getByTestId('leave-first'));
    const calendar = await screen.findByTestId('leave-first-calendar');
    fireEvent.click(within(calendar).getByRole('button', { name: /next/i }));
    await waitFor(() => expect(calendar.textContent).toContain('December 2026'));
    fireEvent.click(dayButton(calendar, name));
    await waitFor(() => expect(screen.queryByTestId('leave-first-calendar')).toBeNull());
  };

  it('sets the last day to the first, so one day of leave takes one choice (AC-15)', async () => {
    // Unanswered, so that no toast outlives this test: what was sent is the point.
    answers['team-leave-add'] = problem('service_unavailable', 503);
    render();
    await openTab('leave');
    fireEvent.click(screen.getByTestId('leave-add'));
    await screen.findByTestId('leave-form');

    await pick('leave-first', '2026-11-20');

    expect(screen.getByTestId('leave-last').textContent).toBe('Fri, Nov 20, 2026');
    fireEvent.click(screen.getByTestId('leave-save'));
    await waitFor(() => expect(sent('team-leave-add')).toHaveLength(1));
    expect(sent('team-leave-add')[0]).toMatchObject({ firstDay: '2026-11-20', lastDay: '2026-11-20' });
  });

  it("opens the last day on the first day's month and offers no day before it (AC-15)", async () => {
    render();
    await openTab('leave');
    fireEvent.click(screen.getByTestId('leave-add'));
    await screen.findByTestId('leave-form');
    await pickFirstNextMonth('December 10, 2026');
    expect(screen.getByTestId('leave-last').textContent).toBe('Thu, Dec 10, 2026');

    fireEvent.click(screen.getByTestId('leave-last'));
    const calendar = await screen.findByTestId('leave-last-calendar');
    expect(calendar.textContent).toContain('December 2026');
    expect(dayButton(calendar, 'December 9, 2026')).toBeDisabled();
    expect(dayButton(calendar, 'December 10, 2026')).toBeEnabled();
    expect(dayButton(calendar, 'December 11, 2026')).toBeEnabled();
    expect(within(calendar).getByRole('button', { name: /previous/i })).toHaveAttribute('aria-disabled', 'true');
  });

  it('moves an earlier last day up to a new first day, and keeps a later one (AC-15)', async () => {
    render();
    await openTab('leave');
    await leaveFilled('2026-11-12', '2026-11-14');

    await pick('leave-first', '2026-11-13');
    expect(screen.getByTestId('leave-last').textContent).toBe('Sat, Nov 14, 2026');

    await pick('leave-first', '2026-11-20');
    expect(screen.getByTestId('leave-last').textContent).toBe('Fri, Nov 20, 2026');
    expect(screen.getByTestId('leave-first').textContent).toBe('Fri, Nov 20, 2026');
  });

  it('says an overlap, the limit and the API-named rules in the sheet, and keeps the form', async () => {
    render();
    await openTab('leave');
    await leaveFilled();

    answers['team-leave-add'] = problem('conflict', 409);
    fireEvent.click(screen.getByTestId('leave-save'));
    expect((await screen.findByTestId('leave-problem')).textContent).toContain('This overlaps leave already planned.');

    answers['team-leave-add'] = problem('limit_reached', 409);
    fireEvent.click(screen.getByTestId('leave-save'));
    await waitFor(() =>
      expect(screen.getByTestId('leave-problem').textContent).toContain('This person has 100 upcoming leaves.'),
    );

    answers['team-leave-add'] = problem('bad_request', 400, [{ field: 'firstDay', detail: 'x' }]);
    fireEvent.click(screen.getByTestId('leave-save'));
    await waitFor(() =>
      expect(screen.getByTestId('leave-problem').textContent).toContain('Choose days from today to a year ahead.'),
    );

    answers['team-leave-add'] = problem('bad_request', 400, [{ field: 'note', detail: 'x' }]);
    fireEvent.click(screen.getByTestId('leave-save'));
    await waitFor(() =>
      expect(screen.getByTestId('leave-problem').textContent).toContain('The note must be 60 characters or fewer.'),
    );
    expect(screen.getByTestId('leave-form')).toBeTruthy();
  });

  it('asks first before removing, and removes only on confirming', async () => {
    answers['team-schedule'] = () => Response.json(schedule({ leave: [sickLeave] }));
    answers['team-leave-remove'] = () => new Response(null, { status: 200 });
    answers['team-leave-remove'] = () => Response.json({ ok: true });
    render();
    await openTab('leave');

    fireEvent.click(screen.getByTestId('leave-remove'));
    const dialog = await screen.findByTestId('leave-remove-dialog');
    expect(within(dialog).getByText('Remove this leave?')).toBeTruthy();
    expect(within(dialog).getByText('They become bookable on these days again.')).toBeTruthy();
    fireEvent.click(screen.getByTestId('leave-remove-cancel'));
    await waitFor(() => expect(screen.queryByTestId('leave-remove-dialog')).toBeNull());
    expect(sent('team-leave-remove')).toHaveLength(0);

    fireEvent.click(screen.getByTestId('leave-remove'));
    fireEvent.click(await screen.findByTestId('leave-remove-confirm'));

    await toastSays('Leave removed.');
    expect(sent('team-leave-remove')).toEqual([{ id: NIMALI, leaveId: 'leave-1' }]);
    expect(screen.getByTestId('leave-empty')).toBeTruthy();
  });

  it('says someone else changed it when removing what is gone', async () => {
    answers['team-schedule'] = () => Response.json(schedule({ leave: [sickLeave] }));
    answers['team-leave-remove'] = problem('not_found', 404);
    render();
    await openTab('leave');

    fireEvent.click(screen.getByTestId('leave-remove'));
    fireEvent.click(await screen.findByTestId('leave-remove-confirm'));

    expect(await screen.findByTestId('schedule-conflict')).toBeTruthy();
  });

  it('asks before leaving a started form, not one left empty', async () => {
    render();
    await openTab('leave');
    fireEvent.click(screen.getByTestId('leave-add'));
    await screen.findByTestId('leave-form');

    fireEvent.click(screen.getByTestId('schedule-close'));
    await waitFor(() => expect(screen.queryByTestId('schedule-sheet')).toBeNull());
    expect(screen.queryByTestId('team-leave-dialog')).toBeNull();

    await openTab('leave');
    fireEvent.click(screen.getByTestId('leave-add'));
    await screen.findByTestId('leave-form');
    fireEvent.change(screen.getByTestId('leave-note'), { target: { value: 'Started' } });
    fireEvent.click(screen.getByTestId('schedule-close'));

    expect(await screen.findByRole('alertdialog')).toBeTruthy();
    expect(screen.getByTestId('schedule-sheet')).toBeTruthy();
  });
});

describe('the Blocked time tab (F06-S03 AC-4 to AC-6)', () => {
  it('says the banner and that nothing is blocked', async () => {
    render();
    await openTab('blocked');

    expect(screen.getByTestId('block-banner').textContent).toBe(
      'Blocked time is part of a day when this person can’t be booked, like training or an errand. It shows on the calendar with its label.',
    );
    expect(screen.getByTestId('block-empty').textContent).toBe('No blocked time.');
  });

  it('lists date, times on the clock the product uses, and label', async () => {
    answers['team-schedule'] = () => Response.json(schedule({ blockedTime: [aBlock] }));
    render();
    await openTab('blocked');

    expect(screen.getByTestId('block-row-when').textContent).toBe('Thu, Nov 5, 10:00 – 12:00');
    expect(screen.getByTestId('block-row-label').textContent).toBe('Colour training');
  });

  it('writes the year of a block in another year (AC-16)', async () => {
    answers['team-schedule'] = () =>
      Response.json(schedule({ blockedTime: [aBlock, { ...aBlock, id: 'block-2', date: '2027-01-05' }] }));
    render();
    await openTab('blocked');

    const [thisYear, nextYear] = screen.getAllByTestId('block-row-when');
    expect(thisYear!.textContent).toBe('Thu, Nov 5, 10:00 – 12:00');
    expect(nextYear!.textContent).toBe('Tue, Jan 5, 2027, 10:00 – 12:00');
    expect(screen.getAllByTestId('block-remove')[0]).toHaveAccessibleName(
      'Remove blocked time, Thu, Nov 5, 10:00 – 12:00',
    );
  });

  it('is read-only for a role without team.block', async () => {
    answers['team-schedule'] = () => Response.json(schedule({ blockedTime: [aBlock] }));
    render({ canManage: false, canBlock: false });
    await openTab('blocked');

    expect(screen.getByTestId('block-row-label').textContent).toBe('Colour training');
    expect(screen.queryByTestId('block-add')).toBeNull();
    expect(screen.queryByTestId('block-remove')).toBeNull();
  });

  it('lets a Receptionist block time without team.manage', async () => {
    answers['team-block-add'] = () => Response.json(blocked, { status: 201 });
    answers['team-schedule'] = () => Response.json(schedule({ leave: [sickLeave] }));
    render({ canManage: false, canBlock: true });
    await openTab('blocked');
    await blockFilled();

    fireEvent.click(screen.getByTestId('block-save'));

    await toastSays('Time blocked.');
    expect(sent('team-block-add')).toEqual([
      { id: NIMALI, date: '2026-11-12', starts: '10:00', finishes: '11:00', label: 'Training' },
    ]);
    expect(screen.getByTestId('block-row-label').textContent).toBe('Training');
  });

  it.each([
    [1, 'Time blocked. The existing booking was kept and flagged.'],
    [3, 'Time blocked. 3 existing bookings were kept and flagged.'],
  ])('says %i kept bookings', async (count, words) => {
    answers['team-block-add'] = () =>
      Response.json(
        { ...blocked, affected: Array.from({ length: count }, (_, i) => booking(`Customer ${i}`)) },
        { status: 201 },
      );
    render();
    await openTab('blocked');
    await blockFilled();

    fireEvent.click(screen.getByTestId('block-save'));

    await toastSays(words);
  });

  it('asks for a date, a finish after the start and a label, without asking the API', async () => {
    render();
    await openTab('blocked');
    fireEvent.click(screen.getByTestId('block-add'));
    await screen.findByTestId('block-form');

    fireEvent.click(screen.getByTestId('block-save'));
    expect((await screen.findByTestId('block-problem')).textContent).toContain('Choose a date.');

    await pick('block-date', '2026-11-12');
    await chooseOption(screen.getByTestId('block-starts'), '12:00');
    fireEvent.click(screen.getByTestId('block-save'));
    await waitFor(() =>
      expect(screen.getByTestId('block-problem').textContent).toContain('The end must be after the start.'),
    );

    await chooseOption(screen.getByTestId('block-starts'), '10:00');
    fireEvent.click(screen.getByTestId('block-save'));
    await waitFor(() =>
      expect(screen.getByTestId('block-problem').textContent).toContain('Add a label, like Training.'),
    );
    expect(sent('team-block-add')).toHaveLength(0);
  });

  it('says an overlap and the limit', async () => {
    render();
    await openTab('blocked');
    await blockFilled();

    answers['team-block-add'] = problem('conflict', 409);
    fireEvent.click(screen.getByTestId('block-save'));
    expect((await screen.findByTestId('block-problem')).textContent).toContain('This overlaps time already blocked.');

    answers['team-block-add'] = problem('limit_reached', 409);
    fireEvent.click(screen.getByTestId('block-save'));
    await waitFor(() =>
      expect(screen.getByTestId('block-problem').textContent).toContain('This person has 200 upcoming blocks.'),
    );
  });

  it('removes at once and says so', async () => {
    answers['team-schedule'] = () => Response.json(schedule({ blockedTime: [aBlock] }));
    answers['team-block-remove'] = () => Response.json({ ok: true });
    render();
    await openTab('blocked');

    fireEvent.click(screen.getByTestId('block-remove'));

    await toastSays('Blocked time removed.');
    expect(sent('team-block-remove')).toEqual([{ id: NIMALI, blockId: 'block-1' }]);
    expect(screen.getByTestId('block-empty')).toBeTruthy();
  });
});

describe('the bookings a period would affect (F06-S03 AC-8)', () => {
  const words = () => screen.getByTestId('period-affected-text').textContent;

  it('asks with both days chosen, and says them with date, time, customer and service', async () => {
    answers['team-leave-affected'] = () => Response.json({ affected: [booking('Kasun'), booking('Amara')] });
    render();
    await openTab('leave');
    await leaveFilled();

    await screen.findByTestId('period-affected');
    expect(words()).toBe('2 existing bookings in this time. They are kept and flagged for you. Nothing is cancelled.');
    const rows = screen.getAllByTestId('period-affected-booking');
    expect(rows[0]!.textContent).toContain('Kasun');
    expect(rows[0]!.textContent).toContain('Balayage');
    expect(rows[0]!.textContent).toContain('10:30');
    const asked = calls.filter((call) => call.action === 'team-leave-affected').at(-1)!.url;
    expect(asked).toContain(`memberId=${NIMALI}`);
    expect(asked).toContain('firstDay=2026-11-12');
    expect(asked).toContain('lastDay=2026-11-14');
    expect(screen.getByTestId('leave-save')).toHaveProperty('disabled', false);
  });

  it('says one booking in the singular, for a block, and asks again as its time changes', async () => {
    answers['team-block-affected'] = () => Response.json({ affected: [booking('Kasun')] });
    render();
    await openTab('blocked');
    fireEvent.click(screen.getByTestId('block-add'));
    await screen.findByTestId('block-form');
    expect(calls.filter((call) => call.action === 'team-block-affected')).toHaveLength(0);

    await pick('block-date', '2026-11-12');
    await screen.findByTestId('period-affected');
    expect(words()).toBe('1 existing booking in this time. It is kept and flagged for you. Nothing is cancelled.');

    const asked = () => calls.filter((call) => call.action === 'team-block-affected').length;
    const before = asked();
    fireEvent.change(screen.getByTestId('block-label'), { target: { value: 'Training' } });
    expect(asked(), 'the label is no part of the period').toBe(before);
    await chooseOption(screen.getByTestId('block-finishes'), '12:00');
    await waitFor(() => expect(asked()).toBe(before + 1));
  });

  it('asks nothing before the days are chosen, then asks for the one day the first day sets', async () => {
    render();
    await openTab('leave');
    fireEvent.click(screen.getByTestId('leave-add'));
    await screen.findByTestId('leave-form');
    expect(calls.filter((call) => call.action === 'team-leave-affected')).toHaveLength(0);

    await pick('leave-first', '2026-11-12');

    await waitFor(() => expect(calls.filter((call) => call.action === 'team-leave-affected')).toHaveLength(1));
    const asked = calls.find((call) => call.action === 'team-leave-affected')!.url;
    expect(asked).toContain('firstDay=2026-11-12');
    expect(asked).toContain('lastDay=2026-11-12');
    expect(screen.queryByTestId('period-affected')).toBeNull();
  });

  it('does not stop a save when the question could not be answered', async () => {
    answers['team-leave-affected'] = problem('service_unavailable', 503);
    answers['team-leave-add'] = () => Response.json(added, { status: 201 });
    render();
    await openTab('leave');
    await leaveFilled();

    expect((await screen.findByTestId('period-unavailable')).textContent).toContain('You can still save');
    fireEvent.click(screen.getByTestId('leave-save'));

    await toastSays('Leave added.');
  });
});

describe('the card’s leave badge (F06-S03 AC-9, AC-16)', () => {
  const nextLeave = { firstDay: '2026-11-12', lastDay: '2026-11-14' };
  // The badge's year follows the salon's own date (Asia/Colombo, UTC+05:30), on the browser's clock.
  const at = (instant: string) => vi.setSystemTime(new Date(instant));
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    at('2026-11-09T06:00:00Z');
  });
  afterEach(() => vi.useRealTimers());
  const withLeave = (leave: unknown) => () =>
    Response.json({ items: [member(NIMALI, 'Nimali Perera', { nextLeave: leave })] });

  it('shows the type and days with team.manage', async () => {
    answers['team-members'] = withLeave({ ...nextLeave, type: 'annual' });
    render();

    expect((await screen.findByTestId('team-card-leave')).textContent).toBe('Annual leave, Thu, Nov 12 to Sat, Nov 14');
  });

  it('shows Sick leave only when the API sent it', async () => {
    answers['team-members'] = withLeave({ ...nextLeave, type: 'sick' });
    render();

    expect((await screen.findByTestId('team-card-leave')).textContent).toBe('Sick leave, Thu, Nov 12 to Sat, Nov 14');
  });

  it('says only On leave without the type', async () => {
    answers['team-members'] = withLeave(nextLeave);
    render({ canManage: false, canBlock: true });

    expect((await screen.findByTestId('team-card-leave')).textContent).toBe('On leave, Thu, Nov 12 to Sat, Nov 14');
  });

  it('says one day alone', async () => {
    answers['team-members'] = withLeave({ firstDay: '2026-11-12', lastDay: '2026-11-12' });
    render({ canManage: false, canBlock: false });

    expect((await screen.findByTestId('team-card-leave')).textContent).toBe('On leave, Thu, Nov 12');
  });

  it('writes the year on both ends when either is in another year', async () => {
    answers['team-members'] = withLeave({ firstDay: '2026-12-30', lastDay: '2027-01-02', type: 'annual' });
    render();

    expect((await screen.findByTestId('team-card-leave')).textContent).toBe(
      'Annual leave, Wed, Dec 30, 2026 to Sat, Jan 2, 2027',
    );
  });

  it.each([
    // 23:30 on 31 December in Colombo, though 18:00 in UTC: still 2026 there.
    ['2026-12-31T18:00:00Z', 'On leave, Tue, Jan 5, 2027'],
    // 01:30 on 1 January in Colombo, though still 31 December in UTC: 2027 there.
    ['2026-12-31T20:00:00Z', 'On leave, Tue, Jan 5'],
  ])('decides the year by the salon’s own date at %s', async (instant, words) => {
    at(instant);
    answers['team-members'] = withLeave({ firstDay: '2027-01-05', lastDay: '2027-01-05' });
    render({ canManage: false, canBlock: false });

    expect((await screen.findByTestId('team-card-leave')).textContent).toBe(words);
  });

  it('has no badge with none', async () => {
    render();

    await screen.findByTestId('team-card-schedule');
    expect(screen.queryByTestId('team-card-leave')).toBeNull();
  });
});

describe('read-only and refused (F06-S03 AC-10, AC-13)', () => {
  it.each(['support', 'salon'] as const)('offers no change in a %s view', async (readOnly) => {
    answers['team-schedule'] = () => Response.json(schedule({ leave: [sickLeave], blockedTime: [aBlock] }));
    render({ canManage: false, canBlock: false, readOnly });
    await openTab('leave');
    expect(screen.queryByTestId('leave-add')).toBeNull();
    expect(screen.queryByTestId('leave-remove')).toBeNull();
    expect(screen.getByTestId('schedule-read-only')).toBeTruthy();

    const blockedTab = screen.getByTestId('schedule-tab-blocked');
    fireEvent.mouseDown(blockedTab, { button: 0 });
    fireEvent.focus(blockedTab);
    await screen.findByTestId('schedule-blocks');
    expect(screen.queryByTestId('block-add')).toBeNull();
    expect(screen.queryByTestId('block-remove')).toBeNull();
  });

  it('says a refused change once and offers no more changes', async () => {
    answers['team-schedule'] = () => Response.json(schedule({ blockedTime: [aBlock] }));
    answers['team-block-remove'] = problem('permission_denied', 403);
    render();
    await openTab('blocked');

    fireEvent.click(screen.getByTestId('block-remove'));

    expect(await screen.findByTestId('schedule-refused')).toBeTruthy();
    await waitFor(() => expect(screen.queryByTestId('block-remove')).toBeNull());
    expect(screen.queryByTestId('block-add')).toBeNull();
  });

  it('keeps what was entered when the API cannot be reached', async () => {
    answers['team-leave-add'] = problem('service_unavailable', 503);
    render();
    await openTab('leave');
    await leaveFilled();
    fireEvent.change(screen.getByTestId('leave-note'), { target: { value: 'Mine' } });

    fireEvent.click(screen.getByTestId('leave-save'));

    expect(await screen.findByTestId('schedule-unavailable')).toBeTruthy();
    expect((screen.getByTestId('leave-note') as HTMLInputElement).value).toBe('Mine');
  });

  it('shows the loading state while the schedule is read, and the retry when it cannot be', async () => {
    answers['team-schedule'] = problem('service_unavailable', 503);
    render();

    fireEvent.click(await screen.findByTestId('team-card-schedule'));

    expect(await screen.findByTestId('schedule-retry')).toBeTruthy();
  });
});

describe('an idle session (BUG-F06-001)', () => {
  it('renews an access credential that lapsed and says the bookings leave would affect, not the sign-in form', async () => {
    answers['team-leave-affected'] = () => Response.json({ affected: [booking('Kasun')] });
    render();
    await openTab('leave');
    const renewals = expireAccessBefore(answers, 'team-leave-affected');
    await leaveFilled();
    expect(await screen.findByTestId('period-affected')).toBeInTheDocument();
    expect(renewals()).toBe(1);
  });
});
