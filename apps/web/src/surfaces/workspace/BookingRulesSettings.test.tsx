import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BookingRulesHistory, BookingRulesSettings } from '@/features/booking';
import { renderWithMessages } from '@test/render';
import { chooseOption } from '@test/select';
import { BookingRulesSettingsScreen } from './BookingRulesSettingsScreen';
import { SettingsScreen } from './SettingsScreen';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));

const RULES: NonNullable<BookingRulesSettings['rules']> = {
  slotIntervalMinutes: 15,
  minimumNoticeMinutes: 60,
  bookingHorizonDays: 60,
  deposit: { kind: 'percentage', percent: 20, capMinor: 200_000 },
  freeCancellationHours: 24,
  lateCancellation: { kind: 'deposit' },
  noShow: { kind: 'deposit' },
  reschedule: { limit: 2, untilHours: 12 },
};
const ME = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const unsaved: BookingRulesSettings = { version: 0, rules: null, currency: 'LKR' };
const saved = (version = 1, rules = RULES): BookingRulesSettings => ({ version, rules, currency: 'LKR' });
const entry = (version: number, createdBy: string, createdByLabel: string | null) => ({
  versionId: `00000000-0000-4000-8000-00000000000${version}`,
  version,
  createdAt: '2026-11-01T04:30:00Z',
  createdBy,
  createdByLabel,
  changedFields: version === 1 ? (['slotIntervalMinutes', 'deposit'] as const) : (['noShow'] as const),
});

type Call = { url: string; body: unknown };
let calls: Call[];
let answers: Record<string, (body: unknown) => Response>;
beforeEach(() => {
  push.mockReset();
  calls = [];
  answers = {
    principal: () => Response.json({ kind: 'staff', staffId: ME, permissions: ['settings.read', 'settings.manage'] }),
    'booking-rules': () => Response.json(unsaved),
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
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const saves = () => calls.filter((call) => call.url.endsWith('/booking-rules-save'));
const render = (readOnly: 'support' | 'salon' | null = null, kind: 'staff' | 'support' = 'staff') =>
  renderWithMessages(<BookingRulesSettingsScreen kind={kind} timeZone="Asia/Colombo" readOnly={readOnly} />);

describe('the Settings page with Booking rules (F04-S04)', () => {
  it('shows Profile, Hours & closures, Booking rules and Branding in that order, and says rules save at once', () => {
    renderWithMessages(
      <SettingsScreen tab="rules">
        <p>content</p>
      </SettingsScreen>,
    );
    expect(
      within(screen.getByTestId('settings-tabs'))
        .getAllByRole('tab')
        .map((tab) => tab.textContent),
    ).toEqual(['Profile', 'Hours & closures', 'Booking rules', 'Branding']);
    expect(screen.getByTestId('settings-tab-rules')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('settings-intro')).toHaveTextContent('Booking rules save at once');
  });
});

describe('the Booking rules tab', () => {
  it('shows a loading state, then the suggested rules marked Not saved yet (AC-1)', async () => {
    render();
    expect(screen.getByTestId('rules-loading')).toHaveAttribute('aria-busy', 'true');

    expect(await screen.findByTestId('rules-not-saved')).toHaveTextContent('Not saved yet');
    expect(screen.getByTestId('rules-interval')).toHaveTextContent('15 minutes');
    expect(screen.getByTestId('rules-notice')).toHaveTextContent('1 hour ahead');
    expect(screen.getByTestId('rules-horizon')).toHaveTextContent('2 months ahead');
    expect(screen.getByTestId('rules-deposit-percentage')).toHaveAttribute('data-state', 'on');
    expect(screen.getByTestId('rules-percent')).toHaveValue('20');
    expect(screen.getByTestId('rules-cap')).toHaveValue('2000');
    expect(screen.getByTestId('rules-free')).toHaveTextContent('24 hours before');
    expect(screen.getByTestId('rules-late')).toHaveTextContent('The deposit');
    expect(screen.getByTestId('rules-noshow')).toHaveTextContent('The deposit');
    expect(screen.getByTestId('rules-reschedule')).toHaveTextContent('Twice');
    expect(screen.getByTestId('rules-until')).toHaveTextContent('12 hours before');
    expect(screen.getByTestId('rules-save')).toBeEnabled();
  });

  it('offers exactly the prototype’s choices in each list (AC-2)', async () => {
    render();
    const options = async (testId: string) => {
      fireEvent.keyDown(await screen.findByTestId(testId), { key: 'Enter' });
      const names = (await screen.findAllByRole('option')).map((option) => option.textContent);
      fireEvent.keyDown(screen.getAllByRole('option')[0]!, { key: 'Escape' });
      await waitFor(() => expect(screen.queryAllByRole('option')).toHaveLength(0));
      return names;
    };
    expect(await options('rules-interval')).toEqual(['10 minutes', '15 minutes', '20 minutes', '30 minutes']);
    expect(await options('rules-notice')).toEqual([
      'Any time before',
      '30 minutes ahead',
      '1 hour ahead',
      '2 hours ahead',
      '4 hours ahead',
      '1 day ahead',
    ]);
    expect(await options('rules-horizon')).toEqual([
      '2 weeks ahead',
      '1 month ahead',
      '2 months ahead',
      '3 months ahead',
      '6 months ahead',
    ]);
    expect(await options('rules-free')).toEqual([
      '2 hours before',
      '6 hours before',
      '12 hours before',
      '24 hours before',
      '48 hours before',
    ]);
    expect(await options('rules-late')).toEqual(['Nothing', 'The deposit', 'A share of the price']);
    expect(await options('rules-noshow')).toEqual(['The deposit', 'A share of the price']);
    expect(await options('rules-reschedule')).toEqual(['Not online', 'Once', 'Twice', 'Any number of times']);
    expect(await options('rules-until')).toEqual([
      '2 hours before',
      '6 hours before',
      '12 hours before',
      '24 hours before',
    ]);
  });

  it('updates what customers see as the form changes (AC-4)', async () => {
    render();
    fireEvent.click(await screen.findByTestId('rules-deposit-none'));
    expect(screen.getAllByTestId('rules-summary-sentence')[0]).toHaveTextContent('No deposit. You pay at the salon.');
    expect(screen.queryByTestId('rules-service-deposit')).toBeNull();
    expect(screen.getAllByTestId('rules-summary-sentence')[2]).toHaveTextContent(
      'After that, nothing is kept. If you don’t come, nothing is kept.',
    );
    await chooseOption(screen.getByTestId('rules-reschedule'), 'Not online');
    expect(screen.queryByTestId('rules-until')).toBeNull();
    expect(screen.getAllByTestId('rules-summary-sentence')[3]).toHaveTextContent('To reschedule, contact the salon.');

    fireEvent.click(screen.getByTestId('rules-deposit-amount'));
    fireEvent.change(screen.getByTestId('rules-amount'), { target: { value: '1500' } });
    expect(screen.getAllByTestId('rules-summary-sentence')[0]).toHaveTextContent(
      'Deposit: Rs. 1,500, taken when you book.',
    );
    expect(screen.getByTestId('rules-service-deposit')).toHaveTextContent(
      'A service can ask for a different deposit, set on the service.',
    );
  });

  it('shows the prototype’s message beside a broken field and disables Save until it is fixed (AC-3)', async () => {
    render();
    fireEvent.change(await screen.findByTestId('rules-cap'), { target: { value: '499' } });

    expect(screen.getByTestId('rules-cap-error')).toHaveTextContent(
      'Enter at least Rs. 500, or choose a fixed amount.',
    );
    expect(screen.getByTestId('rules-cap')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('rules-cap').getAttribute('aria-describedby')).toContain('rules-cap-error');
    expect(screen.getByTestId('rules-save')).toBeDisabled();
    expect(screen.getAllByTestId('rules-summary-sentence')[0]).toHaveTextContent('up to Rs. 2,000');

    fireEvent.change(screen.getByTestId('rules-percent'), { target: { value: '101' } });
    expect(screen.getByTestId('rules-percent-error')).toHaveTextContent('Enter 1 to 100.');
    fireEvent.click(screen.getByTestId('rules-deposit-amount'));
    fireEvent.change(screen.getByTestId('rules-amount'), { target: { value: '100001' } });
    expect(screen.getByTestId('rules-amount-error')).toHaveTextContent('Enter Rs. 100 to Rs. 100,000.');
    await chooseOption(screen.getByTestId('rules-late'), 'A share of the price');
    fireEvent.change(screen.getByTestId('rules-late-percent'), { target: { value: '0' } });
    expect(screen.getByTestId('rules-late-percent-error')).toHaveTextContent('Enter 1 to 100.');
    expect(screen.getByTestId('rules-late-percent')).toHaveAccessibleName('Share kept after a late cancellation');
    expect(screen.getByTestId('rules-save')).toBeDisabled();
    expect(saves()).toEqual([]);
  });

  it('saves at once as a new version from the version read, in minor units, and says so (AC-5)', async () => {
    answers['booking-rules'] = () => Response.json(saved(3));
    answers['booking-rules-save'] = () =>
      Response.json(saved(4, { ...RULES, deposit: { kind: 'amount', amountMinor: 250_000 } }));
    render();
    expect(await screen.findByTestId('rules-save')).toBeDisabled();
    expect(screen.queryByTestId('rules-not-saved')).toBeNull();
    fireEvent.click(screen.getByTestId('rules-deposit-amount'));
    fireEvent.change(screen.getByTestId('rules-amount'), { target: { value: '2,500' } });
    fireEvent.click(screen.getByTestId('rules-save'));

    await waitFor(() =>
      expect(saves()).toEqual([
        {
          url: '/identity-web/staff/booking-rules-save',
          body: { version: 3, rules: { ...RULES, deposit: { kind: 'amount', amountMinor: 250_000 } } },
        },
      ]),
    );
    expect(await screen.findByText('Booking rules saved. They apply to new bookings.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('rules-save')).toBeDisabled());
  });

  it('saves the suggested rules as they are before the first save', async () => {
    answers['booking-rules-save'] = () => Response.json(saved(1));
    render();
    fireEvent.click(await screen.findByTestId('rules-save'));
    await waitFor(() => expect(saves()[0]?.body).toEqual({ version: 0, rules: RULES }));
    await waitFor(() => expect(screen.queryByTestId('rules-not-saved')).toBeNull());
  });

  it('places the API’s field error beside its field', async () => {
    answers['booking-rules-save'] = () =>
      Response.json(
        { code: 'bad_request', errors: [{ field: 'rules.deposit.capMinor', detail: 'must be at least 50000' }] },
        { status: 400 },
      );
    render();
    fireEvent.click(await screen.findByTestId('rules-save'));
    expect(await screen.findByTestId('rules-cap-error')).toHaveTextContent('Enter at least Rs. 500');
    expect(screen.getByTestId('rules-fix')).toBeInTheDocument();
  });

  it('says someone else changed the rules, and Reload shows theirs (conflict)', async () => {
    answers['booking-rules'] = () => Response.json(saved(1));
    answers['booking-rules-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    render();
    await chooseOption(await screen.findByTestId('rules-interval'), '30 minutes');
    fireEvent.click(screen.getByTestId('rules-save'));
    expect(await screen.findByTestId('rules-conflict')).toHaveTextContent('Someone else changed the booking rules');

    answers['booking-rules'] = () => Response.json(saved(2, { ...RULES, slotIntervalMinutes: 10 }));
    fireEvent.click(screen.getByTestId('rules-conflict-reload'));
    await waitFor(() => expect(screen.getByTestId('rules-interval')).toHaveTextContent('10 minutes'));
  });

  it('says when nothing changed, and when the API cannot be reached keeps what was typed', async () => {
    answers['booking-rules'] = () => Response.json(saved(1));
    answers['booking-rules-save'] = () => Response.json({ code: 'invalid_state' }, { status: 409 });
    render();
    await chooseOption(await screen.findByTestId('rules-interval'), '30 minutes');
    fireEvent.click(screen.getByTestId('rules-save'));
    expect(await screen.findByTestId('rules-unchanged')).toHaveTextContent('Nothing changed');

    answers['booking-rules-save'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    fireEvent.click(screen.getByTestId('rules-save'));
    expect(await screen.findByTestId('rules-unavailable')).toHaveTextContent('Nothing was saved');
    expect(screen.getByTestId('rules-interval')).toHaveTextContent('30 minutes');
  });

  it('says the rules could not be reached when they cannot be read, with Try again', async () => {
    answers['booking-rules'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    render();
    expect(await screen.findByTestId('rules-unavailable')).toBeInTheDocument();
    answers['booking-rules'] = () => Response.json(unsaved);
    fireEvent.click(screen.getByTestId('rules-retry'));
    expect(await screen.findByTestId('rules-tab')).toBeInTheDocument();
  });

  it('lets a support view and a read-only salon read the rules and change nothing', async () => {
    answers['booking-rules'] = () => Response.json(saved(1));
    const view = render('support', 'support');
    expect(await screen.findByTestId('rules-read-only')).toHaveTextContent(
      'A support session can read the booking rules but cannot change it.',
    );
    expect(screen.getByTestId('rules-interval')).toBeDisabled();
    expect(screen.getByTestId('rules-deposit-none')).toBeDisabled();
    expect(screen.getByTestId('rules-save')).toBeDisabled();
    expect(screen.getByTestId('rules-history')).toBeEnabled();
    view.unmount();

    render('salon');
    expect(await screen.findByTestId('rules-read-only')).toHaveTextContent(
      'read-only until its overdue invoices are paid',
    );
    expect(screen.getByTestId('rules-cap')).toBeDisabled();
  });

  it('opens the history: each version newest first, by You or the author as the staff list names them (AC-7)', async () => {
    answers['booking-rules'] = () => Response.json(saved(2));
    const page = (number: number): BookingRulesHistory =>
      number === 0
        ? { items: [entry(2, ME, 'me@example.test')], page: 0, size: 1, total: 3 }
        : number === 1
          ? { items: [entry(1, 'another', 'manager@example.test')], page: 1, size: 1, total: 3 }
          : { items: [entry(1, 'gone', null)], page: 2, size: 1, total: 3 };
    answers['booking-rules-history'] = () => Response.json(page(0));
    render();
    fireEvent.click(await screen.findByTestId('rules-history'));

    const sheet = await screen.findByTestId('rules-history-sheet');
    const first = await within(sheet).findByTestId('rules-history-row');
    expect(first).toHaveAttribute('data-version', '2');
    expect(within(first).getByTestId('rules-history-by')).toHaveTextContent('You');
    expect(within(first).getByTestId('rules-history-changed')).toHaveTextContent('Changed: no-show');
    expect(within(first).getByTestId('rules-history-current')).toBeInTheDocument();
    expect(within(sheet).getByTestId('rules-history-page')).toHaveTextContent('Page 1 of 3');

    answers['booking-rules-history'] = () => Response.json(page(1));
    fireEvent.click(within(sheet).getByTestId('rules-history-older'));
    await waitFor(() =>
      expect(within(sheet).getByTestId('rules-history-by')).toHaveTextContent('manager@example.test'),
    );
    expect(within(sheet).getByTestId('rules-history-changed')).toHaveTextContent('Changed: start times, deposit');
    expect(calls.map((call) => call.url)).toContain('/identity-web/staff/booking-rules-history?page=1');

    answers['booking-rules-history'] = () => Response.json(page(2));
    fireEvent.click(within(sheet).getByTestId('rules-history-older'));
    await waitFor(() => expect(within(sheet).getByTestId('rules-history-by')).toHaveTextContent('A team member'));
  });

  it('shows an empty history before the first save', async () => {
    answers['booking-rules-history'] = () => Response.json({ items: [], page: 0, size: 10, total: 0 });
    render();
    fireEvent.click(await screen.findByTestId('rules-history'));
    expect(await screen.findByTestId('rules-history-empty')).toHaveTextContent('No versions yet');
  });

  it('asks before leaving with unsaved edits, by a link or by another Settings tab (AC-10)', async () => {
    answers['booking-rules'] = () => Response.json(saved(1));
    renderWithMessages(
      <>
        <a href="/workspace/staff" data-testid="elsewhere">
          Staff
        </a>
        <SettingsScreen tab="rules">
          <BookingRulesSettingsScreen kind="staff" timeZone="Asia/Colombo" readOnly={null} />
        </SettingsScreen>
      </>,
    );
    // Nothing is asked while nothing has changed.
    fireEvent.mouseDown(await screen.findByTestId('settings-tab-hours'));
    await waitFor(() => expect(push).toHaveBeenCalledWith('/workspace/settings/hours'));
    push.mockReset();

    await chooseOption(screen.getByTestId('rules-interval'), '30 minutes');
    fireEvent.click(screen.getByTestId('elsewhere'));
    fireEvent.click(within(await screen.findByTestId('rules-leave-dialog')).getByTestId('rules-leave-stay'));
    await waitFor(() => expect(screen.queryByTestId('rules-leave-dialog')).toBeNull());
    expect(push).not.toHaveBeenCalled();

    fireEvent.mouseDown(screen.getByTestId('settings-tab-branding'));
    const dialog = await screen.findByTestId('rules-leave-dialog');
    expect(push).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByTestId('rules-leave-confirm'));
    expect(push).toHaveBeenCalledWith('/workspace/settings/branding');

    const unload = new Event('beforeunload', { cancelable: true });
    act(() => {
      window.dispatchEvent(unload);
    });
    expect(unload.defaultPrevented).toBe(true);
  });
});
