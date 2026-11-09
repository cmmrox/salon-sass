import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { atWidth as setWidth, axe, BRANDS, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { AccountFrame } from './AccountFrame';
import { CATEGORIES, member, NIMALI, SERVICES, week, workday } from './team-fixtures';
import { TeamScreen } from './TeamScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F06-S02 AC-16: both tabs of the schedule sheet and their states have no WCAG 2.2 AA violation, colour contrast included,
 * at every width and with a dark and a light brand, rendered in real Chromium in the workspace frame, with no sideways
 * scrolling at any width, five breaks on a day included. The sheet keeps focus inside and returns it to Schedule.
 */
const FIVE = [
  { starts: '10:00', finishes: '10:15' },
  { starts: '11:00', finishes: '11:15' },
  { starts: '13:00', finishes: '14:00' },
  { starts: '15:00', finishes: '15:15' },
  { starts: '16:00', finishes: '16:15' },
];
const OPEN_SALON = {
  weekly: ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'].map((day) => ({
    day,
    open: day !== 'sunday',
    opens: day === 'sunday' ? null : '09:00',
    closes: day === 'sunday' ? null : '18:00',
  })),
  exceptions: [],
};
const WEEKLY = week(
  workday('monday', '08:00', '18:00', FIVE),
  workday('tuesday', '09:00', '18:00', [FIVE[2]!]),
  workday('sunday', '10:00', '12:00'),
);
const CHANGES = [
  { id: 'a', date: '2026-11-05', working: false, starts: null, finishes: null, breaks: [], note: 'Doctor' },
  {
    id: 'b',
    date: '2026-11-07',
    working: true,
    starts: '09:00',
    finishes: '14:00',
    breaks: [FIVE[0]],
    note: 'A note of the full sixty characters a person may write here',
  },
];

let answers: Record<string, () => Response | Promise<Response>>;
beforeEach(() => {
  answers = {
    principal: () => Response.json({ kind: 'staff', staffId: 'me', permissions: ['team.read', 'team.manage'] }),
    'team-members': () => Response.json({ items: [member(NIMALI, 'Nimali Perera', { weekly: WEEKLY })] }),
    'team-sign-in-accounts': () => Response.json({ items: [] }),
    'catalogue-categories': () => Response.json(CATEGORIES),
    'catalogue-services': () => Response.json(SERVICES),
    'team-schedule': () => Response.json({ version: 1, today: '2026-11-02', weekly: WEEKLY, dateChanges: CHANGES }),
    'team-opening-hours': () => Response.json(OPEN_SALON),
  };
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!.split('?')[0]!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});

function page(brand: string, canManage = true, readOnly: 'support' | 'salon' | null = null) {
  return renderWithMessages(
    <AccountFrame
      salon={{ slug: 'alpha', name: 'Alpha Studio', brandColour: brand, timeZone: 'Asia/Colombo' }}
      permissions={['session.manage', 'team.read', 'team.manage']}
      view="team"
    >
      <TeamScreen
        kind="staff"
        timeZone="Asia/Colombo"
        canManage={canManage}
        canBlock={canManage}
        canSeeAccounts={canManage}
        readOnly={readOnly}
      />
    </AccountFrame>,
  );
}

async function noViolations() {
  // Measured once overlays have finished opening: mid-fade colours are not what anyone reads.
  await waitFor(() => expect(document.getAnimations().filter((a) => a.playState === 'running')).toHaveLength(0));
  expect(await axe(document.body)).toHaveNoViolations();
}

const noSidewaysScrolling = () => {
  const sheet = screen.getByTestId('schedule-sheet');
  expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(document.documentElement.clientWidth);
  expect(sheet.scrollWidth).toBeLessThanOrEqual(sheet.clientWidth);
};

const open = async () => {
  fireEvent.click(await screen.findByTestId('team-card-schedule'));
  return screen.findByTestId('schedule-week');
};
const openTimeOff = async (tab: 'leave' | 'blocked') => {
  await open();
  const trigger = screen.getByTestId(`schedule-tab-${tab}`);
  fireEvent.mouseDown(trigger, { button: 0 });
  fireEvent.focus(trigger);
  return screen.findByTestId(tab === 'leave' ? 'schedule-leave' : 'schedule-blocks');
};
const openChanges = async () => {
  await open();
  const tab = screen.getByTestId('schedule-tab-changes');
  fireEvent.mouseDown(tab, { button: 0 });
  fireEvent.focus(tab);
  return screen.findByTestId('schedule-changes');
};

async function atWidth(width: number, work: () => Promise<void>) {
  await setWidth(width);
  await work();
}

const matrix = BRANDS.flatMap((brand) => WIDTHS.map((width) => [brand, width] as const));

describe('weekly hours (F06-S02 AC-16)', () => {
  it.each(matrix)('brand %s at %ipx, with five breaks on a day and the warning', async (brand, width) => {
    await atWidth(width, async () => {
      page(brand);
      await open();
      expect(screen.getByTestId('schedule-outside')).toBeVisible();
      expect(screen.getByTestId('schedule-monday-breaks-full')).toBeVisible();
      noSidewaysScrolling();
      await noViolations();
    });
  });
});

describe('date changes (F06-S02 AC-16)', () => {
  it.each(matrix)('brand %s at %ipx: the list and the form', async (brand, width) => {
    await atWidth(width, async () => {
      page(brand);
      await openChanges();
      expect(screen.getAllByTestId('change-row')).toHaveLength(2);
      noSidewaysScrolling();
      await noViolations();

      fireEvent.click(screen.getByTestId('change-add'));
      await screen.findByTestId('change-form');
      fireEvent.click(screen.getByTestId('change-add-break'));
      noSidewaysScrolling();
      await noViolations();
    });
  });
});

describe('leave and blocked time (F06-S03 AC-13)', () => {
  const LEAVE = [
    {
      id: 'l1',
      firstDay: '2026-11-12',
      lastDay: '2026-11-14',
      type: 'annual',
      note: 'A note of the full sixty characters a person may write here',
      bookingsToResolve: 2,
    },
    { id: 'l2', firstDay: '2026-12-01', lastDay: '2026-12-01', type: 'sick', note: null, bookingsToResolve: 0 },
  ];
  const BLOCKS = [
    {
      id: 'b1',
      date: '2026-11-05',
      starts: '10:00',
      finishes: '12:00',
      label: 'A label of forty characters, all of them',
      bookingsToResolve: 0,
    },
    { id: 'b2', date: '2026-11-06', starts: '09:00', finishes: '09:15', label: 'Errand', bookingsToResolve: 1 },
  ];
  const AFFECTED = {
    affected: [
      { date: '2026-11-12', time: '10:30', customerName: 'Kasun Perera', serviceName: 'Balayage' },
      { date: '2026-11-13', time: '15:00', customerName: 'Amara Silva', serviceName: 'Root touch-up' },
    ],
  };
  const withTimeOff = (overrides: Record<string, unknown> = {}) =>
    Response.json({
      version: 1,
      today: '2026-11-02',
      weekly: WEEKLY,
      dateChanges: [],
      leave: LEAVE,
      blockedTime: BLOCKS,
      ...overrides,
    });
  const refusedWith = (code: string, status: number) => () => Response.json({ code, errors: [] }, { status });
  const pick = async (control: string, day: string) => {
    fireEvent.click(screen.getByTestId(control));
    const calendar = await screen.findByTestId(`${control}-calendar`);
    fireEvent.click(
      within(calendar)
        .getAllByRole('button')
        .find((each) => each.textContent === day)!,
    );
    await waitFor(() => expect(screen.queryByTestId(`${control}-calendar`)).toBeNull());
  };

  beforeEach(() => {
    answers['team-schedule'] = () => withTimeOff();
    answers['team-leave-affected'] = () => Response.json(AFFECTED);
    answers['team-block-affected'] = () => Response.json(AFFECTED);
  });

  it.each(matrix)(
    'brand %s at %ipx: the Leave list, then its form with the bookings it affects',
    async (brand, width) => {
      await atWidth(width, async () => {
        page(brand);
        await openTimeOff('leave');
        expect(screen.getAllByTestId('leave-row')).toHaveLength(2);
        noSidewaysScrolling();
        await noViolations();

        fireEvent.click(screen.getByTestId('leave-add'));
        await screen.findByTestId('leave-form');
        await pick('leave-first', '12');
        await pick('leave-last', '14');
        await screen.findByTestId('period-affected');
        noSidewaysScrolling();
        await noViolations();
      });
    },
  );

  it.each(matrix)(
    'brand %s at %ipx: the Blocked time list, then its form with the bookings it affects',
    async (brand, width) => {
      await atWidth(width, async () => {
        page(brand);
        await openTimeOff('blocked');
        expect(screen.getAllByTestId('block-row')).toHaveLength(2);
        noSidewaysScrolling();
        await noViolations();

        fireEvent.click(screen.getByTestId('block-add'));
        await screen.findByTestId('block-form');
        await pick('block-date', '12');
        await screen.findByTestId('period-affected');
        noSidewaysScrolling();
        await noViolations();
      });
    },
  );

  it.each(WIDTHS)('empty at %ipx', async (width) => {
    answers['team-schedule'] = () => withTimeOff({ leave: [], blockedTime: [] });
    await atWidth(width, async () => {
      page(BRANDS[0]!);
      await openTimeOff('leave');
      expect(screen.getByTestId('leave-empty')).toBeVisible();
      await noViolations();
      const blocked = screen.getByTestId('schedule-tab-blocked');
      fireEvent.mouseDown(blocked, { button: 0 });
      fireEvent.focus(blocked);
      expect(await screen.findByTestId('block-empty')).toBeVisible();
      await noViolations();
    });
  });

  it('field errors, naming what to fix', async () => {
    await atWidth(WIDTHS[0]!, async () => {
      page(BRANDS[0]!);
      await openTimeOff('leave');
      fireEvent.click(screen.getByTestId('leave-add'));
      await screen.findByTestId('leave-form');
      fireEvent.click(screen.getByTestId('leave-save'));
      await screen.findByTestId('leave-problem');
      expect(screen.getByTestId('leave-first')).toHaveAttribute('aria-describedby', 'leave-problem');
      noSidewaysScrolling();
      await noViolations();
      fireEvent.click(screen.getByTestId('leave-cancel'));

      const blocked = screen.getByTestId('schedule-tab-blocked');
      fireEvent.mouseDown(blocked, { button: 0 });
      fireEvent.focus(blocked);
      fireEvent.click(await screen.findByTestId('block-add'));
      await screen.findByTestId('block-form');
      fireEvent.click(screen.getByTestId('block-save'));
      await screen.findByTestId('block-problem');
      expect(screen.getByTestId('block-date')).toHaveAttribute('aria-describedby', 'block-problem');
      await noViolations();
    });
  });

  it('an overlap, and the question about bookings going unanswered', async () => {
    answers['team-leave-affected'] = refusedWith('service_unavailable', 503);
    answers['team-leave-add'] = refusedWith('conflict', 409);
    await atWidth(WIDTHS[0]!, async () => {
      page(BRANDS[0]!);
      await openTimeOff('leave');
      fireEvent.click(screen.getByTestId('leave-add'));
      await screen.findByTestId('leave-form');
      await pick('leave-first', '12');
      await pick('leave-last', '14');
      await screen.findByTestId('period-unavailable');
      fireEvent.click(screen.getByTestId('leave-save'));
      await screen.findByTestId('leave-problem');
      noSidewaysScrolling();
      await noViolations();
    });
  });

  it('refused, unavailable and someone else’s change', async () => {
    answers['team-block-remove'] = refusedWith('permission_denied', 403);
    await atWidth(WIDTHS[0]!, async () => {
      page(BRANDS[0]!);
      await openTimeOff('blocked');
      fireEvent.click(screen.getAllByTestId('block-remove')[0]!);
      await screen.findByTestId('schedule-refused');
      await noViolations();
    });
  });

  it('asking before Remove, on top of the sheet', async () => {
    await atWidth(WIDTHS[0]!, async () => {
      page(BRANDS[0]!);
      await openTimeOff('leave');
      fireEvent.click(screen.getAllByTestId('leave-remove')[0]!);
      await screen.findByTestId('leave-remove-dialog');
      await noViolations();
    });
  });

  it.each(['support', 'salon'] as const)('read-only with only the days, %s', async (readOnly) => {
    answers['team-schedule'] = () =>
      withTimeOff({
        leave: [{ id: 'l1', firstDay: '2026-11-12', lastDay: '2026-11-14', bookingsToResolve: 2 }],
      });
    await atWidth(WIDTHS[0]!, async () => {
      page(BRANDS[0]!, false, readOnly);
      await openTimeOff('leave');
      expect(screen.queryByTestId('leave-add')).toBeNull();
      expect(screen.queryByTestId('leave-row-type')).toBeNull();
      await noViolations();
    });
  });

  it('names every control, reaches each from the keyboard, and moves to both new tabs with the arrow keys', async () => {
    await atWidth(WIDTHS[0]!, async () => {
      page(BRANDS[0]!);
      await open();
      const week = screen.getByTestId('schedule-tab-week');
      week.focus();
      fireEvent.keyDown(week, { key: 'End' });
      expect(await screen.findByTestId('schedule-blocks')).toBeVisible();
      fireEvent.keyDown(screen.getByTestId('schedule-tab-blocked'), { key: 'ArrowLeft' });
      expect(await screen.findByTestId('schedule-leave')).toBeVisible();

      const sheet = screen.getByTestId('schedule-sheet');
      expect(within(sheet).getAllByRole('button', { name: /^Remove leave, / })).toHaveLength(2);
      fireEvent.click(screen.getByTestId('leave-add'));
      await screen.findByTestId('leave-form');
      expect(within(sheet).getByRole('button', { name: 'First day' })).toBeVisible();
      expect(within(sheet).getByRole('button', { name: 'Last day' })).toBeVisible();
      expect(within(sheet).getByRole('combobox', { name: 'Type' })).toBeVisible();
      expect(within(sheet).getByRole('textbox', { name: /Note/ })).toBeVisible();
    });
  });
});

describe('the states (F06-S02 AC-16)', () => {
  const brand = BRANDS[0]!;
  const width = WIDTHS[0]!;

  it('loading', async () => {
    answers['team-schedule'] = () => new Promise<Response>(() => {});
    await atWidth(width, async () => {
      page(brand);
      fireEvent.click(await screen.findByTestId('team-card-schedule'));
      await screen.findByTestId('schedule-loading');
      await noViolations();
    });
  });

  it('unavailable', async () => {
    answers['team-schedule'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    await atWidth(width, async () => {
      page(brand);
      fireEvent.click(await screen.findByTestId('team-card-schedule'));
      await screen.findByTestId('schedule-unavailable');
      await noViolations();
    });
  });

  it.each(['support', 'salon'] as const)('read-only, %s', async (readOnly) => {
    await atWidth(width, async () => {
      page(brand, false, readOnly);
      await open();
      expect(screen.getByTestId('schedule-read-only')).toBeVisible();
      noSidewaysScrolling();
      await noViolations();
    });
  });

  it('empty, and the card with no working days', async () => {
    answers['team-schedule'] = () =>
      Response.json({ version: 0, today: '2026-11-02', weekly: week(), dateChanges: [] });
    answers['team-members'] = () => Response.json({ items: [member(NIMALI, 'Nimali Perera')] });
    await atWidth(width, async () => {
      page(brand);
      expect(await screen.findByTestId('team-card-hours')).toHaveTextContent('No working days');
      await noViolations();
      await openChanges();
      expect(screen.getByTestId('change-empty')).toBeVisible();
      await noViolations();
    });
  });

  it('field errors, with a day’s and a date’s rule said', async () => {
    answers['team-schedule'] = () =>
      Response.json({
        version: 1,
        today: '2026-11-02',
        weekly: week(workday('monday', '18:00', '09:00')),
        dateChanges: [],
      });
    await atWidth(width, async () => {
      page(brand);
      await open();
      expect(screen.getByTestId('schedule-problems')).toBeVisible();
      await noViolations();

      const tab = screen.getByTestId('schedule-tab-changes');
      fireEvent.mouseDown(tab, { button: 0 });
      fireEvent.focus(tab);
      fireEvent.click(await screen.findByTestId('change-add'));
      fireEvent.click(await screen.findByTestId('change-save'));
      expect(await screen.findByTestId('change-problems')).toBeVisible();
      await noViolations();
    });
  });

  it('conflict, refused and bookings left outside', async () => {
    const booking = { date: '2026-11-09', time: '10:30', customerName: 'Kasun Silva', serviceName: 'Balayage' };
    await atWidth(width, async () => {
      page(brand);
      await open();
      answers['team-week-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
      fireEvent.click(screen.getByTestId('schedule-wednesday-working'));
      fireEvent.click(screen.getByTestId('schedule-save'));
      expect(await screen.findByTestId('schedule-conflict')).toBeVisible();
      await noViolations();

      answers['team-week-save'] = () => Response.json({ version: 2, weekly: WEEKLY, affected: [booking] });
      fireEvent.click(screen.getByTestId('schedule-save'));
      expect(await screen.findByTestId('schedule-affected')).toBeVisible();
      noSidewaysScrolling();
      await noViolations();

      answers['team-week-save'] = () => Response.json({ code: 'permission_denied' }, { status: 403 });
      fireEvent.click(screen.getByTestId('schedule-thursday-working'));
      fireEvent.click(screen.getByTestId('schedule-save'));
      expect(await screen.findByTestId('schedule-refused')).toBeVisible();
      await noViolations();
    });
  });

  it('the calendar of a date change', async () => {
    await atWidth(width, async () => {
      page(brand);
      await openChanges();
      fireEvent.click(screen.getByTestId('change-add'));
      await screen.findByTestId('change-form');
      fireEvent.click(screen.getByTestId('change-date'));
      await screen.findByTestId('change-calendar');
      await noViolations();
    });
  });
});

describe('keyboard use (F06-S02 AC-16)', () => {
  it('keeps focus inside the sheet, and gives it back to Schedule on close', async () => {
    await atWidth(WIDTHS[0]!, async () => {
      page(BRANDS[0]!);
      const schedule = await screen.findByTestId('team-card-schedule');
      schedule.focus();
      fireEvent.click(schedule);
      const sheet = await screen.findByTestId('schedule-sheet');
      await screen.findByTestId('schedule-week');
      await waitFor(() => expect(sheet.contains(document.activeElement)).toBe(true));

      fireEvent.keyDown(document.activeElement!, { key: 'Escape' });

      await waitFor(() => expect(screen.queryByTestId('schedule-sheet')).toBeNull());
      await waitFor(() => expect(document.activeElement).toBe(schedule));
    });
  });

  it('names every control by its weekday and break, and moves between the tabs with the arrow keys', async () => {
    await atWidth(WIDTHS[0]!, async () => {
      page(BRANDS[0]!);
      await open();
      const sheet = screen.getByTestId('schedule-sheet');
      expect(within(sheet).getByRole('combobox', { name: 'Monday break 5 ends' })).toBeVisible();
      expect(within(sheet).getByRole('button', { name: 'Remove Monday break 5' })).toBeVisible();

      const week = screen.getByTestId('schedule-tab-week');
      week.focus();
      fireEvent.keyDown(week, { key: 'ArrowRight' });

      expect(await screen.findByTestId('schedule-changes')).toBeVisible();
    });
  });
});
