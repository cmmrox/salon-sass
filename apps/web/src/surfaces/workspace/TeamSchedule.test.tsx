import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { expireAccessBefore } from '@test/renewal';
import { chooseOption } from '@test/select';
import { CATEGORIES, member, NIMALI, SERVICES, week, workday } from './team-fixtures';
import { TeamScreen } from './TeamScreen';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));

const lunch = { starts: '13:00', finishes: '14:00' };
const closedSalon = { weekly: null, exceptions: [] };
const openSalon = {
  weekly: ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'].map((day) => ({
    day,
    open: day !== 'sunday',
    opens: day === 'sunday' ? null : '09:00',
    closes: day === 'sunday' ? null : '18:00',
  })),
  exceptions: [],
};
const change = (date: string, overrides: Record<string, unknown> = {}) => ({
  id: `change-${date}`,
  date,
  working: false,
  starts: null,
  finishes: null,
  breaks: [],
  note: null,
  ...overrides,
});
const schedule = (overrides: Record<string, unknown> = {}) => ({
  version: 3,
  today: '2026-11-02',
  weekly: week(workday('monday', '09:00', '18:00', [lunch]), workday('tuesday', '09:00', '18:00')),
  dateChanges: [],
  ...overrides,
});

type Call = { action: string; url: string; body: unknown };
let calls: Call[];
let answers: Record<string, (body: unknown) => Response | Promise<Response>>;
beforeEach(() => {
  push.mockReset();
  calls = [];
  answers = {
    principal: () => Response.json({ kind: 'staff', staffId: 'me', permissions: ['team.read', 'team.manage'] }),
    'team-members': () => Response.json({ items: [member(NIMALI, 'Nimali Perera', { weekly: schedule().weekly })] }),
    'team-sign-in-accounts': () => Response.json({ items: [] }),
    'catalogue-categories': () => Response.json(CATEGORIES),
    'catalogue-services': () => Response.json(SERVICES),
    'team-schedule': () => Response.json(schedule()),
    'team-opening-hours': () => Response.json(openSalon),
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

const render = (canManage = true, readOnly: 'support' | 'salon' | null = null) =>
  renderWithMessages(
    <TeamScreen
      kind="staff"
      timeZone="Asia/Colombo"
      canManage={canManage}
      canBlock={canManage}
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
const open = async () => {
  fireEvent.click(await screen.findByTestId('team-card-schedule'));
  return screen.findByTestId('schedule-week');
};
const openChanges = async () => {
  await open();
  const tab = screen.getByTestId('schedule-tab-changes');
  fireEvent.mouseDown(tab, { button: 0 });
  fireEvent.focus(tab);
  return screen.findByTestId('schedule-changes');
};
const said = () => screen.getAllByTestId('schedule-problem').map((each) => each.textContent);

describe('the card (F06-S02 AC-12, AC-13)', () => {
  const card = async (weekly: unknown) => {
    answers['team-members'] = () => Response.json({ items: [member(NIMALI, 'Nimali', { weekly })] });
    render();
    return (await screen.findByTestId('team-card-hours')).textContent;
  };
  const days = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

  it('sums up a run of days with one break', async () => {
    expect(await card(week(...days.map((day) => workday(day, '09:00', '18:00', [lunch]))))).toBe(
      'Mon–Sat, 09:00 – 18:00 · break 13:00',
    );
  });

  it('counts several breaks', async () => {
    expect(
      await card(
        week(...days.map((day) => workday(day, '09:00', '18:00', [lunch, { starts: '15:00', finishes: '15:15' }]))),
      ),
    ).toBe('Mon–Sat, 09:00 – 18:00 · 2 breaks');
  });

  it('says a single day with no break', async () => {
    expect(await card(week(workday('monday', '09:00', '18:00')))).toBe('Mon, 09:00 – 18:00');
  });

  it('says it varies', async () => {
    expect(await card(week(workday('monday', '09:00', '18:00'), workday('tuesday', '10:00', '18:00')))).toBe(
      'Varies by day',
    );
  });

  it('says no working days', async () => {
    expect(await card(week())).toBe('No working days');
  });

  it('offers Schedule beside Edit profile, and alone to a role that only reads', async () => {
    const { unmount } = render();
    const schedules = await screen.findByTestId('team-card-schedule');
    expect(schedules).toHaveAccessibleName('Schedule of Nimali Perera');
    expect(screen.getByTestId('team-card-edit')).toBeVisible();
    unmount();

    render(false);
    expect(await screen.findByTestId('team-card-schedule')).toBeVisible();
    expect(screen.queryByTestId('team-card-edit')).toBeNull();
  });
});

describe('the sheet’s header and states (AC-16)', () => {
  it('is headed with the person’s name, shows a skeleton while it reads, and opens on Weekly hours', async () => {
    let answer!: (response: Response) => void;
    answers['team-schedule'] = () => new Promise((resolve) => (answer = resolve));
    render();
    fireEvent.click(await screen.findByTestId('team-card-schedule'));

    expect(await screen.findByTestId('schedule-loading')).toBeVisible();
    expect(screen.getByTestId('schedule-name')).toHaveTextContent('Nimali Perera');
    await waitFor(() => expect(answer).toBeDefined());
    answer(Response.json(schedule()));

    expect(await screen.findByTestId('schedule-week-banner')).toHaveTextContent(
      'Customers can book this person inside these hours while the salon is open.',
    );
    expect(calls.find((call) => call.action === 'team-schedule')?.url).toContain(`memberId=${NIMALI}`);
  });

  it('says it could not be reached, and tries again', async () => {
    answers['team-schedule'] = problem('service_unavailable', 503);
    render();
    fireEvent.click(await screen.findByTestId('team-card-schedule'));

    expect(await screen.findByTestId('schedule-unavailable')).toBeVisible();

    answers['team-schedule'] = () => Response.json(schedule());
    fireEvent.click(screen.getByTestId('schedule-retry'));
    expect(await screen.findByTestId('schedule-week')).toBeVisible();
    expect(screen.queryByTestId('schedule-unavailable')).toBeNull();
  });

  it('keeps the edits when a save cannot reach the API', async () => {
    render();
    await open();
    await chooseOption(screen.getByTestId('schedule-monday-starts'), '10:00');
    answers['team-week-save'] = problem('service_unavailable', 503);

    fireEvent.click(screen.getByTestId('schedule-save'));

    expect(await screen.findByTestId('schedule-unavailable')).toBeVisible();
    expect(screen.getByTestId('schedule-monday-starts')).toHaveTextContent('10:00');
  });

  it('says a refused change, and stops offering changes', async () => {
    render();
    await open();
    answers['team-week-save'] = problem('permission_denied', 403);

    await chooseOption(screen.getByTestId('schedule-monday-starts'), '10:00');
    fireEvent.click(screen.getByTestId('schedule-save'));

    expect(await screen.findByTestId('schedule-refused')).toBeVisible();
    expect(screen.queryByTestId('schedule-save')).toBeNull();
  });
});

describe('weekly hours (AC-2, AC-3)', () => {
  it('names each control by its weekday and break', async () => {
    render();
    await open();

    expect(screen.getByTestId('schedule-monday-working')).toHaveAccessibleName('Monday');
    expect(screen.getByTestId('schedule-monday-starts')).toHaveAccessibleName('Monday starts');
    expect(screen.getByTestId('schedule-monday-finishes')).toHaveAccessibleName('Monday finishes');
    expect(screen.getByTestId('schedule-monday-break-0-starts')).toHaveAccessibleName('Monday break 1 starts');
    expect(screen.getByTestId('schedule-monday-break-0-finishes')).toHaveAccessibleName('Monday break 1 ends');
    expect(screen.getByTestId('schedule-monday-break-0-remove')).toHaveAccessibleName('Remove Monday break 1');
    expect(screen.getByTestId('schedule-monday-add-break')).toHaveAccessibleName('+ Add break on Monday');
    expect(screen.getByTestId('schedule-wednesday-off')).toHaveTextContent('Not working');
  });

  it('saves the whole week from the version it read, says so, closes, and reads the team again', async () => {
    render();
    await open();
    await chooseOption(screen.getByTestId('schedule-monday-finishes'), '17:00');
    fireEvent.click(screen.getByTestId('schedule-wednesday-working'));
    answers['team-week-save'] = () => Response.json({ version: 4, weekly: week(), affected: [] });
    const before = calls.filter((call) => call.action === 'team-members').length;

    fireEvent.click(screen.getByTestId('schedule-save'));

    expect(await screen.findByText('Hours saved. New bookings follow them. Existing bookings are kept.')).toBeVisible();
    await waitFor(() => expect(screen.queryByTestId('schedule-sheet')).toBeNull());
    const body = sent('team-week-save')[0]!;
    expect(body).toMatchObject({ id: NIMALI, version: 3 });
    const weekly = body.weekly as Record<string, unknown>[];
    expect(weekly).toHaveLength(7);
    expect(weekly[0]).toEqual({ day: 'monday', working: true, starts: '09:00', finishes: '17:00', breaks: [lunch] });
    expect(weekly[2]).toEqual({ day: 'wednesday', working: true, starts: '09:00', finishes: '18:00', breaks: [] });
    expect(weekly[6]).toEqual({ day: 'sunday', working: false, starts: null, finishes: null, breaks: [] });
    await waitFor(() => expect(calls.filter((call) => call.action === 'team-members').length).toBe(before + 1));
  });

  it('a finish at or before the start is said on that weekday and stops Save hours while it lasts', async () => {
    render();
    await open();

    await chooseOption(screen.getByTestId('schedule-tuesday-finishes'), '09:00');

    expect(said()).toEqual(['Tue: the finish must be after the start.']);
    expect(screen.getByTestId('schedule-save')).toBeDisabled();

    await chooseOption(screen.getByTestId('schedule-tuesday-finishes'), '09:15');

    expect(screen.queryByTestId('schedule-problem')).toBeNull();
    expect(screen.getByTestId('schedule-save')).toBeEnabled();
  });

  it('says each rule of a break, in the words of the criterion', async () => {
    render();
    await open();

    await chooseOption(screen.getByTestId('schedule-monday-break-0-finishes'), '13:00');
    expect(said()).toEqual(['Mon: a break must end after it starts.']);

    await chooseOption(screen.getByTestId('schedule-monday-break-0-finishes'), '19:00');
    expect(said()).toEqual(['Mon: a break must be inside the working hours.']);

    await chooseOption(screen.getByTestId('schedule-monday-break-0-finishes'), '14:00');
    fireEvent.click(screen.getByTestId('schedule-monday-add-break'));
    expect(said()).toEqual(['Mon: breaks can’t overlap.']);
    expect(screen.getByTestId('schedule-save')).toBeDisabled();
  });

  it('allows touching breaks, and refuses breaks that fill the day', async () => {
    render();
    await open();
    fireEvent.click(screen.getByTestId('schedule-monday-add-break'));
    await chooseOption(screen.getByTestId('schedule-monday-break-1-starts'), '14:00');
    await chooseOption(screen.getByTestId('schedule-monday-break-1-finishes'), '15:00');
    expect(screen.queryByTestId('schedule-problem')).toBeNull();

    await chooseOption(screen.getByTestId('schedule-monday-break-0-starts'), '09:00');
    await chooseOption(screen.getByTestId('schedule-monday-break-1-finishes'), '18:00');
    expect(said()).toEqual(['Mon: breaks can’t fill the whole day. Set it as not working.']);
    expect(screen.getByTestId('schedule-save')).toBeDisabled();
  });

  it('adds up to five breaks, and removes one by its own control', async () => {
    render();
    await open();
    const add = screen.getByTestId('schedule-monday-add-break');
    for (let index = 0; index < 4; index += 1) fireEvent.click(add);

    expect(add).toBeDisabled();
    expect(screen.getByTestId('schedule-monday-breaks-full')).toHaveTextContent('Up to 5 breaks a day.');
    expect(screen.getByTestId('schedule-monday-break-4')).toBeVisible();

    fireEvent.click(screen.getByTestId('schedule-monday-break-4-remove'));

    expect(screen.queryByTestId('schedule-monday-break-4')).toBeNull();
    expect(add).toBeEnabled();
  });

  it('turning a day off hides its breaks and sends none; turning it back on restores them', async () => {
    render();
    await open();
    fireEvent.click(screen.getByTestId('schedule-monday-working'));
    expect(screen.queryByTestId('schedule-monday-break-0')).toBeNull();

    fireEvent.click(screen.getByTestId('schedule-monday-working'));
    expect(screen.getByTestId('schedule-monday-break-0')).toBeVisible();

    fireEvent.click(screen.getByTestId('schedule-monday-working'));
    answers['team-week-save'] = () => Response.json({ version: 4, weekly: week(), affected: [] });
    fireEvent.click(screen.getByTestId('schedule-save'));
    await waitFor(() => expect(sent('team-week-save')).toHaveLength(1));
    expect((sent('team-week-save')[0]!.weekly as unknown[])[0]).toEqual({
      day: 'monday',
      working: false,
      starts: null,
      finishes: null,
      breaks: [],
    });
  });

  it('says what the API named when the sheet’s own checks did not', async () => {
    render();
    await open();
    answers['team-week-save'] = problem('bad_request', 400, [{ field: 'weekly[0].breaks[0]', detail: 'x' }]);
    await chooseOption(screen.getByTestId('schedule-monday-starts'), '10:00');

    fireEvent.click(screen.getByTestId('schedule-save'));

    await waitFor(() => expect(said()).toEqual(['Mon: a break must be inside the working hours.']));
    expect(screen.getByTestId('schedule-save')).toBeDisabled();
    await chooseOption(screen.getByTestId('schedule-monday-starts'), '09:30');
    expect(screen.queryByTestId('schedule-problem')).toBeNull();
  });
});

describe('the shortcuts (AC-4)', () => {
  it('Copy Monday copies its times and breaks to the other working days, and saves nothing', async () => {
    render();
    await open();

    fireEvent.click(screen.getByTestId('schedule-copy-monday'));

    expect(screen.getByTestId('schedule-tuesday-break-0')).toBeVisible();
    expect(screen.getByTestId('schedule-wednesday-off')).toBeVisible();
    expect(sent('team-week-save')).toEqual([]);
  });

  it('Copy Monday is disabled while Monday is not working', async () => {
    render();
    await open();

    fireEvent.click(screen.getByTestId('schedule-monday-working'));

    expect(screen.getByTestId('schedule-copy-monday')).toBeDisabled();
  });

  it('Use the salon’s hours sets every weekday, leaves the breaks, and saves nothing', async () => {
    answers['team-schedule'] = () =>
      Response.json(schedule({ weekly: week(workday('monday', '08:00', '19:00', [lunch])) }));
    render();
    await open();

    fireEvent.click(screen.getByTestId('schedule-use-salon'));

    expect(screen.getByTestId('schedule-monday-starts')).toHaveTextContent('09:00');
    expect(screen.getByTestId('schedule-monday-break-0')).toBeVisible();
    expect(screen.getByTestId('schedule-saturday-starts')).toBeVisible();
    expect(screen.getByTestId('schedule-sunday-off')).toBeVisible();
    expect(sent('team-week-save')).toEqual([]);
  });

  it('Use the salon’s hours is disabled, with its reason, when the salon has none', async () => {
    answers['team-opening-hours'] = () => Response.json(closedSalon);
    render();
    await open();

    const button = screen.getByTestId('schedule-use-salon');
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(screen.getByTestId('schedule-no-salon-hours').textContent!);
  });
});

describe('the warning about the salon’s hours (AC-5)', () => {
  it('a salon with no saved hours has one warning, and none day by day', async () => {
    answers['team-opening-hours'] = () => Response.json(closedSalon);
    render();
    await open();

    expect(screen.getByTestId('schedule-salon-hours-unset')).toHaveTextContent(
      'The salon hasn’t set its opening hours yet, so nobody can be booked.',
    );
    expect(screen.queryByTestId('schedule-outside')).toBeNull();
  });

  it('names each day, and does not stop the save', async () => {
    answers['team-schedule'] = () =>
      Response.json(
        schedule({ weekly: week(workday('monday', '08:00', '18:00'), workday('sunday', '10:00', '12:00')) }),
      );
    render();
    await open();

    expect(screen.getByTestId('schedule-outside')).toHaveTextContent(
      'Outside opening hours. Mon: the salon is open 09:00 – 18:00. Sun: the salon is closed. Bookings still follow the salon’s hours.',
    );
    fireEvent.click(screen.getByTestId('schedule-wednesday-working'));
    expect(screen.getByTestId('schedule-save')).toBeEnabled();
    expect(screen.queryByTestId('schedule-salon-hours-unset')).toBeNull();
  });

  it('says nothing inside the salon’s hours', async () => {
    render();
    await open();

    expect(screen.queryByTestId('schedule-outside')).toBeNull();
  });
});

describe('conflicts and leaving (AC-6)', () => {
  it('shows a conflict with a way to reload, which drops the edits', async () => {
    render();
    await open();
    await chooseOption(screen.getByTestId('schedule-monday-starts'), '10:00');
    answers['team-week-save'] = problem('conflict', 409);

    fireEvent.click(screen.getByTestId('schedule-save'));
    expect(await screen.findByTestId('schedule-conflict')).toBeVisible();

    answers['team-schedule'] = () => Response.json(schedule({ version: 9 }));
    fireEvent.click(screen.getByTestId('schedule-conflict-reload'));

    await waitFor(() => expect(screen.queryByTestId('schedule-conflict')).toBeNull());
    expect(screen.getByTestId('schedule-monday-starts')).toHaveTextContent('09:00');
    await chooseOption(screen.getByTestId('schedule-monday-starts'), '10:00');
    answers['team-week-save'] = () => Response.json({ version: 10, weekly: week(), affected: [] });
    fireEvent.click(screen.getByTestId('schedule-save'));
    await waitFor(() => expect(sent('team-week-save').at(-1)).toMatchObject({ version: 9 }));
  });

  it('Save hours is disabled while nothing has changed, and again once an edit is undone', async () => {
    render();
    await open();
    expect(screen.getByTestId('schedule-save')).toBeDisabled();

    fireEvent.click(screen.getByTestId('schedule-wednesday-working'));
    expect(screen.getByTestId('schedule-save')).toBeEnabled();

    fireEvent.click(screen.getByTestId('schedule-wednesday-working'));
    expect(screen.getByTestId('schedule-save')).toBeDisabled();
  });

  it('moving between the tabs keeps unsaved edits with no prompt', async () => {
    render();
    await open();
    await chooseOption(screen.getByTestId('schedule-monday-starts'), '10:00');
    const changes = screen.getByTestId('schedule-tab-changes');
    fireEvent.mouseDown(changes, { button: 0 });
    fireEvent.focus(changes);
    fireEvent.click(await screen.findByTestId('change-add'));
    fireEvent.change(await screen.findByTestId('change-note'), { target: { value: 'Half day' } });
    expect(screen.queryByTestId('team-leave-dialog')).toBeNull();

    const hours = screen.getByTestId('schedule-tab-week');
    fireEvent.mouseDown(hours, { button: 0 });
    fireEvent.focus(hours);

    expect(await screen.findByTestId('schedule-monday-starts')).toHaveTextContent('10:00');
    expect(screen.queryByTestId('team-leave-dialog')).toBeNull();
    fireEvent.mouseDown(changes, { button: 0 });
    fireEvent.focus(changes);
    expect(await screen.findByTestId('change-note')).toHaveValue('Half day');
  });

  it('says it when the API answers that a save changes nothing', async () => {
    render();
    await open();
    answers['team-week-save'] = problem('invalid_state', 409);
    fireEvent.click(screen.getByTestId('schedule-wednesday-working'));

    fireEvent.click(screen.getByTestId('schedule-save'));

    expect(await screen.findByTestId('schedule-unchanged')).toHaveTextContent(
      'Nothing has changed, so there is nothing to save.',
    );
  });

  it('asks before Close leaves unsaved edits, and stays or leaves', async () => {
    render();
    await open();
    await chooseOption(screen.getByTestId('schedule-monday-starts'), '10:00');

    fireEvent.click(screen.getByTestId('schedule-close'));
    fireEvent.click(await screen.findByTestId('team-leave-stay'));
    await waitFor(() => expect(screen.queryByTestId('team-leave-dialog')).toBeNull());
    expect(screen.getByTestId('schedule-monday-starts')).toHaveTextContent('10:00');

    fireEvent.click(screen.getByTestId('schedule-close'));
    fireEvent.click(await screen.findByTestId('team-leave-confirm'));
    await waitFor(() => expect(screen.queryByTestId('schedule-sheet')).toBeNull());
    expect(sent('team-week-save')).toEqual([]);
  });

  it('closes without asking when nothing was edited', async () => {
    render();
    await open();

    fireEvent.click(screen.getByTestId('schedule-close'));

    await waitFor(() => expect(screen.queryByTestId('schedule-sheet')).toBeNull());
    expect(screen.queryByTestId('team-leave-dialog')).toBeNull();
  });

  it('asks before a half-filled date change is left', async () => {
    render();
    await openChanges();
    fireEvent.click(screen.getByTestId('change-add'));
    fireEvent.change(await screen.findByTestId('change-note'), { target: { value: 'Half day' } });

    fireEvent.click(screen.getByTestId('schedule-close'));

    expect(await screen.findByTestId('team-leave-dialog')).toBeVisible();
  });
});

describe('date changes (AC-7)', () => {
  it('says there are none, under the banner', async () => {
    render();
    await openChanges();

    expect(screen.getByTestId('change-banner')).toHaveTextContent(
      'A date change replaces the weekly hours on that date only. Use it for a half day or an extra working day.',
    );
    expect(screen.getByTestId('change-empty')).toHaveTextContent('No date changes.');
  });

  it('lists the changes the API sends, with their hours, breaks and notes', async () => {
    answers['team-schedule'] = () =>
      Response.json(
        schedule({
          dateChanges: [
            change('2026-11-05', { note: 'Doctor' }),
            change('2026-11-07', {
              working: true,
              starts: '09:00',
              finishes: '14:00',
              breaks: [{ starts: '11:00', finishes: '11:15' }],
            }),
          ],
        }),
      );
    render();
    await openChanges();

    const rows = screen.getAllByTestId('change-row');
    expect(within(rows[0]!).getByTestId('change-row-hours')).toHaveTextContent('Not working · Doctor');
    expect(within(rows[1]!).getByTestId('change-row-hours')).toHaveTextContent('09:00 – 14:00 · break 11:00');
    expect(within(rows[0]!).getByTestId('change-remove')).toHaveAccessibleName(/Remove the change on/);
  });

  it("prints the rows in the short day form, without the year within the salon's year (F06-S02 AC-17)", async () => {
    answers['team-schedule'] = () =>
      Response.json(schedule({ dateChanges: [change('2026-11-05'), change('2027-01-05')] }));
    render();
    await openChanges();

    const [thisYear, nextYear] = screen.getAllByTestId('change-row');
    expect(within(thisYear!).getByTestId('change-row-date').textContent).toBe('Thu, Nov 5');
    expect(within(thisYear!).getByTestId('change-remove')).toHaveAccessibleName('Remove the change on Thu, Nov 5');
    expect(within(nextYear!).getByTestId('change-row-date').textContent).toBe('Tue, Jan 5, 2027');
    expect(within(nextYear!).getByTestId('change-remove')).toHaveAccessibleName(
      'Remove the change on Tue, Jan 5, 2027',
    );
  });

  it("keeps the year on the form's date button (F06-S02 AC-17)", async () => {
    render();
    await openChanges();
    fireEvent.click(screen.getByTestId('change-add'));
    await screen.findByTestId('change-form');
    fireEvent.click(screen.getByTestId('change-date'));
    const calendar = await screen.findByTestId('change-calendar');
    fireEvent.click(calendar.querySelector('button[aria-label*="November 5,"]')!);

    await waitFor(() => expect(screen.getByTestId('change-date').textContent).toBe('Thu, Nov 5, 2026'));
  });

  it('asks for a date before saving', async () => {
    render();
    await openChanges();
    fireEvent.click(screen.getByTestId('change-add'));

    fireEvent.click(await screen.findByTestId('change-save'));

    expect(await screen.findByTestId('change-problem')).toHaveTextContent('Choose a date.');
    expect(sent('team-date-change-add')).toEqual([]);
  });

  const pickDate = async (day: string) => {
    fireEvent.click(screen.getByTestId('change-date'));
    const calendar = await screen.findByTestId('change-calendar');
    fireEvent.click(within(calendar).getByRole('button', { name: new RegExp(day) }));
    await waitFor(() => expect(screen.queryByTestId('change-calendar')).toBeNull());
  };

  it('adds different hours with a break and a note, says so, and lists the change', async () => {
    render();
    await openChanges();
    fireEvent.click(screen.getByTestId('change-add'));
    await screen.findByTestId('change-form');
    await pickDate('November 5');
    await chooseOption(screen.getByTestId('change-finishes'), '14:00');
    fireEvent.click(screen.getByTestId('change-add-break'));
    await chooseOption(screen.getByTestId('change-break-0-starts'), '10:00');
    await chooseOption(screen.getByTestId('change-break-0-finishes'), '10:15');
    fireEvent.change(screen.getByTestId('change-note'), { target: { value: 'Half day' } });
    answers['team-date-change-add'] = () =>
      Response.json(
        {
          change: change('2026-11-05', { working: true, starts: '09:00', finishes: '14:00', note: 'Half day' }),
          affected: [],
        },
        { status: 201 },
      );

    fireEvent.click(screen.getByTestId('change-save'));

    expect(await screen.findByText('Date saved.')).toBeVisible();
    expect(sent('team-date-change-add')[0]).toEqual({
      id: NIMALI,
      date: '2026-11-05',
      working: true,
      starts: '09:00',
      finishes: '14:00',
      breaks: [{ starts: '10:00', finishes: '10:15' }],
      note: 'Half day',
    });
    expect(await screen.findByTestId('change-row')).toHaveAttribute('data-date', '2026-11-05');
    expect(screen.queryByTestId('change-form')).toBeNull();
  });

  it('offers no date before today', async () => {
    render();
    await openChanges();
    fireEvent.click(screen.getByTestId('change-add'));
    await screen.findByTestId('change-form');
    fireEvent.click(screen.getByTestId('change-date'));
    const calendar = await screen.findByTestId('change-calendar');

    expect(within(calendar).getByRole('button', { name: /November 1,/ })).toBeDisabled();
    expect(within(calendar).getByRole('button', { name: /November 2,/ })).toBeEnabled();
  });

  it('Not working has no times or breaks to enter', async () => {
    render();
    await openChanges();
    fireEvent.click(screen.getByTestId('change-add'));
    await screen.findByTestId('change-form');
    expect(screen.getByTestId('change-starts')).toBeVisible();

    fireEvent.click(screen.getByTestId('change-kind-off'));

    expect(screen.queryByTestId('change-starts')).toBeNull();
    expect(screen.queryByTestId('change-add-break')).toBeNull();
  });

  it('says the rules of the hours without a weekday, and stops the save', async () => {
    render();
    await openChanges();
    fireEvent.click(screen.getByTestId('change-add'));
    await screen.findByTestId('change-form');

    await chooseOption(screen.getByTestId('change-finishes'), '09:00');

    expect(screen.getByTestId('change-problem')).toHaveTextContent('The finish must be after the start.');
    expect(screen.getByTestId('change-save')).toBeDisabled();

    await chooseOption(screen.getByTestId('change-finishes'), '18:00');
    fireEvent.click(screen.getByTestId('change-add-break'));
    await chooseOption(screen.getByTestId('change-break-0-starts'), '09:00');
    await chooseOption(screen.getByTestId('change-break-0-finishes'), '18:00');

    expect(screen.getByTestId('change-problem')).toHaveTextContent(
      'Breaks can’t fill the whole day. Set it as not working.',
    );
    expect(screen.getByTestId('change-save')).toBeDisabled();
  });

  it.each([
    [problem('conflict', 409), 'This date already has a change. Remove it first.'],
    [problem('limit_reached', 409), 'This person has 100 upcoming date changes. Remove one first.'],
    [problem('bad_request', 400, [{ field: 'date' }]), 'Choose a date from today to a year ahead.'],
    [problem('bad_request', 400, [{ field: 'note' }]), 'The note must be 60 characters or fewer.'],
    [problem('bad_request', 400, [{ field: 'breaks' }]), 'Breaks can’t fill the whole day. Set it as not working.'],
  ])('says what the API refused, keeping the form: %#', async (refusal, words) => {
    render();
    await openChanges();
    fireEvent.click(screen.getByTestId('change-add'));
    await screen.findByTestId('change-form');
    await pickDate('November 5');
    answers['team-date-change-add'] = refusal;

    fireEvent.click(screen.getByTestId('change-save'));

    expect(await screen.findByTestId('change-problem')).toHaveTextContent(words);
    expect(screen.getByTestId('change-form')).toBeVisible();
  });

  it('Remove takes a change away and says so', async () => {
    answers['team-schedule'] = () => Response.json(schedule({ dateChanges: [change('2026-11-05')] }));
    answers['team-date-change-remove'] = () => Response.json({ ok: true });
    render();
    await openChanges();

    fireEvent.click(screen.getByTestId('change-remove'));

    expect(await screen.findByText('Back to the weekly hours on that date.')).toBeVisible();
    expect(sent('team-date-change-remove')[0]).toEqual({ id: NIMALI, changeId: 'change-2026-11-05' });
    expect(await screen.findByTestId('change-empty')).toBeVisible();
  });

  it('a change that has gone shows the conflict with a way to reload', async () => {
    answers['team-schedule'] = () => Response.json(schedule({ dateChanges: [change('2026-11-05')] }));
    answers['team-date-change-remove'] = problem('not_found', 404);
    render();
    await openChanges();

    fireEvent.click(screen.getByTestId('change-remove'));

    expect(await screen.findByTestId('schedule-conflict')).toBeVisible();
  });
});

describe('bookings left outside (AC-11)', () => {
  const booking = {
    date: '2026-11-09',
    time: '10:30',
    customerName: 'Kasun Silva',
    serviceName: 'Balayage',
  };

  it('a saved week lists them and stays open', async () => {
    render();
    await open();
    answers['team-week-save'] = () =>
      Response.json({ version: 4, weekly: schedule().weekly, affected: [booking, { ...booking, time: '11:30' }] });
    fireEvent.click(screen.getByTestId('schedule-wednesday-working'));

    fireEvent.click(screen.getByTestId('schedule-save'));

    const alert = await screen.findByTestId('schedule-affected');
    expect(alert).toHaveTextContent('2 upcoming bookings fall outside these hours. They are kept.');
    expect(within(alert).getAllByTestId('schedule-affected-booking')[0]).toHaveTextContent('Kasun Silva');
    expect(within(alert).getAllByTestId('schedule-affected-booking')[0]).toHaveTextContent('10:30');
    expect(within(alert).getAllByTestId('schedule-affected-booking')[0]).toHaveTextContent('Balayage');
    expect(screen.getByTestId('schedule-sheet')).toBeVisible();
  });

  it('says one booking of a saved week in the singular', async () => {
    render();
    await open();
    answers['team-week-save'] = () => Response.json({ version: 4, weekly: schedule().weekly, affected: [booking] });
    fireEvent.click(screen.getByTestId('schedule-wednesday-working'));

    fireEvent.click(screen.getByTestId('schedule-save'));

    expect(await screen.findByTestId('schedule-affected')).toHaveTextContent(
      '1 upcoming booking falls outside these hours. It is kept.',
    );
  });

  it.each([
    [1, '1 upcoming booking on this date is affected. It is kept.'],
    [2, '2 upcoming bookings on this date are affected. They are kept.'],
  ])('a date change says %i in its own words', async (count, words) => {
    render();
    await openChanges();
    fireEvent.click(screen.getByTestId('change-add'));
    await screen.findByTestId('change-form');
    fireEvent.click(screen.getByTestId('change-date'));
    const calendar = await screen.findByTestId('change-calendar');
    fireEvent.click(within(calendar).getByRole('button', { name: /November 5/ }));
    answers['team-date-change-add'] = () =>
      Response.json(
        { change: change('2026-11-05'), affected: Array.from({ length: count }, () => booking) },
        { status: 201 },
      );

    fireEvent.click(await screen.findByTestId('change-save'));

    expect(await screen.findByTestId('schedule-affected')).toHaveTextContent(words);
  });
});

describe('those who only read (AC-13)', () => {
  it('see the hours and the changes with nothing to change, and no note arrives to show', async () => {
    answers['team-schedule'] = () =>
      Response.json(schedule({ dateChanges: [change('2026-11-05', { note: undefined })] }));
    render(false);
    await openChanges();

    expect(screen.queryByTestId('change-add')).toBeNull();
    expect(screen.queryByTestId('change-remove')).toBeNull();
    expect(screen.getByTestId('change-row-hours')).toHaveTextContent('Not working');
    expect(calls.filter((call) => call.action === 'team-sign-in-accounts')).toEqual([]);

    const tab = screen.getByTestId('schedule-tab-week');
    fireEvent.mouseDown(tab, { button: 0 });
    fireEvent.focus(tab);
    await screen.findByTestId('schedule-week');
    expect(screen.queryByTestId('schedule-save')).toBeNull();
    expect(screen.queryByTestId('schedule-monday-add-break')).toBeNull();
    expect(screen.queryByTestId('schedule-monday-break-0-remove')).toBeNull();
    expect(screen.queryByTestId('schedule-copy-monday')).toBeNull();
    expect(screen.getByTestId('schedule-monday-starts')).toBeDisabled();
    expect(screen.getByTestId('schedule-monday-working')).toBeDisabled();
  });

  it.each([
    ['support', 'A support session can read the team but cannot change it.'],
    ['salon', 'This salon is read-only until its overdue invoices are paid. You can read the team but not change it.'],
  ] as const)('a %s view says it is read-only and offers no change', async (readOnly, words) => {
    render(false, readOnly);
    await open();

    expect(screen.getByTestId('schedule-read-only')).toHaveTextContent(words);
    expect(screen.queryByTestId('schedule-save')).toBeNull();
  });

  it('closing never asks: nothing can have been edited', async () => {
    render(false);
    await open();

    fireEvent.click(screen.getByTestId('schedule-close'));

    await waitFor(() => expect(screen.queryByTestId('schedule-sheet')).toBeNull());
  });
});

describe('an idle session (BUG-F06-001)', () => {
  it('renews an access credential that lapsed and opens the schedule sheet, not the sign-in form', async () => {
    render();
    await screen.findByTestId('team-card-schedule');
    const renewals = expireAccessBefore(answers, 'team-schedule');
    expect(await open()).toBeInTheDocument();
    expect(renewals()).toBe(1);
  });
});
