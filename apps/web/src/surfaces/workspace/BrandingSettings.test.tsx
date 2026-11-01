import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BrandDraft } from '@/features/branding';
import { contrastColour } from '@/styles/brand';
import { renderWithMessages } from '@test/render';
import { BrandingSettingsScreen } from './BrandingSettingsScreen';
import { SettingsScreen } from './SettingsScreen';
import { StaffSignInFrame } from './StaffSignInFrame';
import { WorkspaceShell } from './WorkspaceShell';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

const LOGO = { id: '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10', width: 512, height: 512 };
const NEW_COVER = { id: '7c1e8a52-1f3e-4a0e-9a57-0f0c6f4b1f22', kind: 'cover', width: 1600, height: 600 };
function draftOf(overrides: Partial<BrandDraft> = {}, brand: Partial<BrandDraft['brand']> = {}): BrandDraft {
  return {
    version: 2,
    hasUnpublishedChanges: false,
    publishedAt: null,
    publishedBy: null,
    brand: { name: 'Alpha Studio', tagline: 'Simple booking.', colour: '#D6086A', logo: LOGO, cover: null, ...brand },
    ...overrides,
  };
}

type Call = { url: string; body: unknown };
let calls: Call[];
let answers: Record<string, () => Response>;
beforeEach(() => {
  calls = [];
  push.mockReset();
  answers = {
    principal: () => Response.json({ kind: 'staff', permissions: ['settings.read', 'settings.manage'] }),
    'brand-draft': () => Response.json(draftOf()),
  };
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const body = init?.body instanceof FormData ? init.body : init?.body ? JSON.parse(String(init.body)) : null;
    calls.push({ url, body });
    const action = url.split('/').pop()!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const changes = () => calls.filter((call) => call.body !== null && !call.url.endsWith('/principal'));
const render = (readOnly: 'support' | 'salon' | null = null) =>
  renderWithMessages(<BrandingSettingsScreen kind="staff" readOnly={readOnly} />);
const preview = () => screen.getByTestId('brand-preview');

describe('the Branding tab', () => {
  it('shows a loading skeleton, then the draft and its preview', async () => {
    render();
    expect(screen.getByTestId('brand-loading')).toHaveAttribute('aria-busy', 'true');

    expect(await screen.findByTestId('brand-name')).toHaveValue('Alpha Studio');
    expect(screen.getByTestId('brand-preview-name')).toHaveTextContent('Alpha Studio');
    expect(screen.getByTestId('brand-preview-tagline')).toHaveTextContent('Simple booking.');
    expect(screen.getByTestId('brand-preview-book')).toHaveTextContent('Book an appointment');
    expect(screen.getByTestId('brand-preview-cover')).toHaveAttribute('data-image', 'neutral');
    expect(screen.getByTestId('brand-logo-default')).toBeInTheDocument();
    expect(screen.getByTestId('publish-bar-status')).toHaveTextContent('Everything is published');
  });

  it('updates the preview as the person types and chooses, before anything is saved', async () => {
    render();
    fireEvent.change(await screen.findByTestId('brand-name'), { target: { value: 'Beta Lounge' } });
    fireEvent.change(screen.getByTestId('brand-tagline'), { target: { value: 'Hair and bridal' } });
    fireEvent.click(screen.getAllByTestId('brand-swatch').find((s) => s.dataset.colour === '#2F6B5E')!);

    expect(screen.getByTestId('brand-preview-name')).toHaveTextContent('Beta Lounge');
    expect(screen.getByTestId('brand-preview-tagline')).toHaveTextContent('Hair and bridal');
    expect(screen.getByTestId('brand-colour')).toHaveValue('#2F6B5E');
    expect(preview().style.getPropertyValue('--primary')).toBe('#2F6B5E');
    expect(changes()).toEqual([]);
    expect(screen.getByTestId('publish-bar-status')).toHaveTextContent('Unpublished changes');
  });

  it('draws the preview button text by the contrast rule, black on a mid-tone such as #808080', async () => {
    render();
    fireEvent.change(await screen.findByTestId('brand-colour'), { target: { value: '#808080' } });

    expect(preview().style.getPropertyValue('--primary')).toBe('#808080');
    expect(preview().style.getPropertyValue('--primary-foreground')).toBe(contrastColour('#808080'));
    expect(contrastColour('#808080')).toBe('#000000');
  });

  it('keeps the last valid colour on the preview while what is typed is not yet #RRGGBB', async () => {
    render();
    fireEvent.change(await screen.findByTestId('brand-colour'), { target: { value: '#12' } });

    expect(preview().style.getPropertyValue('--primary')).toBe('#D6086A');
  });

  it('gives each swatch an accessible name', async () => {
    render();
    await screen.findByTestId('brand-tab');
    for (const colour of ['#D6086A', '#141413', '#CC5500', '#8A4B6B', '#2F6B5E', '#2F4B8A'])
      expect(screen.getByRole('radio', { name: `Use colour ${colour}` })).toBeInTheDocument();
  });

  it('uploads a chosen cover, shows it, and saves it into the draft only with Save draft', async () => {
    answers['brand-image'] = () => Response.json(NEW_COVER, { status: 201 });
    answers['brand-save'] = () =>
      Response.json(draftOf({ version: 3, hasUnpublishedChanges: true }, { cover: NEW_COVER }));
    render();
    const file = new File([new Uint8Array([1, 2, 3])], 'my-cover.webp', { type: 'image/webp' });

    fireEvent.change(await screen.findByTestId('brand-cover-file'), { target: { files: [file] } });

    await waitFor(() => expect(screen.getByTestId('brand-cover-chosen')).toHaveAttribute('data-image', NEW_COVER.id));
    expect(screen.getByTestId('brand-preview-cover')).toHaveAttribute('data-image', NEW_COVER.id);
    const upload = changes()[0]!;
    expect(upload.url).toBe('/identity-web/staff/brand-image');
    expect((upload.body as FormData).get('kind')).toBe('cover');
    expect(changes().map((call) => call.url)).toEqual(['/identity-web/staff/brand-image']);

    fireEvent.click(screen.getByTestId('brand-save'));

    await waitFor(() => expect(changes()).toHaveLength(2));
    expect(changes()[1]).toEqual({
      url: '/identity-web/staff/brand-save',
      body: { version: 2, brand: expect.objectContaining({ logo: LOGO.id, cover: NEW_COVER.id }) },
    });
  });

  it('shows a refused upload beside its field and keeps the image it had', async () => {
    answers['brand-image'] = () =>
      Response.json({ code: 'bad_request', errors: [{ field: 'file', detail: 'x' }] }, { status: 400 });
    render();
    const file = new File(['not an image'], 'logo.png', { type: 'image/png' });

    fireEvent.change(await screen.findByTestId('brand-logo-file'), { target: { files: [file] } });

    expect(await screen.findByTestId('brand-logo-error')).toHaveTextContent('That file can’t be used');
    expect(screen.getByTestId('brand-logo-chosen')).toHaveAttribute('data-image', LOGO.id);
    expect(screen.getByTestId('brand-logo-choose')).toHaveAccessibleDescription(/can’t be used/);
  });

  it('opens the file choice from the Button, which is the field’s one focusable control', async () => {
    render();
    const input = (await screen.findByTestId('brand-logo-file')) as HTMLInputElement;
    const click = vi.spyOn(input, 'click');

    fireEvent.click(screen.getByTestId('brand-logo-choose'));

    expect(click).toHaveBeenCalled();
    expect(input).toHaveAttribute('tabindex', '-1');
  });

  it('removes the logo from the form with Use default logo, the preview falling back to the initial', async () => {
    render();
    fireEvent.click(await screen.findByTestId('brand-logo-default'));

    expect(screen.getByTestId('brand-logo-chosen')).toHaveAttribute('data-image', 'none');
    expect(screen.queryByTestId('brand-logo-default')).toBeNull();
    expect(within(screen.getByTestId('brand-preview-logo')).getByText('A')).toBeInTheDocument();
  });

  it('resets to the default branding only after the confirmation', async () => {
    answers['brand-reset'] = () =>
      Response.json(
        draftOf({ version: 3, hasUnpublishedChanges: true }, { colour: '#D6086A', tagline: null, logo: null }),
      );
    render();
    fireEvent.click(await screen.findByTestId('brand-reset'));
    const dialog = await screen.findByTestId('brand-reset-dialog');
    expect(changes()).toEqual([]);

    fireEvent.click(within(dialog).getByTestId('brand-reset-confirm'));

    await waitFor(() => expect(changes()).toEqual([{ url: '/identity-web/staff/brand-reset', body: { version: 2 } }]));
    await waitFor(() => expect(screen.getByTestId('brand-tagline')).toHaveValue(''));
  });

  it('shows a field error beside the field the API refused', async () => {
    answers['brand-save'] = () =>
      Response.json({ code: 'bad_request', errors: [{ field: 'brand.name', detail: 'x' }] }, { status: 400 });
    render();
    fireEvent.change(await screen.findByTestId('brand-name'), { target: { value: '   ' } });
    fireEvent.click(screen.getByTestId('brand-save'));

    expect(await screen.findByTestId('brand-name-error')).toHaveTextContent('up to 60 characters');
    expect(screen.getByTestId('brand-fix')).toBeInTheDocument();
  });

  it('says someone else changed the branding and reloads it on request', async () => {
    answers['brand-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    render();
    fireEvent.change(await screen.findByTestId('brand-name'), { target: { value: 'Mine' } });
    fireEvent.click(screen.getByTestId('brand-save'));

    expect(await screen.findByTestId('brand-conflict')).toHaveTextContent('Someone else changed the branding');
  });

  it('publishes unsaved edits by saving them first', async () => {
    answers['brand-save'] = () => Response.json(draftOf({ version: 3, hasUnpublishedChanges: true }, { name: 'New' }));
    answers['brand-publish'] = () => Response.json(draftOf({ version: 4 }, { name: 'New' }));
    render();
    fireEvent.change(await screen.findByTestId('brand-name'), { target: { value: 'New' } });

    fireEvent.click(screen.getByTestId('profile-publish'));

    await waitFor(() => expect(screen.getByTestId('publish-bar-status')).toHaveTextContent('Everything is published'));
    expect(changes().map((call) => [call.url, (call.body as { version: number }).version])).toEqual([
      ['/identity-web/staff/brand-save', 2],
      ['/identity-web/staff/brand-publish', 3],
    ]);
  });

  // Found while building F04-S04: a Settings tab is a button, not a link, so changing tab skipped the guard and
  // lost the edits. A dirty brand draft now asks first; Stay keeps the edits, Leave goes to the tab.
  it('asks before changing Settings tab with unsaved edits, and stays or leaves as chosen', async () => {
    renderWithMessages(
      <SettingsScreen tab="branding">
        <BrandingSettingsScreen kind="staff" readOnly={null} />
      </SettingsScreen>,
    );
    fireEvent.change(await screen.findByTestId('brand-name'), { target: { value: 'Beta Lounge' } });

    fireEvent.mouseDown(screen.getByTestId('settings-tab-profile'));
    const dialog = await screen.findByTestId('profile-leave-dialog');
    expect(push).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByTestId('profile-leave-stay'));
    await waitFor(() => expect(screen.queryByTestId('profile-leave-dialog')).toBeNull());
    expect(screen.getByTestId('brand-name')).toHaveValue('Beta Lounge');
    expect(screen.getByTestId('settings-tab-branding')).toHaveAttribute('aria-selected', 'true');
    expect(push).not.toHaveBeenCalled();

    fireEvent.mouseDown(screen.getByTestId('settings-tab-rules'));
    fireEvent.click(within(await screen.findByTestId('profile-leave-dialog')).getByTestId('profile-leave-confirm'));
    expect(push).toHaveBeenCalledWith('/workspace/settings/rules');
  });

  it('asks before leaving with an uploaded image not yet saved', async () => {
    answers['brand-image'] = () => Response.json(NEW_COVER, { status: 201 });
    renderWithMessages(
      <>
        <a href="/workspace/staff" data-testid="elsewhere">
          {'Staff'}
        </a>
        <BrandingSettingsScreen kind="staff" readOnly={null} />
      </>,
    );
    fireEvent.change(await screen.findByTestId('brand-cover-file'), {
      target: { files: [new File([new Uint8Array([1])], 'c.jpg', { type: 'image/jpeg' })] },
    });
    await waitFor(() => expect(screen.getByTestId('brand-cover-chosen')).toHaveAttribute('data-image', NEW_COVER.id));

    fireEvent.click(screen.getByTestId('elsewhere'));

    expect(await screen.findByTestId('profile-leave-dialog')).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it('lets a support view read the branding and change nothing', async () => {
    render('support');

    expect(await screen.findByTestId('brand-read-only')).toHaveTextContent('support session');
    expect(screen.getByTestId('brand-name')).toBeDisabled();
    expect(screen.getByTestId('brand-logo-choose')).toBeDisabled();
    expect(screen.getByTestId('brand-reset')).toBeDisabled();
    expect(screen.getByTestId('brand-save')).toBeDisabled();
    expect(screen.getByTestId('profile-publish')).toBeDisabled();
  });

  it('is built of the shadcn/ui components the map names for its jobs', async () => {
    const { container } = render();
    await screen.findByTestId('brand-tab');
    for (const slot of ['card', 'field', 'input', 'toggle-group', 'toggle-group-item', 'avatar', 'button'])
      expect(container.querySelector(`[data-slot="${slot}"]`), slot).not.toBeNull();
  });
});

describe('the published logo elsewhere', () => {
  const salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };

  it('stands before the name in the workspace header, and nothing does when there is none', () => {
    const { unmount } = renderWithMessages(
      <WorkspaceShell salon={{ ...salon, logo: LOGO.id }}>
        <p>{'x'}</p>
      </WorkspaceShell>,
    );
    expect(screen.getByTestId('workspace-logo')).toBeInTheDocument();
    unmount();
    renderWithMessages(
      <WorkspaceShell salon={salon}>
        <p>{'x'}</p>
      </WorkspaceShell>,
    );
    expect(screen.queryByTestId('workspace-logo')).toBeNull();
  });

  it('keeps the sign-in initial when there is no logo', () => {
    renderWithMessages(
      <StaffSignInFrame salon={salon}>
        <p>{'x'}</p>
      </StaffSignInFrame>,
    );
    expect(screen.getByTestId('staff-sign-in-initial')).toHaveTextContent('A');
  });
});
