import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { chooseOption } from '@test/select';
import {
  ACCOUNTS,
  CATEGORIES,
  COLOUR,
  FREE_ACCOUNT,
  KASUN,
  NIMALI,
  SERVICES,
  TEAM,
  TINT,
  member,
} from './team-fixtures';
import { TeamScreen } from './TeamScreen';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));

type Call = { action: string; body: unknown };
let calls: Call[];
let answers: Record<string, (body: unknown) => Response>;
beforeEach(() => {
  push.mockReset();
  calls = [];
  answers = {
    principal: () => Response.json({ kind: 'staff', staffId: 'me', permissions: ['team.read', 'team.manage'] }),
    'team-members': () => Response.json(TEAM),
    'team-sign-in-accounts': () => Response.json(ACCOUNTS),
    'catalogue-categories': () => Response.json(CATEGORIES),
    'catalogue-services': () => Response.json(SERVICES),
  };
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  // jsdom has no ResizeObserver; Radix measures with one.
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const body = init?.body instanceof FormData ? init.body : init?.body ? JSON.parse(String(init.body)) : null;
    const action = url.split('/').pop()!.split('?')[0]!;
    calls.push({ action, body });
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))(body);
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const render = (canManage = true, canSeeAccounts = true, readOnly: 'support' | 'salon' | null = null) =>
  renderWithMessages(
    <TeamScreen
      kind="staff"
      canManage={canManage}
      canBlock={canManage}
      canSeeAccounts={canSeeAccounts}
      readOnly={readOnly}
    />,
  );
const sent = (action: string) => calls.filter((call) => call.action === action).map((call) => call.body);
const card = (name: string) =>
  screen.getAllByTestId('team-card').find((each) => within(each).getByTestId('team-card-name').textContent === name)!;
const problem =
  (code: string, status: number, errors: unknown[] = []) =>
  () =>
    Response.json({ code, errors }, { status });
const editNimali = async () => {
  await screen.findAllByTestId('team-card');
  fireEvent.click(within(card('Nimali Perera')).getByTestId('team-card-edit'));
  await screen.findByTestId('team-sheet');
};
const openNew = async () => {
  fireEvent.click(await screen.findByTestId('team-add'));
  await screen.findByTestId('team-sheet');
};

describe('the Team tab (F06-S01 AC-12)', () => {
  it('shows a loading state, then a card per person in the order the API gives', async () => {
    render();
    expect(screen.getByTestId('team-loading')).toHaveAttribute('aria-busy', 'true');

    const cards = await screen.findAllByTestId('team-card');

    expect(cards.map((each) => within(each).getByTestId('team-card-name').textContent)).toEqual([
      'Kasun Silva',
      'Nimali Perera',
    ]);
    expect(screen.getByTestId('team-summary')).toHaveTextContent('2 people · 1 shown for online booking');
  });

  it('says one person in the singular', async () => {
    answers['team-members'] = () => Response.json({ items: [TEAM.items[1]] });
    render();

    await screen.findAllByTestId('team-card');

    expect(screen.getByTestId('team-summary')).toHaveTextContent('1 person · 1 shown for online booking');
  });

  it('shows the categories performed, the online badge and, with staff.list, the sign-in', async () => {
    render();
    await screen.findAllByTestId('team-card');

    const nimali = within(card('Nimali Perera'));
    expect(nimali.getByTestId('team-card-title')).toHaveTextContent('Colour Specialist');
    expect(nimali.getByTestId('team-card-bio')).toHaveTextContent('Ten years of colour work.');
    expect(nimali.getByTestId('team-card-services')).toHaveTextContent('Colour');
    expect(nimali.getByTestId('team-card-online')).toHaveTextContent('Shown');
    expect(nimali.getByTestId('team-card-sign-in')).toHaveTextContent('nimali@example.test');
    const kasun = within(card('Kasun Silva'));
    expect(kasun.getByTestId('team-card-services')).toHaveTextContent('Nails');
    expect(kasun.getByTestId('team-card-online')).toHaveTextContent('Hidden');
    expect(kasun.getByTestId('team-card-sign-in')).toHaveTextContent('None');
  });

  it('says None yet for someone who performs nothing', async () => {
    answers['team-members'] = () => Response.json({ items: [member(KASUN, 'Kasun Silva')] });
    render();

    await screen.findAllByTestId('team-card');

    expect(within(card('Kasun Silva')).getByTestId('team-card-services')).toHaveTextContent('None yet');
  });

  it('shows the initial on one of the six colours with no photo', async () => {
    answers['team-members'] = () => Response.json({ items: [member(KASUN, 'Kasun Silva')] });
    render();

    await screen.findAllByTestId('team-card');

    const initial = within(card('Kasun Silva')).getByTestId('team-member-initial');
    expect(initial).toHaveTextContent('K');
    expect(initial.className).toMatch(/bg-avatar-[1-6]/);
    expect(within(card('Kasun Silva')).queryByTestId('team-member-photo')).toBeNull();
  });

  it('shows the photo through the web’s own route, never the API’s, once it has loaded', async () => {
    // jsdom loads no images; Radix Avatar shows its image only once the browser says it has.
    vi.stubGlobal(
      'Image',
      class {
        complete = true;
        naturalWidth = 320;
        src = '';
        referrerPolicy = '';
        crossOrigin = '';
        addEventListener() {}
        removeEventListener() {}
      },
    );
    answers['team-members'] = () =>
      Response.json({ items: [member(KASUN, 'Kasun Silva', { photoId: 'p1', photoUrl: '/api/v1/team/photos/p1' })] });
    render();

    await screen.findAllByTestId('team-card');

    const photo = await within(card('Kasun Silva')).findByTestId('team-member-photo');
    expect(photo).toHaveAttribute('src', '/team-photos/p1');
    expect(within(card('Kasun Silva')).queryByTestId('team-member-initial')).toBeNull();
  });

  it('shows the Empty state and the add action with team.manage', async () => {
    answers['team-members'] = () => Response.json({ items: [] });
    render();

    const empty = await screen.findByTestId('team-empty');

    expect(empty).toHaveTextContent('Add your first team member');
    expect(within(empty).getByTestId('team-empty-add')).toHaveTextContent('+ Add team member');
    expect(screen.getByTestId('team-summary')).toHaveTextContent('No team members yet');
  });

  it('shows no add action in the Empty state without team.manage', async () => {
    answers['team-members'] = () => Response.json({ items: [] });
    render(false, false);

    const empty = await screen.findByTestId('team-empty');

    expect(within(empty).queryByTestId('team-empty-add')).toBeNull();
  });
});

describe('roles (F06-S01 AC-11, AC-13)', () => {
  it('shows the tab bar and Sign-in with staff.list', async () => {
    render();
    await screen.findAllByTestId('team-card');

    expect(screen.getByTestId('team-tab-team')).toBeInTheDocument();
    expect(screen.getByTestId('team-tab-accounts')).toBeInTheDocument();
  });

  it('reads the team without tabs, Sign-in, Add or Edit for one who only has team.read', async () => {
    answers['team-members'] = () =>
      Response.json({
        items: TEAM.items.map((item) =>
          Object.fromEntries(Object.entries(item).filter(([field]) => !field.startsWith('signIn'))),
        ),
      });
    render(false, false);
    await screen.findAllByTestId('team-card');

    expect(screen.queryByTestId('team-tabs')).toBeNull();
    expect(screen.queryByTestId('team-add')).toBeNull();
    expect(screen.queryByTestId('team-card-edit')).toBeNull();
    expect(screen.queryByTestId('team-card-sign-in')).toBeNull();
    expect(sent('team-sign-in-accounts')).toHaveLength(0);
  });

  it('says why a support session or a read-only salon cannot change anything', async () => {
    render(false, true, 'support');

    expect(await screen.findByTestId('team-read-only')).toHaveTextContent('A support session can read the team');
    expect(screen.queryByTestId('team-add')).toBeNull();
  });
});

describe('adding a person (F06-S01 AC-1)', () => {
  it('disables Add to team and says why while a rule is broken', async () => {
    render();
    await openNew();

    expect(screen.getByTestId('team-sheet-save')).toBeDisabled();
    expect(screen.getByTestId('team-sheet-problem')).toHaveTextContent('Name must be 2 to 40 characters.');

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Ka' } });
    fireEvent.change(screen.getByTestId('team-title'), { target: { value: 'x'.repeat(41) } });

    expect(screen.getByTestId('team-sheet-save')).toBeDisabled();
    expect(screen.getByTestId('team-sheet-problem')).toHaveTextContent('Title must be 40 characters or fewer.');
  });

  it.each([
    ['team-highlights', 61, 'Highlights must be 60 characters or fewer.'],
    ['team-bio', 141, 'Bio must be 140 characters or fewer.'],
  ])('says %s is over its limit, in the words the lead ruled', async (field, length, message) => {
    render();
    await openNew();

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Ka' } });
    fireEvent.change(screen.getByTestId(field), { target: { value: 'x'.repeat(length) } });

    expect(screen.getByTestId('team-sheet-problem')).toHaveTextContent(message);
    expect(screen.getByTestId('team-sheet-save')).toBeDisabled();
  });

  it('keeps a live count of the bio', async () => {
    render();
    await openNew();

    fireEvent.change(screen.getByTestId('team-bio'), { target: { value: 'x'.repeat(65) } });

    expect(screen.getByTestId('team-bio-count')).toHaveTextContent('65/140');
  });

  it('is on for online booking by default, says a photo comes after, and offers no removal', async () => {
    render();
    await openNew();

    expect(screen.getByTestId('team-online')).toHaveAttribute('data-state', 'checked');
    expect(screen.getByTestId('team-photo-after-add')).toHaveTextContent('Add a photo after you add this person.');
    expect(screen.queryByTestId('team-sheet-remove')).toBeNull();
    expect(screen.getByTestId('team-sheet-save')).toHaveTextContent('Add to team');
  });

  it('saves the person, says so and reads the team again', async () => {
    answers['team-member-add'] = () => Response.json(member('new', 'Amal'), { status: 201 });
    render();
    await openNew();

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: '  Amal  ' } });
    fireEvent.click(screen.getByTestId('team-sheet-save'));

    await waitFor(() => expect(sent('team-member-add')).toHaveLength(1));
    expect(sent('team-member-add')[0]).toMatchObject({
      name: 'Amal',
      shownOnline: true,
      signInAccountId: null,
      services: { categoryIds: [], serviceIds: [] },
    });
    expect(await screen.findByText('Added to the team.')).toBeInTheDocument();
    await waitFor(() => expect(sent('team-members').length).toBeGreaterThan(1));
  });

  it('shows the seat message and keeps the sheet when no seat is free', async () => {
    answers['team-member-add'] = problem('entitlement_exceeded', 409, [{ field: 'staffSeats', detail: 'x' }]);
    render();
    await openNew();

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Amal' } });
    fireEvent.click(screen.getByTestId('team-sheet-save'));

    const rule = await screen.findByTestId('team-rule');
    expect(rule).toHaveAttribute('data-reason', 'seat');
    expect(rule).toHaveTextContent('no free staff seat');
    expect(screen.getByTestId('team-sheet')).toBeInTheDocument();
  });

  it('says the limit at the 201st', async () => {
    answers['team-member-add'] = problem('limit_reached', 409);
    render();
    await openNew();

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Amal' } });
    fireEvent.click(screen.getByTestId('team-sheet-save'));

    expect(await screen.findByTestId('team-rule')).toHaveTextContent(
      'A salon can have up to 200 team members. Remove someone first.',
    );
  });

  it('shows a refused field from the API and disables saving until it is changed', async () => {
    answers['team-member-add'] = problem('bad_request', 400, [{ field: 'signInAccountId', detail: 'x' }]);
    render();
    await openNew();

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Amal' } });
    fireEvent.click(screen.getByTestId('team-sheet-save'));

    expect(await screen.findByTestId('team-sheet-problem')).toHaveAttribute('data-problem', 'signInAccountId');
    expect(screen.getByTestId('team-sheet-save')).toBeDisabled();

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Amala' } });

    expect(screen.queryByTestId('team-sheet-problem')).toBeNull();
  });
});

describe('what a person performs (F06-S01 AC-3)', () => {
  const rowOf = (name: string) =>
    screen.getAllByTestId('team-picker-row').find((each) => within(each).getByRole('checkbox', { name }) !== null)!;
  const row = (name: string) => {
    const all = screen.getAllByTestId('team-picker-row');
    return all.find((each) => within(each).queryByRole('checkbox', { name })) ?? rowOf(name);
  };

  it('lists the categories with services, and says what each covers', async () => {
    render();
    await editNimali();

    const rows = screen.getAllByTestId('team-picker-row');
    expect(rows).toHaveLength(2);
    expect(within(row('Colour')).getByTestId('team-picker-summary')).toHaveTextContent(
      'All 3 services, and any added later',
    );
    expect(within(row('Nails')).getByTestId('team-picker-summary')).toHaveTextContent('Not performed');
  });

  it('says one service in the singular', async () => {
    render();
    await editNimali();

    fireEvent.click(within(row('Nails')).getByRole('checkbox', { name: 'Nails' }));

    expect(within(row('Nails')).getByTestId('team-picker-summary')).toHaveTextContent('1 service, and any added later');
  });

  it('narrows a ticked category with Choose, named for the category', async () => {
    answers['team-member-save'] = () => Response.json(TEAM.items[1]);
    render();
    await editNimali();

    fireEvent.click(within(row('Colour')).getByRole('button', { name: 'Choose services in Colour' }));
    fireEvent.click(within(row('Colour')).getByRole('button', { name: 'Tint' }));

    expect(within(row('Colour')).getByTestId('team-picker-summary')).toHaveTextContent('2 of 3 services');
    fireEvent.click(screen.getByTestId('team-sheet-save'));
    await waitFor(() => expect(sent('team-member-save')).toHaveLength(1));
    const saved = sent('team-member-save')[0] as { services: { categoryIds: string[]; serviceIds: string[] } };
    expect(saved.services.categoryIds).toEqual([]);
    expect(saved.services.serviceIds).toHaveLength(2);
    expect(saved.services.serviceIds).not.toContain(TINT);
  });

  it('says which services keep a person booked, and nothing changes', async () => {
    answers['team-member-save'] = problem('in_use', 409, [
      { field: 'services', detail: 'Tint' },
      { field: 'services', detail: 'Gloss' },
    ]);
    render();
    await editNimali();

    fireEvent.click(within(row('Colour')).getByRole('checkbox', { name: 'Colour' }));
    fireEvent.click(screen.getByTestId('team-sheet-save'));

    expect(await screen.findByTestId('team-rule')).toHaveTextContent(
      'This person has upcoming bookings for Tint, Gloss. Move them first.',
    );
  });
});

describe('editing a person (F06-S01 AC-2, AC-5)', () => {
  it('saves from the version read, says so and closes', async () => {
    answers['team-member-save'] = () => Response.json(TEAM.items[1]);
    render();
    await editNimali();

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Nimali P' } });
    fireEvent.click(screen.getByTestId('team-sheet-save'));

    await waitFor(() => expect(sent('team-member-save')).toHaveLength(1));
    expect(sent('team-member-save')[0]).toMatchObject({
      id: NIMALI,
      version: 0,
      name: 'Nimali P',
      services: { categoryIds: [COLOUR], serviceIds: [] },
    });
    // An earlier test's toast may still be on screen: sonner keeps its own queue across renders.
    expect((await screen.findAllByText('Profile saved.')).length).toBeGreaterThan(0);
  });

  it('says when nothing has changed', async () => {
    answers['team-member-save'] = problem('invalid_state', 409);
    render();
    await editNimali();

    fireEvent.click(screen.getByTestId('team-sheet-save'));

    expect(await screen.findByTestId('team-rule')).toHaveAttribute('data-reason', 'unchanged');
  });

  it('shows the conflict in the sheet, and Reload brings the person as they are now', async () => {
    answers['team-member-save'] = problem('conflict', 409);
    render();
    await editNimali();

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Nimali P' } });
    fireEvent.click(screen.getByTestId('team-sheet-save'));

    const conflict = await screen.findByTestId('team-conflict');
    fireEvent.click(within(conflict).getByTestId('team-conflict-reload'));

    await waitFor(() => expect(screen.getByTestId('team-name')).toHaveValue('Nimali Perera'));
    expect(screen.queryByTestId('team-conflict')).toBeNull();
  });

  const offered = async () => {
    fireEvent.keyDown(screen.getByTestId('team-sign-in'), { key: 'Enter' });
    return (await screen.findAllByRole('option')).map((option) => option.textContent);
  };
  /** The accounts as the API lists them, with one more that Kasun holds. */
  const withKasunsAccount = () =>
    Response.json({
      items: [...ACCOUNTS.items, { id: 'kasuns', label: 'kasun@example.test', role: 'PROFESSIONAL', memberId: KASUN }],
    });

  it('offers no sign-in, this person’s own account and the free ones, never one another person holds', async () => {
    answers['team-sign-in-accounts'] = withKasunsAccount;
    render();
    await editNimali();

    expect(screen.getByTestId('team-sign-in')).toHaveTextContent('nimali@example.test');
    expect(await offered()).toEqual([
      'No sign-in. They can still take bookings',
      'nimali@example.test',
      'free@example.test',
    ]);
  });

  it('offers a new person only the free accounts, with no sign-in chosen', async () => {
    answers['team-sign-in-accounts'] = withKasunsAccount;
    render();
    await openNew();

    expect(screen.getByTestId('team-sign-in')).toHaveTextContent('No sign-in. They can still take bookings');
    expect(await offered()).toEqual(['No sign-in. They can still take bookings', 'free@example.test']);
  });

  it('sends the account chosen, and null when the link is taken off', async () => {
    answers['team-member-save'] = () => Response.json(TEAM.items[1]);
    render();
    await editNimali();

    await chooseOption(screen.getByTestId('team-sign-in'), 'free@example.test');
    fireEvent.click(screen.getByTestId('team-sheet-save'));
    await waitFor(() => expect(sent('team-member-save')).toHaveLength(1));
    expect(sent('team-member-save')[0]).toMatchObject({ signInAccountId: FREE_ACCOUNT });

    await waitFor(() => expect(screen.queryByTestId('team-sheet')).toBeNull());
    await editNimali();
    await chooseOption(screen.getByTestId('team-sign-in'), 'No sign-in. They can still take bookings');
    fireEvent.click(screen.getByTestId('team-sheet-save'));
    await waitFor(() => expect(sent('team-member-save')).toHaveLength(2));
    expect(sent('team-member-save')[1]).toMatchObject({ signInAccountId: null });
  });
});

describe('removing a person (F06-S01 AC-7)', () => {
  it('asks first, and then says so', async () => {
    answers['team-member-remove'] = () => Response.json({ ok: true });
    render();
    await editNimali();

    fireEvent.click(screen.getByTestId('team-sheet-remove'));
    const dialog = await screen.findByTestId('team-remove-dialog');
    expect(dialog).toHaveTextContent('Remove Nimali Perera?');
    expect(dialog).toHaveTextContent('Past bookings keep their name.');
    expect(sent('team-member-remove')).toHaveLength(0);

    fireEvent.click(within(dialog).getByTestId('team-remove-confirm'));

    await waitFor(() => expect(sent('team-member-remove')).toEqual([{ id: NIMALI, version: 0 }]));
    expect(await screen.findByText('Removed from the team.')).toBeInTheDocument();
  });

  it('says how many upcoming bookings stop it, and the person stays', async () => {
    answers['team-member-remove'] = problem('in_use', 409, [{ field: 'upcomingBookings', detail: '3' }]);
    render();
    await editNimali();

    fireEvent.click(screen.getByTestId('team-sheet-remove'));
    fireEvent.click(within(await screen.findByTestId('team-remove-dialog')).getByTestId('team-remove-confirm'));

    expect(await screen.findByTestId('team-rule')).toHaveTextContent(
      'Nimali Perera has 3 upcoming bookings. Move or cancel them first.',
    );
    expect(screen.getByTestId('team-sheet')).toBeInTheDocument();
  });

  it('says one upcoming booking in the singular', async () => {
    answers['team-member-remove'] = problem('in_use', 409, [{ field: 'upcomingBookings', detail: '1' }]);
    render();
    await editNimali();

    fireEvent.click(screen.getByTestId('team-sheet-remove'));
    fireEvent.click(within(await screen.findByTestId('team-remove-dialog')).getByTestId('team-remove-confirm'));

    expect(await screen.findByTestId('team-rule')).toHaveTextContent('Nimali Perera has 1 upcoming booking.');
  });
});

describe('a person’s photo (F06-S01 AC-9)', () => {
  const file = new File(['x'], 'p.png', { type: 'image/png' });

  it('uploads at once, naming the person in the form', async () => {
    answers['team-photo'] = () => Response.json(member(NIMALI, 'Nimali Perera', { photoId: 'p2', version: 1 }));
    render();
    await editNimali();

    fireEvent.change(screen.getByTestId('team-photo-file'), { target: { files: [file] } });

    await waitFor(() => expect(sent('team-photo')).toHaveLength(1));
    expect((sent('team-photo')[0] as FormData).get('memberId')).toBe(NIMALI);
    expect(await screen.findByText('Photo saved.')).toBeInTheDocument();
  });

  it('says why a file was refused, and names the control for the person', async () => {
    answers['team-photo'] = problem('bad_request', 400, [{ field: 'file', detail: 'x' }]);
    render();
    await editNimali();

    expect(screen.getByRole('button', { name: 'Upload photo of Nimali Perera' })).toBeInTheDocument();
    fireEvent.change(screen.getByTestId('team-photo-file'), { target: { files: [file] } });

    expect(await screen.findByTestId('team-photo-error')).toHaveTextContent('This file can’t be used.');
  });
});

describe('leaving and failing (F06-S01 AC-16)', () => {
  it('asks before closing the sheet with unsaved edits, and keeps them on Stay', async () => {
    render();
    await editNimali();

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Edited' } });
    fireEvent.click(screen.getByTestId('team-sheet-close'));

    fireEvent.click(within(await screen.findByTestId('team-leave-dialog')).getByTestId('team-leave-stay'));

    expect(screen.getByTestId('team-name')).toHaveValue('Edited');
  });

  it('closes at once with nothing unsaved', async () => {
    render();
    await editNimali();

    fireEvent.click(screen.getByTestId('team-sheet-close'));

    await waitFor(() => expect(screen.queryByTestId('team-sheet')).toBeNull());
    expect(screen.queryByTestId('team-leave-dialog')).toBeNull();
  });

  it('says when the team cannot be reached, and offers to try again', async () => {
    answers['team-members'] = problem('service_unavailable', 503);
    render();

    expect(await screen.findByTestId('team-unavailable')).toBeInTheDocument();

    answers['team-members'] = () => Response.json(TEAM);
    fireEvent.click(screen.getByTestId('team-retry'));

    expect(await screen.findAllByTestId('team-card')).toHaveLength(2);
    expect(screen.queryByTestId('team-unavailable')).toBeNull();
  });

  it('goes read-only and says so when a change is refused', async () => {
    answers['team-member-save'] = problem('permission_denied', 403);
    render();
    await editNimali();

    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Nimali P' } });
    fireEvent.click(screen.getByTestId('team-sheet-save'));

    expect(await screen.findByTestId('team-refused')).toBeInTheDocument();
    expect(screen.queryByTestId('team-add')).toBeNull();
    expect(screen.queryByTestId('team-card-edit')).toBeNull();
  });
});
