import { fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { atWidth, axe, BRANDS, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { AccountFrame } from './AccountFrame';
import { CATEGORIES, PRODUCTS, SERVICES, TAX_PUBLIC, TAX_SAVED, TAX_UNSAVED } from './catalogue-fixtures';
import { SettingsScreen } from './SettingsScreen';
import { TaxSettingsScreen } from './TaxSettingsScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F05-S03 AC-13: the Tax & charges tab, its preview, its history sheet and its states have no WCAG 2.2 AA violation,
 * colour contrast included, at every width and with a dark and a light brand, rendered in real Chromium in the
 * workspace frame, with no horizontal scrolling.
 */
const ME = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const HISTORY = {
  items: [3, 2, 1].map((version) => ({
    versionId: `00000000-0000-4000-8000-00000000000${version}`,
    version,
    createdAt: '2026-11-01T04:30:00Z',
    createdBy: version === 3 ? ME : 'someone-else',
    createdByLabel: version === 1 ? null : 'a-very-long-manager-address@example-salon-domain.test',
    changedFields:
      version === 1 ? ['vat.registered', 'vat.registrationNumber', 'serviceCharge.on'] : ['vat.ratePercent'],
  })),
  page: 0,
  size: 3,
  total: 7,
};

let answers: Record<string, () => Response | Promise<Response>>;
beforeEach(() => {
  answers = {
    principal: () => Response.json({ kind: 'staff', staffId: ME, permissions: ['settings.read', 'settings.manage'] }),
    'catalogue-tax-settings': () => Response.json(TAX_SAVED),
    'catalogue-tax-public': () => Response.json({ current: TAX_PUBLIC }),
    'catalogue-tax-history': () => Response.json(HISTORY),
    'catalogue-categories': () => Response.json(CATEGORIES),
    'catalogue-services': () => Response.json(SERVICES),
    'catalogue-products': () => Response.json(PRODUCTS),
  };
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!.split('?')[0]!;
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
      <SettingsScreen tab="tax">
        <TaxSettingsScreen kind="staff" timeZone="Asia/Colombo" readOnly={readOnly} />
      </SettingsScreen>
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

describe('the Tax & charges tab', () => {
  it.each(BRANDS.flatMap((brand) => WIDTHS.map((width) => [brand, width] as const)))(
    'saved with both parts on and the preview, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      page(brand);
      await screen.findByTestId('tax-tab');
      await screen.findByTestId('tax-charge-products');
      await noViolations();
      noHorizontalScroll();
    },
  );

  it('loading', async () => {
    vi.stubGlobal('fetch', (url: string) =>
      url.endsWith('/principal')
        ? Promise.resolve(Response.json({ kind: 'staff', permissions: [] }))
        : new Promise<Response>(() => undefined),
    );
    page(BRANDS[1]);
    await screen.findByTestId('tax-loading');
    await noViolations();
  });

  it.each(BRANDS.flatMap((brand) => [360, 1280].map((width) => [brand, width] as const)))(
    'before the first save, with field errors and the new VAT rate said, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      answers['catalogue-tax-settings'] = () => Response.json(TAX_UNSAVED);
      page(brand);
      await screen.findByTestId('tax-tab');
      fireEvent.click(screen.getByTestId('tax-vat-registered'));
      fireEvent.click(screen.getByTestId('tax-charge-on'));
      fireEvent.change(screen.getByTestId('tax-charge-percent'), { target: { value: '21' } });
      await screen.findByTestId('tax-charge-percent-error');
      await screen.findByTestId('tax-vat-number-error');
      await noViolations();
      noHorizontalScroll();
    },
  );

  it.each(BRANDS.map((brand) => [brand] as const))(
    'the new VAT rate, a conflict and the history, brand %s',
    async (brand) => {
      await atWidth(390);
      answers['catalogue-tax-settings'] = () =>
        Response.json({ ...TAX_SAVED, configured: { ...TAX_SAVED.configured, vatRatePercent: 15 } });
      answers['catalogue-tax-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
      page(brand);
      await screen.findByTestId('tax-new-rate');
      fireEvent.click(screen.getByTestId('tax-save'));
      await screen.findByTestId('tax-conflict');
      await noViolations();
      fireEvent.click(screen.getByTestId('tax-history'));
      await screen.findAllByTestId('tax-history-row');
      await noViolations();
    },
  );

  it.each(BRANDS.map((brand) => [brand] as const))(
    'read-only for a support view, and unavailable, brand %s',
    async (brand) => {
      await atWidth(768);
      const view = page(brand, 'support');
      await screen.findByTestId('tax-read-only');
      await noViolations();
      view.unmount();
      answers['catalogue-tax-settings'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
      page(brand);
      await screen.findByTestId('tax-unavailable');
      await noViolations();
    },
  );
});
