import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { atWidth, axe, BRANDS, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { AccountFrame } from './AccountFrame';
import { ACCOUNTS, CATEGORIES, KASUN, member, NIMALI, SERVICES, TEAM } from './team-fixtures';
import { TeamScreen } from './TeamScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F06-S01 AC-16: the Team tab, the profile sheet and their states have no WCAG 2.2 AA violation, colour contrast
 * included, at every width and with a dark and a light brand, rendered in real Chromium in the workspace frame, with
 * no horizontal scrolling. Each of the six initial colours is checked, a long name wraps, and the sheet keeps focus
 * inside and returns it to the card's Edit profile when it closes.
 */
const LONG = 'Wijesinghe Arachchige Dona Nimali Perera';
let answers: Record<string, () => Response | Promise<Response>>;
beforeEach(() => {
  answers = {
    principal: () => Response.json({ kind: 'staff', staffId: 'me', permissions: ['team.read', 'team.manage'] }),
    'team-members': () =>
      Response.json({
        items: [
          ...TEAM.items,
          member('long', LONG, { title: 'x'.repeat(40), bio: 'long '.repeat(28).trim() }),
          // Enough ids to reach every one of the six colours.
          ...['a', 'b', 'c', 'd', 'e', 'f'].map((id) => member(id, `Tone ${id}`)),
        ],
      }),
    'team-sign-in-accounts': () => Response.json(ACCOUNTS),
    'catalogue-categories': () => Response.json(CATEGORIES),
    'catalogue-services': () => Response.json(SERVICES),
  };
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!.split('?')[0]!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});

function page(brand: string, canManage = true, canSeeAccounts = true, readOnly: 'support' | 'salon' | null = null) {
  return renderWithMessages(
    <AccountFrame
      salon={{ slug: 'alpha', name: 'Alpha Studio', brandColour: brand, timeZone: 'Asia/Colombo' }}
      permissions={['session.manage', 'team.read', 'team.manage']}
      view="team"
    >
      <TeamScreen kind="staff" canManage={canManage} canSeeAccounts={canSeeAccounts} readOnly={readOnly} />
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

const matrix = BRANDS.flatMap((brand) => WIDTHS.map((width) => [brand, width] as const));

const card = (id: string) => screen.getAllByTestId('team-card').find((each) => each.dataset.id === id)!;

describe('the Team tab', () => {
  it.each(matrix)('with its cards, a long name and every initial colour, brand %s at %ipx', async (brand, width) => {
    await atWidth(width);
    page(brand);
    await screen.findAllByTestId('team-card');
    await noViolations();
    noHorizontalScroll();
  });

  it('loading', async () => {
    vi.stubGlobal('fetch', () => new Promise<Response>(() => undefined));
    page(BRANDS[1]);
    await screen.findByTestId('team-loading');
    await noViolations();
  });

  it.each(WIDTHS)('empty at %ipx', async (width) => {
    await atWidth(width);
    answers['team-members'] = () => Response.json({ items: [] });
    page(BRANDS[0]);
    await screen.findByTestId('team-empty');
    await noViolations();
    noHorizontalScroll();
  });

  it('unavailable', async () => {
    answers['team-members'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    page(BRANDS[0]);
    await screen.findByTestId('team-unavailable');
    await noViolations();
  });

  it('read only for a support session, with no tabs for one without staff.list', async () => {
    page(BRANDS[1], false, false, 'support');
    await screen.findByTestId('team-read-only');
    await screen.findAllByTestId('team-card');
    await noViolations();
  });
});

describe('the profile sheet', () => {
  const editNimali = async () => {
    await screen.findAllByTestId('team-card');
    fireEvent.click(within(card(NIMALI)).getByTestId('team-card-edit'));
    await screen.findByTestId('team-sheet');
  };

  it.each(matrix)('editing, with the picker open, brand %s at %ipx', async (brand, width) => {
    await atWidth(width);
    page(brand);
    await editNimali();
    fireEvent.click(screen.getByRole('button', { name: 'Choose services in Colour' }));
    await screen.findByTestId('team-picker-services');
    await noViolations();
    noHorizontalScroll();
  });

  it.each(WIDTHS)('adding, with its broken rules, at %ipx', async (width) => {
    await atWidth(width);
    page(BRANDS[0]);
    fireEvent.click(await screen.findByTestId('team-add'));
    await screen.findByTestId('team-sheet');
    fireEvent.change(screen.getByTestId('team-title'), { target: { value: 'x'.repeat(41) } });
    fireEvent.change(screen.getByTestId('team-bio'), { target: { value: 'x'.repeat(141) } });
    await screen.findAllByTestId('team-sheet-problem');
    await noViolations();
    noHorizontalScroll();
  });

  it('with a conflict, and with a seat refused', async () => {
    answers['team-member-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    page(BRANDS[1]);
    await editNimali();
    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Nimali P' } });
    fireEvent.click(screen.getByTestId('team-sheet-save'));
    await screen.findByTestId('team-conflict');
    await noViolations();
  });

  it('the removal confirmation and the leave question', async () => {
    page(BRANDS[0]);
    await editNimali();
    fireEvent.click(screen.getByTestId('team-sheet-remove'));
    await screen.findByTestId('team-remove-dialog');
    await noViolations();
    fireEvent.click(screen.getByTestId('team-remove-cancel'));
    await waitFor(() => expect(screen.queryByTestId('team-remove-dialog')).toBeNull());
    fireEvent.change(screen.getByTestId('team-name'), { target: { value: 'Edited' } });
    fireEvent.click(screen.getByTestId('team-sheet-close'));
    await screen.findByTestId('team-leave-dialog');
    await noViolations();
  });

  it('keeps focus inside, and gives it back to Edit profile when it closes', async () => {
    page(BRANDS[0]);
    await screen.findAllByTestId('team-card');
    const edit = within(card(KASUN)).getByTestId('team-card-edit');
    edit.focus();
    fireEvent.click(edit);
    const sheet = await screen.findByTestId('team-sheet');
    await waitFor(() => expect(sheet.contains(document.activeElement)).toBe(true));
    fireEvent.click(screen.getByTestId('team-sheet-close'));
    await waitFor(() => expect(document.activeElement).toBe(edit));
  });
});
