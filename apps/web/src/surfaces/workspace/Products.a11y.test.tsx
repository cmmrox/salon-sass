import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { atWidth, axe, BRANDS, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { AccountFrame } from './AccountFrame';
import { PRODUCTS, product, TAX_PUBLIC } from './catalogue-fixtures';
import { ServicesScreen } from './ServicesScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F05-S03 AC-13: the Products tab (its table from 900px and cards below, the empty state, the banners), the product
 * sheet with its problems and the delete confirmation have no WCAG 2.2 AA violation, colour contrast included, at
 * every width and with a dark and a light brand, rendered in real Chromium in the workspace frame, with no horizontal
 * scrolling. A product name of 40 characters wraps.
 */
const LONG = 'Professional keratin repair mask 1000 ml';
let answers: Record<string, () => Response | Promise<Response>>;
beforeEach(() => {
  answers = {
    principal: () =>
      Response.json({ kind: 'staff', staffId: 'me', permissions: ['catalogue.read', 'catalogue.price.change'] }),
    'catalogue-products': () =>
      Response.json({
        items: [...PRODUCTS.items, product('44444444-0000-4000-8000-000000000009', LONG, { priceMinor: 50_000_000 })],
      }),
    'catalogue-tax-public': () => Response.json({ current: TAX_PUBLIC }),
  };
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!.split('?')[0]!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});

function page(brand: string, canChange = true, readOnly: 'support' | 'salon' | null = null) {
  return renderWithMessages(
    <AccountFrame
      salon={{ slug: 'alpha', name: 'Alpha Studio', brandColour: brand, timeZone: 'Asia/Colombo' }}
      permissions={['session.manage', 'catalogue.read', 'catalogue.price.change']}
      view="services"
    >
      <ServicesScreen kind="staff" tab="products" canChange={canChange} readOnly={readOnly} />
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

describe('the Products tab', () => {
  it.each(BRANDS.flatMap((brand) => WIDTHS.map((width) => [brand, width] as const)))(
    'listed, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      page(brand);
      await screen.findAllByTestId('product-row');
      expect(screen.queryByTestId(width >= 900 ? 'product-table' : 'product-cards')).not.toBeNull();
      await noViolations();
      noHorizontalScroll();
    },
  );

  it.each(BRANDS.flatMap((brand) => [360, 1280].map((width) => [brand, width] as const)))(
    'empty, with VAT off, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      answers['catalogue-products'] = () => Response.json({ items: [] });
      answers['catalogue-tax-public'] = () => Response.json({ current: null });
      page(brand);
      await screen.findByTestId('products-empty');
      await noViolations();
      noHorizontalScroll();
    },
  );

  it.each(BRANDS)('loading, read in a support view, and unavailable, brand %s', async (brand) => {
    await atWidth(390);
    answers['catalogue-products'] = () => new Promise<Response>(() => undefined);
    const loading = page(brand);
    await screen.findByTestId('products-loading');
    await noViolations();
    loading.unmount();
    answers['catalogue-products'] = () => Response.json(PRODUCTS);
    const support = page(brand, false, 'support');
    await screen.findByTestId('products-read-only');
    await screen.findAllByTestId('product-row');
    await noViolations();
    support.unmount();
    answers['catalogue-products'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    page(brand);
    await screen.findByTestId('products-unavailable');
    await noViolations();
  });
});

describe('the product sheet', () => {
  it.each(BRANDS.flatMap((brand) => [360, 768, 1280].map((width) => [brand, width] as const)))(
    'new, with its problems, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      page(brand);
      fireEvent.click(await screen.findByTestId('products-new'));
      const sheet = await screen.findByTestId('product-sheet');
      fireEvent.change(within(sheet).getByTestId('product-sheet-name'), { target: { value: 'A' } });
      fireEvent.change(within(sheet).getByTestId('product-sheet-price'), { target: { value: '0' } });
      await screen.findAllByTestId('product-sheet-problem');
      await noViolations();
    },
  );

  it.each(BRANDS)(
    'editing with VAT off, a conflict inside it, and the delete confirmation, brand %s',
    async (brand) => {
      await atWidth(1024);
      answers['catalogue-tax-public'] = () => Response.json({ current: null });
      answers['catalogue-product-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
      page(brand);
      fireEvent.click((await screen.findAllByTestId('product-edit'))[0]!);
      await screen.findByTestId('product-sheet-vat-off');
      fireEvent.change(screen.getByTestId('product-sheet-price'), { target: { value: '3300' } });
      fireEvent.click(screen.getByTestId('product-sheet-save'));
      await within(screen.getByTestId('product-sheet')).findByTestId('products-conflict');
      await noViolations();
      fireEvent.click(screen.getByTestId('product-sheet-delete'));
      await screen.findByTestId('product-delete-dialog');
      await noViolations();
    },
  );
});
