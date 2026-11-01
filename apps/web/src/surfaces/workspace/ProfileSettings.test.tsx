import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProfileDraft } from '@/features/branding';
import { renderWithMessages } from '@test/render';
import { ProfileSettingsScreen } from './ProfileSettingsScreen';
import { SettingsScreen } from './SettingsScreen';
import { WorkspaceShell } from './WorkspaceShell';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

const BRANCH = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const principal = { kind: 'staff', permissions: ['settings.read', 'settings.manage'] };
function draftOf(
  overrides: Partial<ProfileDraft> = {},
  branch: Partial<ProfileDraft['profile']['branches'][number]> = {},
) {
  return {
    version: 2,
    hasUnpublishedChanges: false,
    publishedAt: '2026-11-01T05:00:00Z',
    publishedBy: '11111111-0000-4000-8000-000000000001',
    profile: {
      description: 'A friendly neighbourhood salon.',
      links: { instagram: 'https://instagram.com/yoursalon', facebook: null, website: null },
      branches: [
        {
          id: BRANCH,
          isDefault: true,
          address: 'No. 24, Flower Road, Colombo 07',
          location: { latitude: 6.9061, longitude: 79.8611 },
          phone: '+94112504417',
          whatsapp: '+94774123300',
          ...branch,
        },
      ],
    },
    ...overrides,
  } satisfies ProfileDraft;
}

type Call = { url: string; body: Record<string, unknown> | null };
let calls: Call[];
let answers: Record<string, () => Response>;
beforeEach(() => {
  calls = [];
  push.mockReset();
  answers = {
    principal: () => Response.json(principal),
    'profile-draft': () => Response.json(draftOf()),
  };
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    calls.push({ url, body: init?.body ? JSON.parse(String(init.body)) : null });
    const action = url.split('/').pop()!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const mutations = () => calls.filter((call) => call.body !== null && !call.url.endsWith('/principal'));
const render = (readOnly: 'support' | 'salon' | null = null) =>
  renderWithMessages(<ProfileSettingsScreen kind="staff" salonName="Alpha Studio" readOnly={readOnly} />);

describe('the Profile tab', () => {
  it('shows a loading skeleton, then the saved draft with phone numbers in the national format', async () => {
    render();
    expect(screen.getByTestId('profile-loading')).toHaveAttribute('aria-busy', 'true');

    expect(await screen.findByTestId('profile-phone')).toHaveValue('0112 504 417');
    expect(screen.getByTestId('profile-whatsapp')).toHaveValue('077 412 3300');
    expect(screen.getByTestId('profile-location')).toHaveValue('6.9061, 79.8611');
    expect(screen.getByTestId('profile-check-map')).toHaveAttribute(
      'href',
      'https://www.google.com/maps/search/?api=1&query=6.9061,79.8611',
    );
    expect(screen.getByTestId('profile-check-map')).toHaveAttribute('target', '_blank');
    expect(screen.getByTestId('publish-bar-status')).toHaveTextContent('Everything is published');
    expect(screen.getByTestId('profile-publish')).toBeDisabled();
    expect(screen.getByTestId('profile-save')).toBeDisabled();
  });

  it('updates What customers see as the person types, and says there are unpublished changes', async () => {
    render();
    const description = await screen.findByTestId('profile-description');

    fireEvent.change(description, { target: { value: 'Hair and bridal in Colombo.' } });
    fireEvent.change(screen.getByTestId('profile-whatsapp'), { target: { value: '' } });

    expect(screen.getByTestId('profile-preview-description')).toHaveTextContent('Hair and bridal in Colombo.');
    expect(screen.getByTestId('profile-description-count')).toHaveTextContent('27 of 500 characters');
    expect(screen.queryByTestId('profile-preview-chat')).toBeNull();
    expect(screen.getByTestId('publish-bar-status')).toHaveTextContent('Unpublished changes');
    expect(screen.getByTestId('profile-publish')).toBeEnabled();
  });

  it('saves a draft against its version and confirms it', async () => {
    answers['profile-save'] = () =>
      Response.json(draftOf({ version: 3, hasUnpublishedChanges: true }, { address: 'Kandy Road' }));
    render();
    fireEvent.change(await screen.findByTestId('profile-address'), { target: { value: 'Kandy Road' } });

    fireEvent.click(screen.getByTestId('profile-save'));

    await waitFor(() => expect(screen.getByTestId('profile-save')).toBeDisabled());
    expect(mutations()).toEqual([
      {
        url: '/identity-web/staff/profile-save',
        body: expect.objectContaining({
          version: 2,
          profile: expect.objectContaining({
            branches: [expect.objectContaining({ id: BRANCH, address: 'Kandy Road', phone: '0112 504 417' })],
          }),
        }),
      },
    ]);
    expect(screen.getByTestId('publish-bar-status')).toHaveTextContent('Unpublished changes');
  });

  it('shows each refused field’s message beside it and keeps what was typed', async () => {
    answers['profile-save'] = () =>
      Response.json(
        {
          code: 'bad_request',
          errors: [
            { field: 'profile.branches[0].whatsapp', detail: 'must be a mobile number' },
            { field: 'profile.links.website', detail: 'must be an https:// web address' },
          ],
        },
        { status: 400 },
      );
    render();
    fireEvent.change(await screen.findByTestId('profile-whatsapp'), { target: { value: '011 250 4417' } });
    fireEvent.change(screen.getByTestId('profile-website'), { target: { value: 'http://salon.example' } });

    fireEvent.click(screen.getByTestId('profile-save'));

    expect(await screen.findByTestId('profile-whatsapp-error')).toHaveTextContent('Enter a mobile number');
    expect(screen.getByTestId('profile-website-error')).toHaveTextContent('https://');
    expect(screen.getByTestId('profile-whatsapp')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('profile-whatsapp')).toHaveAccessibleDescription(/Enter a mobile number/);
    expect(screen.getByTestId('profile-whatsapp')).toHaveValue('011 250 4417');
    expect(screen.getByTestId('profile-fix')).toBeInTheDocument();
  });

  it('refuses a map position that is not two numbers without sending anything', async () => {
    render();
    fireEvent.change(await screen.findByTestId('profile-location'), { target: { value: 'near the temple' } });

    fireEvent.click(screen.getByTestId('profile-save'));

    expect(await screen.findByTestId('profile-location-error')).toHaveTextContent('latitude');
    expect(mutations()).toEqual([]);
  });

  it('says someone else changed the profile and reloads it on request', async () => {
    answers['profile-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    render();
    fireEvent.change(await screen.findByTestId('profile-address'), { target: { value: 'Mine' } });
    fireEvent.click(screen.getByTestId('profile-save'));

    const conflict = await screen.findByTestId('profile-conflict');
    expect(conflict).toHaveTextContent('Someone else changed the profile');
    answers['profile-draft'] = () => Response.json(draftOf({ version: 5 }, { address: 'Theirs' }));
    fireEvent.click(within(conflict).getByTestId('profile-conflict-reload'));

    await waitFor(() => expect(screen.getByTestId('profile-address')).toHaveValue('Theirs'));
    expect(screen.queryByTestId('profile-conflict')).toBeNull();
  });

  it('says the profile is unavailable when the API cannot be reached, keeping what was typed', async () => {
    answers['profile-save'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    render();
    fireEvent.change(await screen.findByTestId('profile-address'), { target: { value: 'Typed' } });
    fireEvent.click(screen.getByTestId('profile-save'));

    expect(await screen.findByTestId('profile-unavailable')).toHaveTextContent('Nothing was saved');
    expect(screen.getByTestId('profile-address')).toHaveValue('Typed');
  });

  it('publishes unsaved edits by saving them first, then publishing that version', async () => {
    answers['profile-save'] = () => Response.json(draftOf({ version: 3, hasUnpublishedChanges: true }));
    answers['profile-publish'] = () => Response.json(draftOf({ version: 4 }));
    render();
    fireEvent.change(await screen.findByTestId('profile-description'), { target: { value: 'New words' } });

    fireEvent.click(screen.getByTestId('profile-publish'));

    await waitFor(() => expect(screen.getByTestId('publish-bar-status')).toHaveTextContent('Everything is published'));
    expect(mutations().map((call) => [call.url, call.body?.version])).toEqual([
      ['/identity-web/staff/profile-save', 2],
      ['/identity-web/staff/profile-publish', 3],
    ]);
    expect(screen.getByTestId('profile-publish')).toBeDisabled();
  });

  it('discards changes only after the confirmation', async () => {
    answers['profile-draft'] = () => Response.json(draftOf({ hasUnpublishedChanges: true }));
    answers['profile-discard'] = () => Response.json(draftOf({ version: 3 }));
    render();
    fireEvent.click(await screen.findByTestId('profile-discard'));
    const dialog = await screen.findByTestId('profile-discard-dialog');
    expect(mutations()).toEqual([]);

    fireEvent.click(within(dialog).getByTestId('profile-discard-confirm'));

    await waitFor(() =>
      expect(mutations()).toEqual([{ url: '/identity-web/staff/profile-discard', body: { version: 2 } }]),
    );
    await waitFor(() => expect(screen.getByTestId('publish-bar-status')).toHaveTextContent('Everything is published'));
  });

  it('asks before leaving with unsaved edits, and stays or leaves as chosen', async () => {
    renderWithMessages(
      <>
        <a href="/workspace/staff" data-testid="elsewhere">
          Staff
        </a>
        <ProfileSettingsScreen kind="staff" salonName="Alpha Studio" readOnly={null} />
      </>,
    );
    fireEvent.change(await screen.findByTestId('profile-address'), { target: { value: 'Unsaved' } });

    fireEvent.click(screen.getByTestId('elsewhere'));
    const dialog = await screen.findByTestId('profile-leave-dialog');
    fireEvent.click(within(dialog).getByTestId('profile-leave-stay'));
    await waitFor(() => expect(screen.queryByTestId('profile-leave-dialog')).toBeNull());
    expect(push).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('elsewhere'));
    fireEvent.click(within(await screen.findByTestId('profile-leave-dialog')).getByTestId('profile-leave-confirm'));
    expect(push).toHaveBeenCalledWith('/workspace/staff');

    const unload = new Event('beforeunload', { cancelable: true });
    act(() => {
      window.dispatchEvent(unload);
    });
    expect(unload.defaultPrevented).toBe(true);
  });

  it('lets a support view read the draft and change nothing', async () => {
    render('support');

    expect(await screen.findByTestId('profile-read-only')).toHaveTextContent('support session');
    expect(screen.getByTestId('profile-address')).toBeDisabled();
    expect(screen.getByTestId('profile-save')).toBeDisabled();
    expect(screen.getByTestId('profile-publish')).toBeDisabled();
    expect(screen.getByTestId('profile-discard')).toBeDisabled();
  });

  it('is built of the shadcn/ui components the map names for its jobs', async () => {
    const { container } = render();
    await screen.findByTestId('profile-tab');
    for (const slot of ['card', 'field', 'field-label', 'input', 'textarea', 'button', 'badge'])
      expect(container.querySelector(`[data-slot="${slot}"]`), slot).not.toBeNull();
  });
});

describe('the Settings page', () => {
  it('shows only the delivered tabs, in the prototype order, with Profile selected', () => {
    renderWithMessages(
      <SettingsScreen tab="profile">
        <p>content</p>
      </SettingsScreen>,
    );
    const tabs = within(screen.getByTestId('settings-tabs')).getAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['Profile', 'Branding']);
    expect(screen.getByTestId('settings-tab-profile')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveTextContent('content');
  });

  it('is in the workspace navigation for settings staff only, last', () => {
    const salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };
    const { unmount } = renderWithMessages(
      <WorkspaceShell salon={salon} permissions={['session.manage', 'staff.list', 'settings.read']} view="settings">
        <p>x</p>
      </WorkspaceShell>,
    );
    const items = within(screen.getByTestId('workspace-navigation')).getAllByRole('link');
    expect(items.at(-1)).toHaveAttribute('data-testid', 'workspace-settings');
    expect(screen.getByTestId('workspace-settings')).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId('workspace-settings')).toHaveAttribute('href', '/workspace/settings');
    unmount();

    renderWithMessages(
      <WorkspaceShell salon={salon} permissions={['session.manage', 'calendar.read']}>
        <p>x</p>
      </WorkspaceShell>,
    );
    expect(screen.queryByTestId('workspace-settings')).toBeNull();
  });
});
