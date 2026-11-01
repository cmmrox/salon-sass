import { fireEvent, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProfileDraft } from '@/features/branding';
import { atWidth, axe, BRANDS, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { AccountFrame } from './AccountFrame';
import { ProfileSettingsScreen } from './ProfileSettingsScreen';
import { SettingsScreen } from './SettingsScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F04-S01 AC-11: the Settings page and its Profile tab, in every state, have no WCAG 2.2 AA violation,
 * colour contrast included, at every width of the matrix and with a dark and a light salon brand. Rendered
 * in real Chromium inside the workspace frame, as the page ships.
 */
const BRANCH = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const draft: ProfileDraft = {
  version: 2,
  hasUnpublishedChanges: false,
  publishedAt: '2026-11-01T05:00:00Z',
  publishedBy: '11111111-0000-4000-8000-000000000001',
  profile: {
    description: 'A friendly neighbourhood salon for hair, beauty and bridal.',
    links: { instagram: 'https://instagram.com/yoursalon', facebook: null, website: null },
    branches: [
      {
        id: BRANCH,
        isDefault: true,
        address: 'No. 24, Flower Road, Colombo 07',
        location: { latitude: 6.9061, longitude: 79.8611 },
        phone: '+94112504417',
        whatsapp: '+94774123300',
      },
    ],
  },
};
let answers: Record<string, () => Response>;
beforeEach(() => {
  answers = {
    principal: () => Response.json({ kind: 'staff', permissions: ['settings.read', 'settings.manage'] }),
    'profile-draft': () => Response.json(draft),
  };
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});

function page(brand: string, readOnly: 'support' | 'salon' | null = null) {
  return renderWithMessages(
    <AccountFrame
      salon={{ slug: 'alpha', name: 'Alpha Studio', brandColour: brand, timeZone: 'Asia/Colombo' }}
      permissions={['session.manage', 'staff.list', 'audit.list', 'settings.read', 'settings.manage']}
      view="settings"
    >
      <SettingsScreen tab="profile">
        <ProfileSettingsScreen kind="staff" salonName="Alpha Studio" readOnly={readOnly} />
      </SettingsScreen>
    </AccountFrame>,
  );
}

async function noViolations() {
  expect(await axe(document.body)).toHaveNoViolations();
}

describe('the Settings page and its Profile tab', () => {
  it.each(BRANDS.flatMap((brand) => WIDTHS.map((width) => [brand, width] as const)))(
    'loaded and published: brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      page(brand);
      await screen.findByTestId('profile-tab');
      await noViolations();
    },
  );

  it.each(BRANDS)('loading, with brand %s', async (brand) => {
    answers['profile-draft'] = () => new Promise<Response>(() => undefined) as unknown as Response;
    vi.stubGlobal('fetch', (url: string) =>
      url.endsWith('/principal') ? Promise.resolve(answers.principal!()) : new Promise<Response>(() => undefined),
    );
    page(brand);
    await screen.findByTestId('profile-loading');
    await noViolations();
  });

  it.each(BRANDS)('with unsaved edits and field errors, brand %s', async (brand) => {
    answers['profile-save'] = () =>
      Response.json(
        {
          code: 'bad_request',
          errors: [
            { field: 'profile.branches[0].whatsapp', detail: 'x' },
            { field: 'profile.branches[0].phone', detail: 'x' },
            { field: 'profile.links.website', detail: 'x' },
          ],
        },
        { status: 400 },
      );
    await atWidth(390);
    page(brand);
    fireEvent.change(await screen.findByTestId('profile-whatsapp'), { target: { value: '011 250 4417' } });
    fireEvent.click(screen.getByTestId('profile-save'));
    await screen.findByTestId('profile-whatsapp-error');
    fireEvent.change(screen.getByTestId('profile-location'), { target: { value: 'near the temple' } });
    await noViolations();
  });

  it.each(BRANDS)('after a conflict, brand %s', async (brand) => {
    answers['profile-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    page(brand);
    fireEvent.change(await screen.findByTestId('profile-address'), { target: { value: 'Mine' } });
    fireEvent.click(screen.getByTestId('profile-save'));
    await screen.findByTestId('profile-conflict');
    await noViolations();
  });

  it.each(BRANDS)('unavailable, brand %s', async (brand) => {
    answers['profile-draft'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    page(brand);
    await screen.findByTestId('profile-unavailable');
    await noViolations();
  });

  it.each(BRANDS)('saved, with unpublished changes and the toast, brand %s', async (brand) => {
    answers['profile-save'] = () => Response.json({ ...draft, version: 3, hasUnpublishedChanges: true });
    page(brand);
    fireEvent.change(await screen.findByTestId('profile-description'), { target: { value: 'New words' } });
    fireEvent.click(screen.getByTestId('profile-save'));
    await screen.findByText('Draft saved');
    await screen.findByText('Unpublished changes');
    await noViolations();
  });

  it.each(['support', 'salon'] as const)('read-only (%s)', async (readOnly) => {
    page(BRANDS[0], readOnly);
    await screen.findByTestId('profile-read-only');
    await noViolations();
  });

  it.each(BRANDS)('with the discard confirmation open, brand %s', async (brand) => {
    answers['profile-draft'] = () => Response.json({ ...draft, hasUnpublishedChanges: true });
    await atWidth(360);
    page(brand);
    fireEvent.click(await screen.findByTestId('profile-discard'));
    await screen.findByTestId('profile-discard-dialog');
    await noViolations();
  });

  it.each(BRANDS)('with the leave confirmation open, brand %s', async (brand) => {
    await atWidth(1280);
    page(brand);
    fireEvent.change(await screen.findByTestId('profile-address'), { target: { value: 'Unsaved' } });
    fireEvent.click(screen.getByTestId('workspace-account'));
    const dialog = await screen.findByTestId('profile-leave-dialog');
    expect(within(dialog).getByTestId('profile-leave-stay')).toBeVisible();
    await noViolations();
  });
});
