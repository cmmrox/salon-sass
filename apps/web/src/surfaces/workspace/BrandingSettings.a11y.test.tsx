import { fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BrandDraft } from '@/features/branding';
import { atWidth, axe, BRANDS, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { AccountFrame } from './AccountFrame';
import { BrandingSettingsScreen } from './BrandingSettingsScreen';
import { SettingsScreen } from './SettingsScreen';
import { StaffSignInFrame } from './StaffSignInFrame';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F04-S02 AC-12: the Branding tab, its upload states and its publish bar have no WCAG 2.2 AA violation,
 * colour contrast included, at every width and with a dark and a light draft colour, rendered in real
 * Chromium inside the workspace frame as the page ships. The preview's button is held to 4.5:1 for each
 * draft colour, a mid-tone included.
 */
function draftOf(colour: string, overrides: Partial<BrandDraft> = {}): BrandDraft {
  return {
    version: 2,
    hasUnpublishedChanges: false,
    publishedAt: null,
    publishedBy: null,
    brand: { name: 'Alpha Studio', tagline: 'Simple booking. Better salon days.', colour, logo: null, cover: null },
    ...overrides,
  };
}
let answers: Record<string, () => Response | Promise<Response>>;
beforeEach(() => {
  answers = {
    principal: () => Response.json({ kind: 'staff', permissions: ['settings.read', 'settings.manage'] }),
    'brand-draft': () => Response.json(draftOf('#1F2937')),
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
      permissions={['session.manage', 'settings.read', 'settings.manage']}
      view="settings"
    >
      <SettingsScreen tab="branding">
        <BrandingSettingsScreen kind="staff" readOnly={readOnly} />
      </SettingsScreen>
    </AccountFrame>,
  );
}

async function noViolations() {
  expect(await axe(document.body)).toHaveNoViolations();
}

describe('the Branding tab', () => {
  it.each(BRANDS.flatMap((brand) => WIDTHS.map((width) => [brand, width] as const)))(
    'loaded with draft colour %s at %ipx',
    async (brand, width) => {
      answers['brand-draft'] = () => Response.json(draftOf(brand));
      await atWidth(width);
      page(brand);
      await screen.findByTestId('brand-tab');
      await noViolations();
    },
  );

  it.each(['#808080', '#FFFF00', '#000080', '#D6086A'])('keeps the preview button readable on %s', async (colour) => {
    page('#1F2937');
    fireEvent.change(await screen.findByTestId('brand-colour'), { target: { value: colour } });
    await noViolations();
  });

  it('loading', async () => {
    vi.stubGlobal('fetch', (url: string) =>
      url.endsWith('/principal')
        ? Promise.resolve(Response.json({ kind: 'staff', permissions: [] }))
        : new Promise<Response>(() => undefined),
    );
    page(BRANDS[1]);
    await screen.findByTestId('brand-loading');
    await noViolations();
  });

  it.each(BRANDS)('uploading, then refused, with brand %s', async (brand) => {
    let release: () => void = () => undefined;
    answers['brand-image'] = () =>
      new Promise<Response>((resolve) => {
        release = () =>
          resolve(Response.json({ code: 'bad_request', errors: [{ field: 'file', detail: 'x' }] }, { status: 400 }));
      });
    await atWidth(390);
    page(brand);
    fireEvent.change(await screen.findByTestId('brand-logo-file'), {
      target: { files: [new File(['x'], 'logo.png', { type: 'image/png' })] },
    });
    await screen.findByText('Uploading…', { selector: 'button' });
    await noViolations();
    release();
    await screen.findByTestId('brand-logo-error');
    await noViolations();
  });

  it.each(BRANDS)('with field errors and unsaved edits, brand %s', async (brand) => {
    answers['brand-save'] = () =>
      Response.json(
        {
          code: 'bad_request',
          errors: [
            { field: 'brand.name', detail: 'x' },
            { field: 'brand.colour', detail: 'x' },
          ],
        },
        { status: 400 },
      );
    page(brand);
    fireEvent.change(await screen.findByTestId('brand-name'), { target: { value: ' ' } });
    fireEvent.click(screen.getByTestId('brand-save'));
    await screen.findByTestId('brand-name-error');
    await noViolations();
  });

  it.each(BRANDS)('after a conflict, brand %s', async (brand) => {
    answers['brand-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    page(brand);
    fireEvent.change(await screen.findByTestId('brand-name'), { target: { value: 'Mine' } });
    fireEvent.click(screen.getByTestId('brand-save'));
    await screen.findByTestId('brand-conflict');
    await noViolations();
  });

  it('unavailable', async () => {
    answers['brand-draft'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    page(BRANDS[0]);
    await screen.findByTestId('brand-unavailable');
    await noViolations();
  });

  it.each(BRANDS)('saved with unpublished changes, brand %s', async (brand) => {
    answers['brand-save'] = () => Response.json(draftOf('#2F6B5E', { version: 3, hasUnpublishedChanges: true }));
    page(brand);
    fireEvent.click((await screen.findAllByTestId('brand-swatch'))[4]!);
    fireEvent.click(screen.getByTestId('brand-save'));
    await screen.findByText('Draft saved');
    await screen.findByText('Unpublished changes');
    await noViolations();
  });

  it.each(['support', 'salon'] as const)('read-only (%s)', async (readOnly) => {
    page(BRANDS[0], readOnly);
    await screen.findByTestId('brand-read-only');
    await noViolations();
  });

  it.each(BRANDS)('with the reset confirmation open, brand %s', async (brand) => {
    await atWidth(360);
    page(brand);
    fireEvent.click(await screen.findByTestId('brand-reset'));
    await screen.findByTestId('brand-reset-dialog');
    await noViolations();
  });
});

describe('the sign-in page with a published logo', () => {
  it.each(BRANDS)('brand %s', async (brand) => {
    renderWithMessages(
      <StaffSignInFrame
        salon={{
          slug: 'alpha',
          name: 'Alpha Studio',
          brandColour: brand,
          timeZone: 'Asia/Colombo',
          logo: '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10',
        }}
      >
        <p>{'Sign in'}</p>
      </StaffSignInFrame>,
    );
    await screen.findByTestId('staff-sign-in-initial');
    await noViolations();
  });
});
