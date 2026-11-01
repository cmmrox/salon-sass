import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { CATEGORIES, POPULAR, PRODUCTS, product, RULES, SERVICES, TAX_PUBLIC } from './catalogue-fixtures';
import { ServicesScreen } from './ServicesScreen';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));

let narrow = false;
vi.mock('@/hooks/use-mobile', () => ({ useIsMobile: () => narrow }));

type Call = { action: string; body: unknown };
let calls: Call[];
let answers: Record<string, (body: unknown) => Response>;
beforeEach(() => {
  push.mockReset();
  narrow = false;
  calls = [];
  answers = {
    principal: () =>
      Response.json({ kind: 'staff', staffId: 'me', permissions: ['catalogue.read', 'catalogue.price.change'] }),
    'catalogue-products': () => Response.json(PRODUCTS),
    'catalogue-tax-public': () => Response.json({ current: TAX_PUBLIC }),
    'catalogue-categories': () => Response.json(CATEGORIES),
    'catalogue-services': () => Response.json(SERVICES),
    'catalogue-popular-setting': () => Response.json(POPULAR),
    'booking-rules-public': () => Response.json({ current: RULES }),
  };
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const body = init?.body ? JSON.parse(String(init.body)) : null;
    const action = url.split('/').pop()!.split('?')[0]!;
    calls.push({ action, body });
    return (answers[action] ?? (() => Response.json({ code: 'not_found' }, { status: 404 })))(body);
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const render = (
  canChange = true,
  readOnly: 'support' | 'salon' | null = null,
  tab: 'products' | 'services' = 'products',
) => renderWithMessages(<ServicesScreen kind="staff" tab={tab} canChange={canChange} readOnly={readOnly} />);
const sent = (action: string) => calls.filter((call) => call.action === action).map((call) => call.body);
const row = (name: string) =>
  screen.getAllByTestId('product-row').find((each) => within(each).getByTestId('product-name').textContent === name)!;

describe('the Products tab (F05-S03 AC-7)', () => {
  it('lists each product with its price, tax and whether it is sold, as a table from 900px', async () => {
    render();
    expect(screen.getByTestId('products-loading')).toHaveAttribute('aria-busy', 'true');
    await screen.findByTestId('product-table');
    expect(screen.getByTestId('services-summary')).toHaveTextContent('3 products · 2 sold at checkout');
    expect(screen.getByTestId('services-tab-products')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('products-banner')).toHaveTextContent(
      'Products you sell at the front desk, added to a sale at checkout. Stock isn’t tracked. Prices include VAT where it applies.',
    );
    expect(screen.getByTestId('tax-banner')).toHaveTextContent(
      'Prices you enter include service charge (10%) and VAT (18%). Customers see the same price.',
    );
    expect(screen.getAllByRole('columnheader').map((head) => head.textContent)).toEqual([
      'Product',
      'Price',
      'Tax',
      'At checkout',
      'Actions',
    ]);
    const shampoo = row('Argan oil shampoo 250 ml');
    expect(within(shampoo).getByTestId('product-price')).toHaveTextContent('Rs. 3,200');
    expect(within(shampoo).getByTestId('product-tax')).toHaveTextContent('VAT 18%');
    expect(within(shampoo).getByTestId('product-sold')).toHaveTextContent('Sold');
    expect(within(row('Keratin hair mask 200 ml')).getByTestId('product-tax')).toHaveTextContent('No VAT');
    expect(within(row('Sunscreen SPF 50, 50 ml')).getByTestId('product-sold')).toHaveTextContent('Not sold');
  });

  it('shows cards below 900px, and “—” for tax and the VAT-off sentence while VAT is off', async () => {
    narrow = true;
    answers['catalogue-tax-public'] = () => Response.json({ current: null });
    render();
    expect(await screen.findByTestId('product-cards')).toBeInTheDocument();
    expect(screen.queryByTestId('product-table')).toBeNull();
    expect(within(row('Argan oil shampoo 250 ml')).getByTestId('product-tax')).toHaveTextContent('—');
    expect(screen.getByTestId('products-banner')).toHaveTextContent('VAT is off for your salon.');
    expect(screen.getByTestId('tax-banner')).toHaveTextContent(
      'Tax and service charge are off. Turn them on in Settings if your salon is registered.',
    );
  });

  it('says there are no products yet and offers New product', async () => {
    answers['catalogue-products'] = () => Response.json({ currency: 'LKR', items: [] });
    render();
    expect(await screen.findByTestId('products-empty')).toHaveTextContent(
      'No products yetAdd what you sell at the desk, like shampoo or hair oil.',
    );
    fireEvent.click(screen.getByTestId('products-empty-new'));
    expect(await screen.findByTestId('product-sheet')).toBeInTheDocument();
  });

  it('prices the first product of an empty list in the salon’s currency', async () => {
    answers['catalogue-products'] = () => Response.json({ currency: 'USD', items: [] });
    render();
    fireEvent.click(await screen.findByTestId('products-empty-new'));
    expect(await screen.findByLabelText('Price (USD)')).toBeInTheDocument();
  });

  it('gives a reader the list with no change controls', async () => {
    render(false);
    await screen.findByTestId('product-table');
    expect(screen.queryByTestId('products-new')).toBeNull();
    expect(screen.queryByTestId('product-edit')).toBeNull();
    expect(screen.queryByRole('columnheader', { name: 'Actions' })).toBeNull();
  });

  it('shows the tax banner on the Services tab too (AC-10)', async () => {
    answers['catalogue-tax-public'] = () => Response.json({ current: { ...TAX_PUBLIC, serviceCharge: null } });
    render(true, null, 'services');
    expect(await screen.findByTestId('tax-banner')).toHaveTextContent(
      'Prices you enter include VAT (18%). Customers see the same price.',
    );
  });
});

describe('the product sheet (AC-8)', () => {
  it('adds a product with VAT and sold on by default, in minor units, after checking its fields', async () => {
    answers['catalogue-product-add'] = () => Response.json(product('new', 'Hair oil'), { status: 201 });
    render();
    fireEvent.click(await screen.findByTestId('products-new'));
    const sheet = await screen.findByTestId('product-sheet');
    expect(within(sheet).getByRole('heading', { name: 'New product' })).toBeInTheDocument();
    expect(screen.getByTestId('product-sheet-vat')).toBeChecked();
    expect(screen.getByTestId('product-sheet-sold')).toBeChecked();
    expect(screen.getByTestId('product-sheet-save')).toBeDisabled();
    fireEvent.change(screen.getByTestId('product-sheet-name'), { target: { value: 'H' } });
    fireEvent.change(screen.getByTestId('product-sheet-price'), { target: { value: '500001' } });
    expect(screen.getAllByTestId('product-sheet-problem').map((each) => each.textContent)).toEqual([
      'Product name must be 2 to 40 characters.',
      'Price must be Rs. 1 to Rs. 500,000.',
    ]);
    fireEvent.change(screen.getByTestId('product-sheet-name'), { target: { value: 'ARGAN OIL SHAMPOO 250 ML' } });
    expect(screen.getAllByTestId('product-sheet-problem')[0]).toHaveTextContent(
      'Another product already uses this name.',
    );
    fireEvent.change(screen.getByTestId('product-sheet-name'), { target: { value: ' Hair oil ' } });
    fireEvent.change(screen.getByTestId('product-sheet-price'), { target: { value: '1,500' } });
    fireEvent.click(screen.getByTestId('product-sheet-sold'));
    fireEvent.click(screen.getByTestId('product-sheet-save'));
    expect(await screen.findByText('Product added.')).toBeInTheDocument();
    expect(sent('catalogue-product-add')).toEqual([
      { name: 'Hair oil', priceMinor: 150_000, chargesVat: true, soldAtCheckout: false },
    ]);
  });

  it('hides Charge VAT while VAT is off and keeps the product’s choice', async () => {
    answers['catalogue-tax-public'] = () => Response.json({ current: null });
    answers['catalogue-product-save'] = () => Response.json(PRODUCTS.items[1]);
    render();
    await screen.findByTestId('product-table');
    fireEvent.click(within(row('Keratin hair mask 200 ml')).getByTestId('product-edit'));
    expect(await screen.findByTestId('product-sheet-vat-off')).toHaveTextContent(
      'VAT is off for your salon, so no tax is charged on products.',
    );
    expect(screen.queryByTestId('product-sheet-vat')).toBeNull();
    fireEvent.change(screen.getByTestId('product-sheet-price'), { target: { value: '4600' } });
    fireEvent.click(screen.getByTestId('product-sheet-save'));
    expect(await screen.findByText('Saved. New sales use the new price.')).toBeInTheDocument();
    expect(sent('catalogue-product-save')).toEqual([
      {
        id: PRODUCTS.items[1]!.id,
        version: 0,
        name: 'Keratin hair mask 200 ml',
        priceMinor: 460_000,
        chargesVat: false,
        soldAtCheckout: true,
      },
    ]);
  });

  it('asks before deleting, and deletes from the version read', async () => {
    answers['catalogue-product-delete'] = () => new Response(null, { status: 204 });
    render();
    await screen.findByTestId('product-table');
    fireEvent.click(within(row('Argan oil shampoo 250 ml')).getByTestId('product-edit'));
    fireEvent.click(await screen.findByTestId('product-sheet-delete'));
    const dialog = await screen.findByTestId('product-delete-dialog');
    expect(dialog).toHaveTextContent('Delete “Argan oil shampoo 250 ml”?');
    expect(dialog).toHaveTextContent('It will be removed from checkout. Past sales keep it. This can’t be undone.');
    fireEvent.click(screen.getByTestId('product-delete-confirm'));
    expect(await screen.findByText('Product deleted.')).toBeInTheDocument();
    expect(sent('catalogue-product-delete')).toEqual([{ id: PRODUCTS.items[0]!.id, version: 0 }]);
  });

  it('asks before closing with unsaved edits, and keeps them on Stay', async () => {
    render();
    fireEvent.click(await screen.findByTestId('products-new'));
    fireEvent.change(await screen.findByTestId('product-sheet-name'), { target: { value: 'Comb' } });
    fireEvent.click(screen.getByTestId('product-sheet-close'));
    fireEvent.click(await screen.findByTestId('products-leave-stay'));
    expect(screen.getByTestId('product-sheet-name')).toHaveValue('Comb');
  });

  it('shows a stale save as a conflict inside the sheet, and the limit of 300 as a rule', async () => {
    answers['catalogue-product-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    answers['catalogue-product-add'] = () => Response.json({ code: 'limit_reached' }, { status: 409 });
    render();
    await screen.findByTestId('product-table');
    fireEvent.click(within(row('Argan oil shampoo 250 ml')).getByTestId('product-edit'));
    fireEvent.change(await screen.findByTestId('product-sheet-price'), { target: { value: '3300' } });
    fireEvent.click(screen.getByTestId('product-sheet-save'));
    const sheet = screen.getByTestId('product-sheet');
    expect(await within(sheet).findByTestId('products-conflict')).toHaveTextContent(
      'Someone else changed the products',
    );
    fireEvent.click(within(sheet).getByTestId('products-conflict-reload'));
    await waitFor(() => expect(screen.getByTestId('product-sheet-price')).toHaveValue('3200'));
    fireEvent.click(screen.getByTestId('product-sheet-close'));

    fireEvent.click(screen.getByTestId('products-new'));
    fireEvent.change(await screen.findByTestId('product-sheet-name'), { target: { value: 'Comb' } });
    fireEvent.change(screen.getByTestId('product-sheet-price'), { target: { value: '300' } });
    fireEvent.click(screen.getByTestId('product-sheet-save'));
    expect(await screen.findByTestId('products-rule')).toHaveTextContent(
      'Your salon has 300 products, the most it can have.',
    );
  });

  it('turns read-only and says so when a change is refused', async () => {
    answers['catalogue-product-add'] = () => Response.json({ code: 'permission_denied' }, { status: 403 });
    render();
    fireEvent.click(await screen.findByTestId('products-new'));
    fireEvent.change(await screen.findByTestId('product-sheet-name'), { target: { value: 'Comb' } });
    fireEvent.change(screen.getByTestId('product-sheet-price'), { target: { value: '300' } });
    fireEvent.click(screen.getByTestId('product-sheet-save'));
    expect(await screen.findByTestId('products-refused')).toHaveTextContent(
      'You can’t change products. Ask the salon owner.',
    );
    await waitFor(() => expect(screen.queryByTestId('products-new')).toBeNull());
  });

  it('says when the products cannot be reached, and a support view that it reads only', async () => {
    answers['catalogue-products'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    render(false, 'support');
    expect(await screen.findByTestId('products-unavailable')).toHaveTextContent('The products could not be reached');
    expect(screen.getByTestId('products-read-only')).toHaveTextContent(
      'A support session can read the products but cannot change them.',
    );
  });
});
