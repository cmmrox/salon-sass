import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Closure, HolidayYear, HoursSettings } from '@/features/staff';
import { renderWithMessages } from '@test/render';
import { chooseOption } from '@test/select';
import { HoursSettingsScreen } from './HoursSettingsScreen';
import { SettingsScreen } from './SettingsScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));

const WEEK = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'].map((day) => ({
  day,
  open: day !== 'sunday',
  opens: day === 'sunday' ? null : '09:00',
  closes: day === 'sunday' ? null : '18:00',
}));
/**
 * A saved week with every day closed. The holiday and closure tests serve it because the opening hours play
 * no part in them: a closed day has no time Selects, and Radix keeps all 96 options of every closed time
 * Select mounted (to show the chosen one), so the open week alone adds about 1,150 options to every render,
 * which in jsdom made these tests run past the 5 s timeout on the CI runner.
 */
const CLOSED_WEEK = WEEK.map((day) => ({ ...day, open: false, opens: null, closes: null }));
const saved = (overrides: Partial<HoursSettings> = {}): HoursSettings => ({
  version: 1,
  weekly: WEEK as HoursSettings['weekly'],
  outsideHours: [],
  ...overrides,
});
function yearOf(overrides: Partial<HolidayYear> = {}): HolidayYear {
  return {
    year: 2026,
    revision: 1,
    publishedAt: '2026-10-01T04:30:00Z',
    reviewed: false,
    version: 0,
    days: [
      {
        date: '2026-10-25',
        name: 'Vap Full Moon Poya Day',
        kind: 'poya',
        choice: null,
        opens: null,
        closes: null,
        toReview: true,
        past: true,
      },
      {
        date: '2026-11-08',
        name: 'Deepavali Festival Day',
        kind: 'public',
        choice: null,
        opens: null,
        closes: null,
        toReview: true,
        past: false,
      },
      {
        date: '2026-12-25',
        name: 'Christmas Day',
        kind: 'public',
        choice: null,
        opens: null,
        closes: null,
        toReview: true,
        past: false,
      },
    ],
    ...overrides,
  };
}

type Call = { url: string; body: unknown };
let calls: Call[];
let answers: Record<string, (body: unknown) => Response>;
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-01T06:00:00Z'));
  calls = [];
  answers = {
    principal: () => Response.json({ kind: 'staff', permissions: ['settings.read', 'settings.manage'] }),
    hours: () => Response.json(saved({ version: 0, weekly: null })),
    holidays: () => Response.json({ years: [yearOf()] }),
    closures: () => Response.json([]),
  };
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const body = init?.body ? JSON.parse(String(init.body)) : null;
    calls.push({ url, body });
    const action = url.split('/').pop()!.split('?')[0]!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))(body);
  });
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const changes = () => calls.filter((call) => call.body !== null);
const render = (readOnly: 'support' | 'salon' | null = null) =>
  renderWithMessages(<HoursSettingsScreen kind="staff" timeZone="Asia/Colombo" readOnly={readOnly} />);

/**
 * These tests need the whole open week: twelve time Selects, each keeping its 96 options mounted, so every
 * change re-renders about 1,150 options. A save journey takes under a second on a developer machine but up
 * to 5 s in jsdom on the CI runner, so this block allows 15 s rather than the default 5 s.
 */
describe('the Hours & closures tab: opening hours (AC-1)', { timeout: 15_000 }, () => {
  it('says above the tabs that these settings save at once, not as a draft', () => {
    renderWithMessages(
      <SettingsScreen tab="hours">
        <p>content</p>
      </SettingsScreen>,
    );
    expect(screen.getByTestId('settings-intro')).toHaveTextContent('save at once');
  });

  it('shows a loading state, then the suggested week marked Not saved yet', async () => {
    render();
    expect(screen.getByTestId('hours-loading')).toHaveAttribute('aria-busy', 'true');

    expect(await screen.findByTestId('hours-not-saved')).toHaveTextContent('Not saved yet');
    expect(screen.getByTestId('hours-monday-open')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('hours-saturday-closes')).toHaveTextContent('19:00');
    expect(screen.getByTestId('hours-sunday-open')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('hours-sunday-closed')).toHaveTextContent('Closed');
    expect(screen.getByTestId('hours-monday-opens')).toHaveAccessibleName('Monday opens');
    expect(screen.getByTestId('hours-monday-open')).toHaveAccessibleName('Monday');
    expect(screen.getByTestId('hours-save')).toBeEnabled();
    // Each weekday is an Item row of the week's ItemGroup (docs/engineering/web.md, "Styling").
    expect(screen.getByTestId('hours-week')).toHaveAttribute('data-slot', 'item-group');
    expect(screen.getByTestId('hours-day-monday')).toHaveAttribute('data-slot', 'item');
  });

  it('refuses a closing time at or before the opening time beside that day, sending nothing', async () => {
    render();
    await chooseOption(await screen.findByTestId('hours-tuesday-closes'), '09:00');
    fireEvent.click(screen.getByTestId('hours-save'));

    expect(await screen.findByTestId('hours-tuesday-error')).toHaveTextContent(
      'Closing time must be after opening time.',
    );
    expect(screen.getByTestId('hours-tuesday-closes')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('hours-fix')).toBeInTheDocument();
    expect(changes()).toEqual([]);
  });

  it('saves the week at once with its version and shows the bookings it leaves outside', async () => {
    answers['hours-save'] = () =>
      Response.json(
        saved({ outsideHours: [{ time: '18:30', customerName: 'Ishara Fernando', serviceName: 'Colour' }] }),
      );
    render();
    fireEvent.click(await screen.findByTestId('hours-sunday-open'));
    await chooseOption(screen.getByTestId('hours-sunday-closes'), '13:00');
    fireEvent.click(screen.getByTestId('hours-save'));

    expect(await screen.findByTestId('hours-outside')).toHaveTextContent(
      '1 upcoming booking falls outside these hours.',
    );
    const save = changes().find((call) => call.url.endsWith('/hours-save'))!;
    expect((save.body as { version: number }).version).toBe(0);
    expect((save.body as { weekly: unknown[] }).weekly.at(-1)).toEqual({
      day: 'sunday',
      open: true,
      opens: '09:00',
      closes: '13:00',
    });
    expect(screen.queryByTestId('hours-not-saved')).not.toBeInTheDocument();
    expect(await screen.findByText('Opening hours saved. New bookings follow them.')).toBeInTheDocument();
  });

  it('says someone else changed the hours when the version is stale', async () => {
    answers.hours = () => Response.json(saved());
    answers['hours-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    render();
    fireEvent.click(await screen.findByTestId('hours-monday-open'));
    fireEvent.click(screen.getByTestId('hours-save'));

    expect(await screen.findByTestId('hours-conflict')).toHaveTextContent(
      'Someone else changed the hours and closures',
    );
  });

  it('keeps every control disabled for a support session', async () => {
    render('support');
    expect(await screen.findByTestId('hours-read-only')).toHaveTextContent('support session');
    expect(screen.getByTestId('hours-monday-open')).toBeDisabled();
    expect(screen.getByTestId('hours-save')).toBeDisabled();
    expect(screen.getByTestId('holidays-accept')).toBeDisabled();
    expect(screen.getByTestId('closure-add')).toBeDisabled();
  });

  it('says the hours could not be reached and offers to try again', async () => {
    answers.hours = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    render();
    expect(await screen.findByTestId('hours-unavailable')).toBeInTheDocument();
    expect(screen.getByTestId('hours-retry')).toBeInTheDocument();
  });
});

describe('the Hours & closures tab: public holidays (AC-4, AC-5)', () => {
  beforeEach(() => {
    answers.hours = () => Response.json(saved({ weekly: CLOSED_WEEK as HoursSettings['weekly'] }));
  });
  /** The year as the API answers once the salon accepted Closed for Deepavali and 10:00 to 13:00 for Christmas. */
  const reviewed = (version: number) =>
    yearOf({
      reviewed: true,
      version,
      days: yearOf().days.map((day) => ({
        ...day,
        toReview: false,
        choice: day.date === '2026-12-25' ? 'special' : 'closed',
        opens: day.date === '2026-12-25' ? '10:00' : null,
        closes: day.date === '2026-12-25' ? '13:00' : null,
      })),
    });

  it('lists the year’s coming days To review with their kind, each Closed until the salon chooses', async () => {
    render();
    expect(await screen.findByTestId('holidays-status')).toHaveTextContent('To review');
    const days = within(screen.getByTestId('holidays-days')).getAllByRole('listitem');
    expect(days).toHaveLength(2);
    expect(days[0]).toHaveTextContent('Deepavali Festival Day');
    expect(days[0]).toHaveTextContent('Public');
    expect(screen.getByTestId('holiday-2026-11-08-choice')).toHaveTextContent('Closed');
    expect(screen.queryByTestId('holiday-2026-10-25')).not.toBeInTheDocument();
    // Each day is an Item row of the year's ItemGroup (docs/engineering/web.md, "Styling").
    expect(screen.getByTestId('holidays-days')).toHaveAttribute('data-slot', 'item-group');
    expect(screen.getByTestId('holiday-2026-11-08')).toHaveAttribute('data-slot', 'item');
  });

  it('accepts the choices, special hours with their times', async () => {
    answers['holidays-accept'] = () => Response.json(reviewed(1));
    render();
    await chooseOption(await screen.findByTestId('holiday-2026-12-25-choice'), 'Special hours');
    await chooseOption(screen.getByTestId('holiday-2026-12-25-opens'), '10:00');
    fireEvent.click(screen.getByTestId('holidays-accept'));

    expect(
      await screen.findByText('Holidays accepted. Customers can’t book on the days you close.'),
    ).toBeInTheDocument();
    const accepted = changes().find((call) => call.url.endsWith('/holidays-accept'))!;
    expect(accepted.body).toEqual({
      year: 2026,
      version: 0,
      choices: [
        { date: '2026-11-08', choice: 'closed', opens: null, closes: null },
        { date: '2026-12-25', choice: 'special', opens: '10:00', closes: '13:00' },
      ],
    });
    expect(screen.getByTestId('holidays-status')).toHaveTextContent('Reviewed');
    expect(screen.queryByTestId('holidays-accept')).not.toBeInTheDocument();
  });

  it('after the review, saves a changed day at once', async () => {
    answers.holidays = () => Response.json({ years: [reviewed(1)] });
    answers['holiday-choose'] = () => Response.json(yearOf({ reviewed: true, version: 2 }));
    render();
    expect(await screen.findByTestId('holidays-status')).toHaveTextContent('Reviewed');
    expect(screen.queryByTestId('holidays-accept')).not.toBeInTheDocument();

    await chooseOption(screen.getByTestId('holiday-2026-11-08-choice'), 'Open as usual');
    await waitFor(() => expect(changes().some((call) => call.url.endsWith('/holiday-choose'))).toBe(true));
    expect(changes().find((call) => call.url.endsWith('/holiday-choose'))!.body).toEqual({
      year: 2026,
      date: '2026-11-08',
      choice: 'open',
      opens: null,
      closes: null,
    });
  });

  /** Next year, published before January with two days of its own. */
  const nextYear = (overrides: Partial<HolidayYear> = {}): HolidayYear =>
    yearOf({
      year: 2027,
      revision: 1,
      publishedAt: '2026-10-20T04:30:00Z',
      version: 0,
      days: [
        { ...yearOf().days[1]!, date: '2027-01-14', name: 'Tamil Thai Pongal Day' },
        { ...yearOf().days[1]!, date: '2027-02-04', name: 'Independence Day', kind: 'bank' },
      ],
      ...overrides,
    });

  it('shows a card for each published year with days to come, in year order, each with its own status', async () => {
    answers.holidays = () => Response.json({ years: [reviewed(1), nextYear()] });
    render();
    const current = await screen.findByTestId('holidays-card');
    const next = screen.getByTestId('holidays-card-2027');
    expect(current).toHaveAttribute('data-year', '2026');
    expect(current).toHaveTextContent('Public holidays 2026');
    expect(next).toHaveTextContent('Public holidays 2027');
    expect(current.compareDocumentPosition(next) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByTestId('holidays-status')).toHaveTextContent('Reviewed');
    expect(screen.getByTestId('holidays-status-2027')).toHaveTextContent('To review');
    expect(within(screen.getByTestId('holidays-days-2027')).getAllByRole('listitem')).toHaveLength(2);
    expect(screen.queryByTestId('holidays-accept')).not.toBeInTheDocument();
    expect(screen.getByTestId('holidays-accept-2027')).toBeEnabled();
  });

  it('accepts next year with its own version and choices, leaving this year as it was', async () => {
    // Next year was accepted once (version 2) and the operator has since revised a day, so it is to review
    // again; this year has not been accepted, so its choices are a proposal.
    const revised = nextYear({
      version: 2,
      days: nextYear().days.map((day) =>
        day.kind === 'bank' ? { ...day, choice: 'open', toReview: false } : { ...day, choice: 'closed' },
      ),
    });
    answers.holidays = () => Response.json({ years: [yearOf(), revised] });
    answers['holidays-accept'] = () =>
      Response.json({
        ...revised,
        reviewed: true,
        version: 3,
        days: revised.days.map((day) => ({ ...day, toReview: false })),
      });
    render();
    await chooseOption(await screen.findByTestId('holiday-2026-12-25-choice'), 'Special hours');
    expect(changes(), 'a choice in a year not yet accepted is only a proposal').toEqual([]);
    expect(screen.getByTestId('holiday-2027-02-04-choice')).toHaveTextContent('Open as usual');
    fireEvent.click(screen.getByTestId('holidays-accept-2027'));

    expect(await screen.findByTestId('holidays-status-2027')).toHaveTextContent('Reviewed');
    expect(changes()).toEqual([
      {
        url: expect.stringMatching(/\/holidays-accept$/),
        body: {
          year: 2027,
          version: 2,
          choices: [
            { date: '2027-01-14', choice: 'closed', opens: null, closes: null },
            { date: '2027-02-04', choice: 'open', opens: null, closes: null },
          ],
        },
      },
    ]);
    expect(screen.queryByTestId('holidays-accept-2027')).not.toBeInTheDocument();
    expect(screen.getByTestId('holidays-status')).toHaveTextContent('To review');
    expect(screen.getByTestId('holiday-2026-12-25-choice'), 'this year keeps its unsaved proposal').toHaveTextContent(
      'Special hours',
    );
    expect(screen.getByTestId('holidays-accept')).toBeEnabled();
  });

  it('leaves out a year whose days have all passed when a later year has days to come', async () => {
    answers.holidays = () =>
      Response.json({ years: [yearOf({ days: yearOf().days.map((day) => ({ ...day, past: true })) }), nextYear()] });
    render();
    expect(await screen.findByTestId('holidays-card')).toHaveAttribute('data-year', '2027');
    expect(screen.queryByText('Public holidays 2026')).not.toBeInTheDocument();
  });

  it('says the operator has not published this year’s holidays yet', async () => {
    answers.holidays = () => Response.json({ years: [] });
    render();
    expect(await screen.findByTestId('holidays-none')).toHaveTextContent('has not published');
  });
});

describe('the Hours & closures tab: special closures (AC-7)', () => {
  beforeEach(() => {
    answers.hours = () => Response.json(saved({ weekly: CLOSED_WEEK as HoursSettings['weekly'] }));
  });
  const closure: Closure = {
    id: '2b0c4ad4-8f0d-4d3a-9d1f-1a0b9c8e7f61',
    date: '2026-11-05',
    reason: 'Staff training',
    affectedBookings: 0,
  };

  it('says No closures planned, then adds one through the sheet after showing its bookings', async () => {
    answers['closure-impact'] = () => Response.json({ date: '2026-11-05', bookings: [] });
    answers['closure-add'] = () => Response.json(closure, { status: 201 });
    render();
    expect(await screen.findByTestId('closures-empty')).toHaveTextContent('No closures planned.');

    fireEvent.click(screen.getByTestId('closure-add'));
    const sheet = await screen.findByTestId('closure-sheet');
    expect(within(sheet).getByTestId('closure-confirm')).toBeDisabled();
    fireEvent.click(within(sheet).getByTestId('closure-date'));
    const calendar = await screen.findByTestId('closure-calendar');
    // Today is allowed; the month before it is not offered at all.
    expect(within(calendar).getByRole('button', { name: /November 1(st)?, 2026/ })).toBeEnabled();
    expect(within(calendar).getByRole('button', { name: /previous month/i })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(within(calendar).getByRole('button', { name: /November 5/ }));
    expect(await within(sheet).findByTestId('closure-no-bookings')).toHaveTextContent('No bookings on this day.');
    fireEvent.change(within(sheet).getByTestId('closure-reason'), { target: { value: 'Staff training' } });
    fireEvent.click(within(sheet).getByTestId('closure-confirm'));

    expect(await screen.findByText('Closed. Customers can’t book this day.')).toBeInTheDocument();
    expect(changes().find((call) => call.url.endsWith('/closure-add'))!.body).toEqual({
      date: '2026-11-05',
      reason: 'Staff training',
    });
    expect(calls.some((call) => call.url.endsWith('/closure-impact?date=2026-11-05'))).toBe(true);
    expect(screen.getByTestId('closure-row')).toHaveTextContent('Staff training');
  });

  it('lists the bookings on the chosen date and keeps the sheet open when the day is already closed', async () => {
    answers['closure-impact'] = () =>
      Response.json({
        date: '2026-11-05',
        bookings: [{ time: '10:30', customerName: 'Nimali Perera', serviceName: 'Haircut' }],
      });
    answers['closure-add'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    render();
    fireEvent.click(await screen.findByTestId('closure-add'));
    const sheet = await screen.findByTestId('closure-sheet');
    fireEvent.click(within(sheet).getByTestId('closure-date'));
    fireEvent.click(within(await screen.findByTestId('closure-calendar')).getByRole('button', { name: /November 5/ }));

    expect(await within(sheet).findByTestId('closure-bookings')).toHaveTextContent('1 booking on this day.');
    expect(within(sheet).getByTestId('closure-booking')).toHaveTextContent('10:30 · Nimali Perera');
    expect(within(sheet).getByTestId('closure-booking')).toHaveAttribute('data-slot', 'item');
    fireEvent.click(within(sheet).getByTestId('closure-confirm'));
    expect(await within(sheet).findByTestId('closure-date-error')).toHaveTextContent('This day is already closed.');
  });

  it('opens the sheet empty again after a closure is added', async () => {
    answers['closure-impact'] = () => Response.json({ date: '2026-11-05', bookings: [] });
    answers['closure-add'] = () => Response.json(closure, { status: 201 });
    render();
    fireEvent.click(await screen.findByTestId('closure-add'));
    let sheet = await screen.findByTestId('closure-sheet');
    fireEvent.click(within(sheet).getByTestId('closure-date'));
    fireEvent.click(within(await screen.findByTestId('closure-calendar')).getByRole('button', { name: /November 5/ }));
    await within(sheet).findByTestId('closure-no-bookings');
    fireEvent.change(within(sheet).getByTestId('closure-reason'), { target: { value: 'Staff training' } });
    fireEvent.click(within(sheet).getByTestId('closure-confirm'));
    await screen.findByTestId('closure-row');
    await waitFor(() => expect(screen.queryByTestId('closure-sheet')).not.toBeInTheDocument());

    fireEvent.click(screen.getByTestId('closure-add'));
    sheet = await screen.findByTestId('closure-sheet');
    expect(within(sheet).getByTestId('closure-date')).toHaveTextContent('Choose a day');
    expect(within(sheet).getByTestId('closure-reason')).toHaveValue('');
    expect(within(sheet).getByTestId('closure-confirm')).toBeDisabled();
  });

  it('reopens a closure', async () => {
    answers.closures = () => Response.json([closure]);
    answers['closure-reopen'] = () => Response.json({ ok: true });
    render();
    fireEvent.click(await screen.findByTestId('closure-reopen'));

    expect(await screen.findByTestId('closures-empty')).toBeInTheDocument();
    expect(changes().find((call) => call.url.endsWith('/closure-reopen'))!.body).toEqual({ id: closure.id });
  });
});
