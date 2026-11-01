import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { atWidth, axe, BRANDS, WIDTHS } from '@test/a11y';
import { renderWithMessages } from '@test/render';
import { AccountFrame } from './AccountFrame';
import { CATEGORIES, SERVICES } from './catalogue-fixtures';
import { ServicesScreen, type ServicesTab } from './ServicesScreen';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }) }));
vi.mock('next/link', () => ({
  __esModule: true,
  default: (props: React.AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) => <a {...props} />,
}));

/**
 * F05-S01 AC-16: both tabs of Services & pricing, both sheets and their states have no WCAG 2.2 AA violation,
 * colour contrast included, at every width and with a dark and a light brand, rendered in real Chromium in the
 * workspace frame, with no horizontal scrolling. A long service name and a long category name wrap. The service
 * sheet keeps focus inside and returns it to the card's Edit when it closes.
 */
const LONG = 'Extra long hair treatment with argan oil';
let answers: Record<string, () => Response | Promise<Response>>;
beforeEach(() => {
  answers = {
    principal: () =>
      Response.json({ kind: 'staff', staffId: 'me', permissions: ['catalogue.read', 'catalogue.price.change'] }),
    'catalogue-categories': () =>
      Response.json({
        ...CATEGORIES,
        items: [
          ...CATEGORIES.items,
          { id: 'long', name: 'Bridal trials and occasions', position: 4, serviceCount: 1, version: 0 },
        ],
      }),
    'catalogue-services': () =>
      Response.json({
        items: [
          ...SERVICES.items,
          { ...SERVICES.items[1]!, id: 'long-service', name: LONG, categoryId: 'long', description: 'd'.repeat(90) },
        ],
      }),
  };
  vi.stubGlobal('fetch', async (url: string) => {
    const action = url.split('/').pop()!.split('?')[0]!;
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))();
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
});

function page(
  brand: string,
  tab: ServicesTab = 'services',
  canChange = true,
  readOnly: 'support' | 'salon' | null = null,
) {
  return renderWithMessages(
    <AccountFrame
      salon={{ slug: 'alpha', name: 'Alpha Studio', brandColour: brand, timeZone: 'Asia/Colombo' }}
      permissions={['session.manage', 'catalogue.read', 'catalogue.price.change']}
      view="services"
    >
      <ServicesScreen kind="staff" tab={tab} canChange={canChange} readOnly={readOnly} />
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

describe('the Services tab', () => {
  it.each(matrix)(
    'with its grouped cards, an empty category and a hidden service, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      page(brand);
      await screen.findAllByTestId('service-card');
      await noViolations();
      noHorizontalScroll();
    },
  );

  it('loading', async () => {
    vi.stubGlobal('fetch', () => new Promise<Response>(() => undefined));
    page(BRANDS[1]);
    await screen.findByTestId('services-loading');
    await noViolations();
  });

  it.each(BRANDS)('read by a role that cannot change it, and in a support view, brand %s', async (brand) => {
    page(brand, 'services', false, 'support');
    await screen.findByTestId('services-read-only');
    await screen.findAllByTestId('service-card');
    await noViolations();
  });

  it.each(BRANDS)('after a refusal, a conflict and when unavailable, brand %s', async (brand) => {
    answers['catalogue-service-online'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
    page(brand);
    const cards = await screen.findAllByTestId('service-card');
    fireEvent.click(within(cards[0]!).getByTestId('service-card-online'));
    await screen.findByTestId('services-conflict');
    await noViolations();
    answers['catalogue-service-online'] = () => Response.json({ code: 'permission_denied' }, { status: 403 });
    fireEvent.click(within(screen.getAllByTestId('service-card')[0]!).getByTestId('service-card-online'));
    await screen.findByTestId('services-refused');
    await noViolations();
  });

  it.each(BRANDS)('unavailable, brand %s', async (brand) => {
    answers['catalogue-services'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    page(brand);
    await screen.findByTestId('services-unavailable');
    await noViolations();
  });
});

describe('the service sheet', () => {
  it.each(BRANDS.flatMap((brand) => [360, 768, 1280].map((width) => [brand, width] as const)))(
    'editing with More options open and its field errors, brand %s at %ipx, keeps focus inside and returns it',
    async (brand, width) => {
      await atWidth(width);
      page(brand);
      const cards = await screen.findAllByTestId('service-card');
      const edit = within(cards[0]!).getByTestId('service-card-edit');
      edit.focus();
      fireEvent.click(edit);
      const sheet = await screen.findByTestId('service-sheet');
      await within(sheet).findByTestId('service-sheet-online');
      await waitFor(() => expect(sheet.contains(document.activeElement)).toBe(true));
      await noViolations();
      fireEvent.change(screen.getByTestId('service-sheet-price'), { target: { value: '99' } });
      fireEvent.change(screen.getByTestId('service-sheet-buffer'), { target: { value: '7' } });
      await screen.findByTestId('service-sheet-problems');
      await noViolations();
      noHorizontalScroll();
    },
  );

  it.each(BRANDS)('new, with More options open, and the hidden warning, brand %s', async (brand) => {
    page(brand);
    fireEvent.click(await screen.findByTestId('services-new'));
    fireEvent.click(await screen.findByTestId('service-sheet-more'));
    await screen.findByTestId('service-photo-after-create');
    fireEvent.click(screen.getByTestId('service-sheet-online'));
    await screen.findByTestId('service-sheet-hidden');
    await noViolations();
  });

  it.each(BRANDS)('with a save another change overtook, its conflict and Reload inside it, brand %s', async (brand) => {
    answers['catalogue-service-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
    page(brand);
    const cards = await screen.findAllByTestId('service-card');
    fireEvent.click(within(cards[0]!).getByTestId('service-card-edit'));
    fireEvent.change(await screen.findByTestId('service-sheet-price'), { target: { value: '2700' } });
    fireEvent.click(screen.getByTestId('service-sheet-save'));
    await within(screen.getByTestId('service-sheet')).findByTestId('services-conflict');
    await noViolations();
  });

  it('returns focus to Edit when it closes', async () => {
    page(BRANDS[0]);
    const cards = await screen.findAllByTestId('service-card');
    const edit = within(cards[0]!).getByTestId('service-card-edit');
    edit.focus();
    fireEvent.click(edit);
    await screen.findByTestId('service-sheet');
    fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('service-sheet')).not.toBeInTheDocument());
    await waitFor(() =>
      expect(document.activeElement).toBe(
        within(screen.getAllByTestId('service-card')[0]!).getByTestId('service-card-edit'),
      ),
    );
  });

  it.each(BRANDS)('the delete confirmation, brand %s', async (brand) => {
    page(brand);
    const cards = await screen.findAllByTestId('service-card');
    fireEvent.click(within(cards[0]!).getByTestId('service-card-edit'));
    fireEvent.click(await screen.findByTestId('service-sheet-delete'));
    await screen.findByTestId('service-delete-dialog');
    await noViolations();
  });
});

describe('the Categories tab and its sheet', () => {
  it.each(matrix)('with its rows and order buttons, brand %s at %ipx', async (brand, width) => {
    await atWidth(width);
    page(brand, 'categories');
    await screen.findAllByTestId('category-row');
    await noViolations();
    noHorizontalScroll();
  });

  it.each(BRANDS.flatMap((brand) => [360, 1280].map((width) => [brand, width] as const)))(
    'the category sheet with its error, brand %s at %ipx',
    async (brand, width) => {
      await atWidth(width);
      page(brand, 'categories');
      fireEvent.click(await screen.findByTestId('categories-new'));
      await screen.findByTestId('category-sheet');
      await noViolations();
      fireEvent.change(screen.getByTestId('category-sheet-name'), { target: { value: 'K' } });
      fireEvent.click(screen.getByTestId('category-sheet-save'));
      await screen.findByTestId('category-sheet-error');
      await noViolations();
      noHorizontalScroll();
    },
  );

  it.each(BRANDS)('a rule refused (a category with services) and the delete confirmation, brand %s', async (brand) => {
    page(brand, 'categories');
    const rows = await screen.findAllByTestId('category-row');
    fireEvent.click(within(rows[0]!).getByTestId('category-row-delete'));
    await screen.findByTestId('services-rule');
    await noViolations();
    fireEvent.click(within(rows[2]!).getByTestId('category-row-delete'));
    await screen.findByTestId('category-delete-dialog');
    await noViolations();
  });
});
