import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import {
  CATEGORIES,
  COLOUR,
  NAILS,
  POPULAR,
  RULES,
  SERVICES,
  service,
  TAX_PUBLIC,
  withVariants,
} from './catalogue-fixtures';
import type { CatalogueService } from '@/features/catalogue';
import { ServicesScreen, type ServicesTab } from './ServicesScreen';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));

type Call = { action: string; body: unknown };
let calls: Call[];
let answers: Record<string, (body: unknown) => Response>;
beforeEach(() => {
  push.mockReset();
  calls = [];
  answers = {
    principal: () =>
      Response.json({ kind: 'staff', staffId: 'me', permissions: ['catalogue.read', 'catalogue.price.change'] }),
    'catalogue-categories': () => Response.json(CATEGORIES),
    'catalogue-services': () => Response.json(SERVICES),
    'catalogue-popular-setting': () => Response.json(POPULAR),
    'booking-rules-public': () => Response.json({ current: RULES }),
  };
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  // jsdom has no ResizeObserver; Radix Switch measures itself with one.
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

const render = (tab: ServicesTab = 'services', canChange = true, readOnly: 'support' | 'salon' | null = null) =>
  renderWithMessages(<ServicesScreen kind="staff" tab={tab} canChange={canChange} readOnly={readOnly} />);
const sent = (action: string) => calls.filter((call) => call.action === action).map((call) => call.body);
const cards = () => screen.getAllByTestId('service-card');
const card = (name: string) =>
  cards().find((each) => within(each).getByTestId('service-card-name').textContent === name)!;

describe('the Services tab (F05-S01 AC-12)', () => {
  it('shows a loading state, then every service under its category in the salon’s order', async () => {
    render();
    expect(screen.getByTestId('services-loading')).toHaveAttribute('aria-busy', 'true');

    await screen.findAllByTestId('service-card');
    expect(screen.getByTestId('services-summary')).toHaveTextContent('3 services · 2 bookable online');
    expect(screen.getByTestId('services-banner')).toHaveTextContent(
      'List your services with their price and time. Changes apply to new bookings only.',
    );
    // The prototype's information banner (`.bn.in`), not a plain card.
    expect(screen.getByTestId('services-banner')).toHaveAttribute('data-variant', 'info');
    const groups = screen.getAllByTestId('service-group');
    expect(groups.map((group) => group.getAttribute('data-id'))).toEqual([COLOUR, NAILS]);
    expect(
      within(groups[0]!)
        .getAllByTestId('service-card-name')
        .map((name) => name.textContent),
    ).toEqual(['Balayage', 'zebra highlights']);
    expect(screen.getByTestId('services-empty-categories')).toHaveTextContent('No services yet in: Waxing.');
  });

  it('shows on each card its status, category and description, price, time and buffer', async () => {
    render();
    await screen.findAllByTestId('service-card');
    const zebra = card('zebra highlights');
    expect(within(zebra).getByTestId('service-card-status')).toHaveTextContent('Online');
    expect(within(zebra).getByTestId('service-card-detail')).toHaveTextContent('Colour · Fine foils');
    expect(within(zebra).getByTestId('service-card-price')).toHaveTextContent('Rs. 9,000');
    expect(within(zebra).getByTestId('service-card-time')).toHaveTextContent('120 min');
    expect(within(zebra).getByTestId('service-card-buffer')).toHaveTextContent('+15 min buffer');
    const gel = card('Gel polish');
    expect(within(gel).getByTestId('service-card-status')).toHaveTextContent('Hidden online');
    expect(within(gel).getByTestId('service-card-online')).toHaveTextContent('Show online');
    expect(within(gel).queryByTestId('service-card-buffer')).toBeNull();
  });

  it('gives a reader no change controls (AC-13)', async () => {
    render('services', false);
    await screen.findAllByTestId('service-card');
    expect(screen.queryByTestId('services-new')).toBeNull();
    expect(screen.queryByTestId('service-card-edit')).toBeNull();
    expect(screen.queryByTestId('service-card-online')).toBeNull();
    expect(screen.queryByTestId('service-card-duplicate')).toBeNull();
  });

  it('says a support view is read-only', async () => {
    render('services', false, 'support');
    expect(await screen.findByTestId('services-read-only')).toHaveTextContent(
      'A support session can read the services but cannot change them.',
    );
  });
});

describe('categories with no services (F05-S01 AC-18, CR-025)', () => {
  const categories = (...names: string[]) => ({
    listVersion: 1,
    items: names.map((name, index) => ({ id: name, name, position: index + 1, serviceCount: 0, version: 0 })),
  });

  it('folds them into one line after the listed ones, in the salon’s order', async () => {
    answers['catalogue-categories'] = () => Response.json(categories('Spa', 'Nails', 'Cuts', 'Waxing'));
    answers['catalogue-services'] = () =>
      Response.json({ ...SERVICES, items: [service('s1', 'Gel polish', 'Nails'), service('s2', 'Trim', 'Cuts')] });
    render();
    await screen.findAllByTestId('service-card');

    expect(screen.getAllByTestId('service-group').map((group) => group.getAttribute('data-id'))).toEqual([
      'Nails',
      'Cuts',
    ]);
    const folded = screen.getByTestId('services-empty-categories');
    expect(folded).toHaveTextContent('No services yet in: Spa, Waxing.');
    // After the listed categories.
    const last = screen.getAllByTestId('service-group').at(-1)!;
    expect(last.compareDocumentPosition(folded) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Spa' })).toBeNull();
  });

  it('says nothing folded when every category has a service', async () => {
    answers['catalogue-categories'] = () => Response.json(categories('Nails'));
    answers['catalogue-services'] = () => Response.json({ ...SERVICES, items: [service('s1', 'Gel polish', 'Nails')] });
    render();
    await screen.findAllByTestId('service-card');
    expect(screen.queryByTestId('services-empty-categories')).toBeNull();
  });

  it('shows a salon with no services the Empty state and no folded line, with + New service for who may', async () => {
    answers['catalogue-services'] = () => Response.json({ ...SERVICES, items: [] });
    render();
    const empty = await screen.findByTestId('services-empty');
    expect(empty).toHaveTextContent('No services yet');
    expect(screen.queryByTestId('services-empty-categories')).toBeNull();
    expect(screen.queryByTestId('service-group')).toBeNull();

    fireEvent.click(within(empty).getByTestId('services-empty-new'));
    expect(await screen.findByRole('heading', { name: 'New service' })).toBeInTheDocument();
  });

  it('gives a reader the Empty state without + New service', async () => {
    answers['catalogue-services'] = () => Response.json({ ...SERVICES, items: [] });
    render('services', false);
    const empty = await screen.findByTestId('services-empty');
    expect(within(empty).queryByTestId('services-empty-new')).toBeNull();
  });

  it('keeps every category on the Categories tab', async () => {
    answers['catalogue-services'] = () => Response.json({ ...SERVICES, items: [] });
    render('categories');
    expect(await screen.findAllByTestId('category-row')).toHaveLength(3);
    expect(screen.queryByTestId('services-empty')).toBeNull();
  });
});

describe('the Services tab’s one banner (F05-S01 AC-17, CR-025)', () => {
  const FIRST = 'List your services with their price and time. Changes apply to new bookings only.';
  const infoAlerts = () => document.querySelectorAll('[data-slot="alert"][data-variant="info"]');

  it.each([
    ['both', TAX_PUBLIC, 'Prices you enter include service charge (10%) and VAT (18%). Customers see the same price.'],
    [
      'VAT only',
      { ...TAX_PUBLIC, serviceCharge: null },
      'Prices you enter include VAT (18%). Customers see the same price.',
    ],
    [
      'service charge only',
      { ...TAX_PUBLIC, vat: null },
      'Prices you enter include service charge (10%). Customers see the same price.',
    ],
    ['neither', null, 'Tax and service charge are off. Turn them on in Settings if your salon is registered.'],
  ])('carries both sentences in one Alert: %s', async (_name, current, sentence) => {
    answers['catalogue-tax-public'] = () => Response.json({ current });
    render();
    await screen.findByTestId('tax-banner');

    expect(screen.getByTestId('services-banner')).toHaveTextContent(`${FIRST} ${sentence}`, {
      normalizeWhitespace: true,
    });
    expect(screen.getByTestId('services-banner')).toHaveAttribute('data-variant', 'info');
    expect(infoAlerts()).toHaveLength(1);
  });

  it('says only its own sentence while the salon’s tax cannot be read', async () => {
    render();
    await screen.findAllByTestId('service-card');
    expect(screen.getByTestId('services-banner').textContent?.trim()).toBe(FIRST);
    expect(screen.queryByTestId('tax-banner')).toBeNull();
    expect(infoAlerts()).toHaveLength(1);
  });
});

describe('the service sheet (AC-3, AC-4, AC-16)', () => {
  it('opens a new service with Create disabled until it has a name, and says what is wrong', async () => {
    render();
    fireEvent.click(await screen.findByTestId('services-new'));
    const sheet = await screen.findByTestId('service-sheet');
    expect(within(sheet).getByRole('heading', { name: 'New service' })).toBeInTheDocument();
    expect(screen.getByTestId('service-sheet-save')).toBeDisabled();
    expect(screen.queryByTestId('service-sheet-problems')).toBeNull();
    fireEvent.click(screen.getByTestId('service-sheet-more'));
    expect(await screen.findByTestId('service-photo-after-create')).toHaveTextContent(
      'Add a photo after you create the service.',
    );

    fireEvent.change(screen.getByTestId('service-sheet-name'), { target: { value: 'Root touch-up' } });
    fireEvent.change(screen.getByTestId('service-sheet-price'), { target: { value: '99' } });
    fireEvent.change(screen.getByTestId('service-sheet-time'), { target: { value: '7' } });

    const problems = screen.getAllByTestId('service-sheet-problem').map((problem) => problem.textContent);
    expect(problems).toEqual([
      'Price must be Rs. 100 to Rs. 500,000.',
      'Time must be 5 to 480 minutes, in steps of 5.',
    ]);
    expect(screen.getByTestId('service-sheet-price')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('service-sheet-save')).toBeDisabled();
  });

  it('says a name is taken in any case before sending it', async () => {
    render();
    fireEvent.click(await screen.findByTestId('services-new'));
    fireEvent.change(await screen.findByTestId('service-sheet-name'), { target: { value: 'BALAYAGE' } });
    expect(screen.getByTestId('service-sheet-problem')).toHaveTextContent('Another service already uses this name.');
  });

  it('creates a service in minor units and says so', async () => {
    answers['catalogue-service-create'] = () => Response.json(service('new', 'Root touch-up', COLOUR), { status: 201 });
    render();
    fireEvent.click(await screen.findByTestId('services-new'));
    fireEvent.change(await screen.findByTestId('service-sheet-name'), { target: { value: ' Root touch-up ' } });
    fireEvent.change(screen.getByTestId('service-sheet-price'), { target: { value: '4,500' } });
    fireEvent.click(screen.getByTestId('service-sheet-save'));

    await waitFor(() => expect(screen.queryByTestId('service-sheet')).toBeNull());
    expect(sent('catalogue-service-create')).toEqual([
      {
        name: 'Root touch-up',
        categoryId: COLOUR,
        description: null,
        priceMinor: 450_000,
        durationMinutes: 60,
        bufferMinutes: 0,
        bookableOnline: true,
        deposit: { kind: 'salon_rule' },
      },
    ]);
    expect(sent('catalogue-service-popular')).toEqual([]);
    expect(await screen.findByText('Service created.')).toBeInTheDocument();
  });

  it('after a price change says new bookings use the new prices (AC-4)', async () => {
    const before = SERVICES.items[1]!;
    answers['catalogue-service-save'] = () =>
      Response.json({
        ...before,
        version: 1,
        terms: { ...before.terms, versionId: 'v2', number: 2, priceMinor: 1_300_000 },
      });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-edit'));
    fireEvent.change(await screen.findByTestId('service-sheet-price'), { target: { value: '13000' } });
    fireEvent.click(screen.getByTestId('service-sheet-save'));

    expect(await screen.findByText('Saved. New bookings use the new prices.')).toBeInTheDocument();
    expect(sent('catalogue-service-save')).toEqual([
      expect.objectContaining({ id: before.id, priceMinor: 1_300_000, version: 0, bookableOnline: true }),
    ]);
  });

  it('shows the API’s field error on its field and a stale save as a conflict', async () => {
    answers['catalogue-service-save'] = () =>
      Response.json(
        { code: 'bad_request', errors: [{ field: 'name', detail: 'is used by another service' }] },
        { status: 400 },
      );
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-edit'));
    fireEvent.change(await screen.findByTestId('service-sheet-name'), { target: { value: 'Ombre' } });
    fireEvent.click(screen.getByTestId('service-sheet-save'));
    expect(await screen.findByTestId('service-sheet-problem')).toHaveTextContent(
      'Another service already uses this name.',
    );

    answers['catalogue-service-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    fireEvent.change(screen.getByTestId('service-sheet-name'), { target: { value: 'Ombre balayage' } });
    fireEvent.click(screen.getByTestId('service-sheet-save'));
    expect(await within(screen.getByTestId('service-sheet')).findByTestId('services-conflict')).toHaveTextContent(
      'Someone else changed the services',
    );
  });

  it('asks before closing with unsaved edits, and keeps them on Stay', async () => {
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-edit'));
    fireEvent.change(await screen.findByTestId('service-sheet-name'), { target: { value: 'Ombre' } });
    fireEvent.click(screen.getByTestId('service-sheet-close'));

    fireEvent.click(await screen.findByTestId('services-leave-stay'));
    expect(screen.getByTestId('service-sheet-name')).toHaveValue('Ombre');
    fireEvent.click(screen.getByTestId('service-sheet-close'));
    fireEvent.click(await screen.findByTestId('services-leave-confirm'));
    await waitFor(() => expect(screen.queryByTestId('service-sheet')).toBeNull());
  });

  it('warns that a hidden service is hidden', async () => {
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Gel polish')).getByTestId('service-card-edit'));
    expect(await screen.findByTestId('service-sheet-hidden')).toHaveTextContent(
      'This service is hidden from online booking. Turn it on when you’re ready.',
    );
  });
});

describe('the card actions (AC-7, AC-8, AC-9)', () => {
  it('duplicates, opens the copy and says it is hidden', async () => {
    const copy = { ...service('copy', 'Balayage (copy)', COLOUR), bookableOnline: false };
    answers['catalogue-service-duplicate'] = () => Response.json(copy, { status: 201 });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-duplicate'));

    expect(await screen.findByText('Duplicated and hidden online. Review it, then show it.')).toBeInTheDocument();
    expect(await screen.findByTestId('service-sheet-name')).toHaveValue('Balayage (copy)');
    expect(sent('catalogue-service-duplicate')).toEqual([{ id: SERVICES.items[1]!.id }]);
  });

  it('hides and shows a service online', async () => {
    answers['catalogue-service-online'] = () => Response.json(SERVICES.items[1]);
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-online'));
    expect(
      await screen.findByText('Hidden from online booking. Existing bookings are not affected.'),
    ).toBeInTheDocument();
    fireEvent.click(within(card('Gel polish')).getByTestId('service-card-online'));
    expect(await screen.findByText('Now bookable online.')).toBeInTheDocument();
    expect(sent('catalogue-service-online')).toEqual([
      { id: SERVICES.items[1]!.id, bookableOnline: false, version: 0 },
      { id: SERVICES.items[2]!.id, bookableOnline: true, version: 0 },
    ]);
  });

  it('keeps keyboard focus on Hide online and Duplicate while their change runs, and ignores a second press', async () => {
    let release: (answer: Response) => void = () => {};
    answers['catalogue-service-online'] = () =>
      new Promise<Response>((resolve) => (release = resolve)) as unknown as Response;
    render();
    await screen.findAllByTestId('service-card');
    const hide = within(card('Balayage')).getByTestId('service-card-online');
    const duplicate = within(card('Balayage')).getByTestId('service-card-duplicate');
    hide.focus();
    fireEvent.click(hide);
    await waitFor(() => expect(sent('catalogue-service-online')).toHaveLength(1));
    // While the change runs the buttons say they are busy but stay focusable, so focus is not dropped to the page.
    expect(hide).not.toBeDisabled();
    expect(hide).toHaveAttribute('aria-disabled', 'true');
    expect(hide).toHaveFocus();
    fireEvent.click(hide);
    fireEvent.click(duplicate);
    expect(sent('catalogue-service-online')).toHaveLength(1);
    expect(sent('catalogue-service-duplicate')).toHaveLength(0);
    release(Response.json({ ...SERVICES.items[1]!, bookableOnline: false }));
    expect(
      await screen.findByText('Hidden from online booking. Existing bookings are not affected.'),
    ).toBeInTheDocument();
    expect(within(card('Balayage')).getByTestId('service-card-online')).toHaveFocus();
  });

  it('shows a save another change overtook inside the sheet, and Reload refreshes the sheet and the list (AC-16 conflict)', async () => {
    answers['catalogue-service-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Gel polish')).getByTestId('service-card-edit'));
    fireEvent.change(await screen.findByTestId('service-sheet-price'), { target: { value: '2700' } });
    fireEvent.click(screen.getByTestId('service-sheet-save'));

    const sheet = screen.getByTestId('service-sheet');
    const conflict = await within(sheet).findByTestId('services-conflict');
    expect(conflict).toHaveTextContent('Someone else changed the services');
    expect(screen.getAllByTestId('services-conflict')).toHaveLength(1);

    // Someone else set Rs. 2,600 meanwhile.
    const changed = service('11111111-0000-4000-8000-000000000003', 'Gel polish', NAILS, {
      priceMinor: 260_000,
      minutes: 45,
      online: false,
    });
    answers['catalogue-services'] = () =>
      Response.json({ ...SERVICES, items: [...SERVICES.items.slice(0, 2), { ...changed, version: 1 }] });
    fireEvent.click(within(conflict).getByTestId('services-conflict-reload'));

    await waitFor(() => expect(screen.getByTestId('service-sheet-price')).toHaveValue('2600'));
    expect(screen.queryByTestId('services-conflict')).toBeNull();
    expect(within(card('Gel polish')).getByTestId('service-card-price')).toHaveTextContent('Rs. 2,600');
    expect(sent('catalogue-service-save')).toHaveLength(1);
    // The next save is made from the version read again.
    fireEvent.change(screen.getByTestId('service-sheet-price'), { target: { value: '2700' } });
    answers['catalogue-service-save'] = () => Response.json({ ...changed, version: 2 }, { status: 200 });
    fireEvent.click(screen.getByTestId('service-sheet-save'));
    await waitFor(() => expect(sent('catalogue-service-save')).toHaveLength(2));
    expect(sent('catalogue-service-save')[1]).toMatchObject({ version: 1, priceMinor: 270_000 });
  });

  it('shows a rule the API refuses while the sheet is open inside the sheet', async () => {
    answers['catalogue-service-delete'] = () => Response.json({ code: 'in_use' }, { status: 409 });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-edit'));
    fireEvent.click(await screen.findByTestId('service-sheet-delete'));
    fireEvent.click(await screen.findByTestId('service-delete-confirm'));

    const sheet = screen.getByTestId('service-sheet');
    expect(await within(sheet).findByTestId('services-rule')).toHaveTextContent(
      'This service has upcoming bookings. Hide it from online booking instead.',
    );
  });

  it('asks before deleting, and says to hide a service upcoming bookings use', async () => {
    answers['catalogue-service-delete'] = () => Response.json({ code: 'in_use' }, { status: 409 });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-edit'));
    fireEvent.click(await screen.findByTestId('service-sheet-delete'));
    const dialog = await screen.findByTestId('service-delete-dialog');
    expect(dialog).toHaveTextContent('Delete “Balayage”?');
    expect(dialog).toHaveTextContent(
      'It will be removed from your menu. Past bookings are kept. This can’t be undone.',
    );
    fireEvent.click(screen.getByTestId('service-delete-confirm'));

    expect(await screen.findByTestId('services-rule')).toHaveTextContent(
      'This service has upcoming bookings. Hide it from online booking instead.',
    );
  });

  it('turns read-only and says so when a change is refused (AC-16 refused)', async () => {
    answers['catalogue-service-online'] = () => Response.json({ code: 'permission_denied' }, { status: 403 });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-online'));

    expect(await screen.findByTestId('services-refused')).toHaveTextContent(
      'You can’t change services. Ask the salon owner.',
    );
    await waitFor(() => expect(screen.queryByTestId('service-card-online')).toBeNull());
    expect(screen.queryByTestId('services-new')).toBeNull();
  });

  it('says when the services cannot be reached', async () => {
    answers['catalogue-services'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    render();
    expect(await screen.findByTestId('services-unavailable')).toHaveTextContent('The services could not be reached');
    expect(screen.getByTestId('services-retry')).toBeInTheDocument();
  });
});

describe('the Categories tab (AC-2)', () => {
  it('lists each category with its count and the order buttons, the first not up and the last not down', async () => {
    render('categories');
    const rows = await screen.findAllByTestId('category-row');
    expect(screen.getByTestId('services-summary')).toHaveTextContent('3 categories');
    expect(screen.getByTestId('services-banner')).toHaveTextContent(
      'Customers see categories in this order. A category with nothing bookable online is hidden from them.',
    );
    expect(screen.getByTestId('services-banner')).toHaveAttribute('data-variant', 'info');
    expect(rows.map((row) => within(row).getByTestId('category-row-count').textContent)).toEqual([
      '2 services',
      '1 service',
      'No services',
    ]);
    expect(within(rows[0]!).getByTestId('category-row-up')).toBeDisabled();
    expect(within(rows[0]!).getByTestId('category-row-up')).toHaveAccessibleName('Move Colour up');
    expect(within(rows[2]!).getByTestId('category-row-down')).toBeDisabled();
  });

  it('moves a category from the list version read, and says the order is saved', async () => {
    answers['catalogue-category-move'] = () =>
      Response.json({ listVersion: 5, items: [CATEGORIES.items[1], CATEGORIES.items[0], CATEGORIES.items[2]] });
    render('categories');
    const rows = await screen.findAllByTestId('category-row');
    fireEvent.click(within(rows[1]!).getByTestId('category-row-up'));

    expect(await screen.findByText('Order saved. Customers see it straight away.')).toBeInTheDocument();
    expect(sent('catalogue-category-move')).toEqual([{ id: NAILS, direction: 'up', listVersion: 4 }]);
    await waitFor(() =>
      expect(screen.getAllByTestId('category-row-name').map((name) => name.textContent)).toEqual([
        'Nails',
        'Colour',
        'Waxing',
      ]),
    );
  });

  it('says to move a category’s services before deleting it, and asks before deleting an empty one', async () => {
    answers['catalogue-category-delete'] = () => new Response(null, { status: 200 });
    render('categories');
    const rows = await screen.findAllByTestId('category-row');
    fireEvent.click(within(rows[0]!).getByTestId('category-row-delete'));
    expect(screen.getByTestId('services-rule')).toHaveTextContent('Move its 2 services to another category first.');

    fireEvent.click(within(rows[2]!).getByTestId('category-row-delete'));
    expect(await screen.findByTestId('category-delete-dialog')).toHaveTextContent('Delete “Waxing”?');
  });

  it('adds a category after checking its name', async () => {
    answers['catalogue-category-add'] = () =>
      Response.json({ id: 'k', name: 'Kids', position: 4, serviceCount: 0, version: 0 }, { status: 201 });
    render('categories');
    fireEvent.click(await screen.findByTestId('categories-new'));
    const sheet = await screen.findByTestId('category-sheet');
    expect(within(sheet).getByRole('heading', { name: 'New category' })).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('category-sheet-name'), { target: { value: 'colour' } });
    fireEvent.click(screen.getByTestId('category-sheet-save'));
    expect(await screen.findByTestId('category-sheet-error')).toHaveTextContent(
      'Another category already uses this name.',
    );
    expect(sent('catalogue-category-add')).toEqual([]);

    fireEvent.change(screen.getByTestId('category-sheet-name'), { target: { value: ' Kids ' } });
    fireEvent.click(screen.getByTestId('category-sheet-save'));
    expect(await screen.findByText('Category added.')).toBeInTheDocument();
    expect(sent('catalogue-category-add')).toEqual([{ name: 'Kids' }]);
  });

  it('shows a rename another change overtook inside the category sheet, and Reload refreshes it', async () => {
    answers['catalogue-category-rename'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    render('categories');
    const rows = await screen.findAllByTestId('category-row');
    fireEvent.click(within(rows[1]!).getByTestId('category-row-rename'));
    fireEvent.change(await screen.findByTestId('category-sheet-name'), { target: { value: 'Nail art' } });
    fireEvent.click(screen.getByTestId('category-sheet-save'));

    const sheet = screen.getByTestId('category-sheet');
    const conflict = await within(sheet).findByTestId('services-conflict');
    answers['catalogue-categories'] = () =>
      Response.json({
        ...CATEGORIES,
        items: CATEGORIES.items.map((each) =>
          each.id === NAILS ? { ...each, name: 'Nails & hands', version: 1 } : each,
        ),
      });
    fireEvent.click(within(conflict).getByTestId('services-conflict-reload'));

    await waitFor(() => expect(screen.getByTestId('category-sheet-name')).toHaveValue('Nails & hands'));
    expect(screen.queryByTestId('services-conflict')).toBeNull();
    expect(sent('catalogue-category-rename')).toHaveLength(1);
  });
});

const CUT = withVariants(service('11111111-0000-4000-8000-000000000009', 'Haircut', COLOUR), 'Hair length', [
  ['Short (to the chin)', 2500, 30],
  ['Medium', 3200, 45],
  ['Long', 4000, 60],
  ['Extra long', 4500, 75],
  ['Very long', 5000, 90],
]);
const EVEN = withVariants(service('11111111-0000-4000-8000-000000000010', 'Kids cut', COLOUR), 'Who is it for?', [
  ['Boys', 1500, 30],
  ['Girls', 1500, 30],
]);
const withServices =
  (...extra: CatalogueService[]) =>
  () =>
    Response.json({ ...SERVICES, items: [...SERVICES.items, ...extra] });

describe('variants on the card and in the sheet (F05-S02 AC-1, AC-3)', () => {
  it('shows a "From" price, a range of times, the set with its first four names and the badges', async () => {
    answers['catalogue-services'] = withServices(CUT, EVEN);
    render();
    await screen.findAllByTestId('service-card');
    const cut = card('Haircut');
    expect(within(cut).getByTestId('service-card-price')).toHaveTextContent('From Rs. 2,500');
    expect(within(cut).getByTestId('service-card-time')).toHaveTextContent('30 to 90 min');
    expect(within(cut).getByTestId('service-card-options')).toHaveTextContent(
      'Hair lengthShort (to the chin), Medium, Long, Extra long +1',
    );
    expect(within(cut).getByTestId('service-card-from-badge')).toHaveTextContent('From price');
    expect(within(cut).getByTestId('service-card-options-badge')).toHaveTextContent('5 options');
    const even = card('Kids cut');
    expect(within(even).getByTestId('service-card-price')).toHaveTextContent(/^Rs\. 1,500$/);
    expect(within(even).getByTestId('service-card-time')).toHaveTextContent(/^30 min$/);
    expect(within(even).queryByTestId('service-card-from-badge')).toBeNull();
    expect(within(even).getByTestId('service-card-options-badge')).toHaveTextContent('2 options');
    expect(within(card('Balayage')).queryByTestId('service-card-options')).toBeNull();
  });

  it('turns variants on, names each field, previews the price list and sends them without a price of its own', async () => {
    answers['catalogue-service-create'] = () => Response.json(CUT, { status: 201 });
    render();
    fireEvent.click(await screen.findByTestId('services-new'));
    fireEvent.change(await screen.findByTestId('service-sheet-name'), { target: { value: 'Haircut' } });
    fireEvent.click(screen.getByTestId('service-variants-toggle'));

    expect(screen.queryByTestId('service-sheet-price')).toBeNull();
    expect(screen.getByLabelText('Variant 2 price')).toHaveValue('2500');
    expect(screen.getByRole('button', { name: 'Remove variant 2' })).toBeInTheDocument();
    expect(screen.getByTestId('service-sheet-problem')).toHaveTextContent('Name the variants, 2 to 30 characters.');
    fireEvent.change(screen.getByTestId('service-variant-set'), { target: { value: 'Hair length' } });
    fireEvent.change(screen.getByLabelText('Variant 1 name'), { target: { value: 'Short' } });
    fireEvent.change(screen.getByLabelText('Variant 2 name'), { target: { value: 'Long' } });
    fireEvent.change(screen.getByLabelText('Variant 2 price'), { target: { value: '4000' } });
    fireEvent.change(screen.getByLabelText('Variant 2 time'), { target: { value: '75' } });
    expect(screen.getByTestId('service-sheet-variant-price')).toHaveTextContent(
      'Prices come from the variants below (From Rs. 2,500).',
    );
    expect(screen.getAllByTestId('service-variant-preview-row').map((row) => row.textContent)).toEqual([
      'ShortRs. 2,500· 60 min',
      'LongRs. 4,000· 75 min',
    ]);
    fireEvent.click(screen.getByTestId('service-sheet-save'));

    await waitFor(() => expect(sent('catalogue-service-create')).toHaveLength(1));
    const body = sent('catalogue-service-create')[0] as Record<string, unknown>;
    expect(body).not.toHaveProperty('priceMinor');
    expect(body.variants).toEqual({
      name: 'Hair length',
      items: [
        { name: 'Short', priceMinor: 250_000, durationMinutes: 60 },
        { name: 'Long', priceMinor: 400_000, durationMinutes: 75 },
      ],
    });
  });

  it('says each broken variant rule by the variant’s name and marks its field', async () => {
    render();
    fireEvent.click(await screen.findByTestId('services-new'));
    fireEvent.change(await screen.findByTestId('service-sheet-name'), { target: { value: 'Haircut' } });
    fireEvent.click(screen.getByTestId('service-variants-toggle'));
    fireEvent.change(screen.getByTestId('service-variant-set'), { target: { value: 'Hair length' } });
    fireEvent.change(screen.getByLabelText('Variant 2 name'), { target: { value: 'option 1' } });
    fireEvent.change(screen.getByLabelText('Variant 1 price'), { target: { value: '99' } });
    fireEvent.change(screen.getByLabelText('Variant 1 time'), { target: { value: '7' } });

    expect(screen.getAllByTestId('service-sheet-problem').map((problem) => problem.textContent)).toEqual([
      'Price for “Option 1” must be Rs. 100 to Rs. 500,000.',
      'Time for “Option 1” must be 5 to 480 minutes, in steps of 5.',
      '“option 1” appears twice.',
    ]);
    expect(screen.getByLabelText('Variant 1 price')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Variant 2 name')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('service-variant-preview-empty')).toHaveTextContent(
      'Fix the issues above to see the price list.',
    );
    expect(screen.getByTestId('service-sheet-save')).toBeDisabled();
  });

  it('adds up to ten variants, removes one, and keeps each variant’s id when it saves', async () => {
    answers['catalogue-services'] = withServices(CUT);
    answers['catalogue-service-save'] = () => Response.json({ ...CUT, version: 1 });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Haircut')).getByTestId('service-card-edit'));
    const add = await screen.findByTestId('service-variants-add');
    for (let i = 0; i < 5; i++) fireEvent.click(add);
    expect(screen.getAllByTestId('service-variant-row')).toHaveLength(10);
    expect(add).toBeDisabled();
    for (let i = 0; i < 5; i++) fireEvent.click(screen.getByRole('button', { name: 'Remove variant 6' }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove variant 2' }));
    fireEvent.click(screen.getByTestId('service-sheet-save'));

    await waitFor(() => expect(sent('catalogue-service-save')).toHaveLength(1));
    expect(
      (sent('catalogue-service-save')[0] as { variants: { items: { id: string }[] } }).variants.items.map(
        (each) => each.id,
      ),
    ).toEqual([
      CUT.terms.variants[0]!.id,
      CUT.terms.variants[2]!.id,
      CUT.terms.variants[3]!.id,
      CUT.terms.variants[4]!.id,
    ]);
  });

  it('treats a variant another change removed as a conflict, shown in the sheet', async () => {
    answers['catalogue-services'] = withServices(CUT);
    answers['catalogue-service-save'] = () =>
      Response.json(
        {
          code: 'bad_request',
          errors: [{ field: 'variants.items[1].id', detail: 'is not a variant of this service' }],
        },
        { status: 400 },
      );
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Haircut')).getByTestId('service-card-edit'));
    fireEvent.change(await screen.findByLabelText('Variant 1 price'), { target: { value: '2600' } });
    fireEvent.click(screen.getByTestId('service-sheet-save'));
    expect(await within(screen.getByTestId('service-sheet')).findByTestId('services-conflict')).toBeInTheDocument();
  });
});

describe('Show in Popular and the Popular card (F05-S02 AC-5, AC-6)', () => {
  const chosen = (name: string, id: string, category = COLOUR) => ({ ...service(id, name, category), popular: true });

  it('lists the chosen services in category then name order, and switches to Most booked at once', async () => {
    answers['catalogue-services'] = withServices(
      chosen('acrylic set', '11111111-0000-4000-8000-000000000020', NAILS),
      chosen('Ombre', '11111111-0000-4000-8000-000000000021'),
    );
    answers['catalogue-popular-mode'] = () =>
      Response.json({ mode: 'most_booked', version: 1, popular: ['11111111-0000-4000-8000-000000000021'] });
    render();
    const summary = await screen.findByTestId('popular-summary');
    expect(summary).toHaveTextContent(
      '2 chosen: Ombre, acrylic set. Change this in each service under “More options”.',
    );
    fireEvent.click(screen.getByTestId('popular-mode-most_booked'));

    await waitFor(() => expect(sent('catalogue-popular-mode')).toEqual([{ mode: 'most_booked', version: 0 }]));
    expect(await screen.findByText(/^Now:/)).toBeInTheDocument();
    expect(screen.getByTestId('popular-summary')).toHaveTextContent(
      'Now: Ombre. The 6 services booked most in the last 90 days, among those bookable online. Until you have bookings, your chosen services are shown.',
    );
  });

  it('says none are chosen yet, and gives a reader the setting without changing it', async () => {
    render('services', false);
    expect(await screen.findByTestId('popular-summary')).toHaveTextContent(
      'None chosen yet. Choose up to 6 under “More options” in a service.',
    );
    expect(screen.getByTestId('popular-mode-chosen')).toBeDisabled();
  });

  it('chooses a service with its own call and no save of the service', async () => {
    answers['catalogue-service-popular'] = () => Response.json({ ...SERVICES.items[1], popular: true, version: 1 });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-edit'));
    const popular = await screen.findByTestId('service-sheet-popular');
    expect(screen.getByTestId('service-sheet-popular-help')).toHaveTextContent('On your home screen in the app');
    fireEvent.click(popular);
    fireEvent.click(screen.getByTestId('service-sheet-save'));

    await waitFor(() =>
      expect(sent('catalogue-service-popular')).toEqual([{ id: SERVICES.items[1]!.id, popular: true, version: 0 }]),
    );
    expect(sent('catalogue-service-save')).toEqual([]);
  });

  it('is disabled while Most booked is on, and when six others are chosen', async () => {
    answers['catalogue-popular-setting'] = () => Response.json({ ...POPULAR, mode: 'most_booked' });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-edit'));
    expect(await screen.findByTestId('service-sheet-popular')).toBeDisabled();
    expect(screen.getByTestId('service-sheet-popular-help')).toHaveTextContent('Popular is set to “Most booked”');
  });

  it('says six are already chosen, in the sheet and when the API refuses a seventh', async () => {
    const six = Array.from({ length: 6 }, (_, i) => chosen(`Chosen ${i}`, `11111111-0000-4000-8000-00000000003${i}`));
    answers['catalogue-services'] = withServices(...six);
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-edit'));
    expect(await screen.findByTestId('service-sheet-popular')).toBeDisabled();
    expect(screen.getByTestId('service-sheet-popular-help')).toHaveTextContent('6 already chosen. Remove one first.');
  });

  it('says so in the sheet when another choice took the sixth place first', async () => {
    answers['catalogue-service-popular'] = () => Response.json({ code: 'limit_reached' }, { status: 409 });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-edit'));
    fireEvent.click(await screen.findByTestId('service-sheet-popular'));
    fireEvent.click(screen.getByTestId('service-sheet-save'));
    expect(await within(screen.getByTestId('service-sheet')).findByTestId('services-rule')).toHaveTextContent(
      '6 already chosen. Remove one first.',
    );
  });
});

describe('a service’s deposit (F05-S02 AC-7)', () => {
  const openMore = async () => {
    await screen.findAllByTestId('service-card');
    fireEvent.click(within(card('Balayage')).getByTestId('service-card-edit'));
    return screen.findByTestId('service-deposit');
  };

  it.each([
    [{ current: RULES }, 'Uses your booking rules: 20%, up to Rs. 2,000.'],
    [
      { current: { ...RULES, rules: { ...RULES.rules, deposit: { kind: 'none' } } } },
      'Uses your booking rules: no deposit.',
    ],
    [
      { current: { ...RULES, rules: { ...RULES.rules, deposit: { kind: 'amount', amountMinor: 100_000 } } } },
      'Uses your booking rules: Rs. 1,000.',
    ],
    [{ current: null }, 'Uses your booking rules: not set yet.'],
  ])('says the salon’s rule in words: %o', async (answer, words) => {
    answers['booking-rules-public'] = () => Response.json(answer);
    render();
    await openMore();
    expect(screen.getByTestId('service-deposit-says')).toHaveTextContent(words);
  });

  it('says No deposit, and sends a custom percentage', async () => {
    answers['catalogue-service-save'] = () => Response.json({ ...SERVICES.items[1], version: 1 });
    render();
    await openMore();
    fireEvent.click(screen.getByTestId('service-deposit-none'));
    expect(screen.getByTestId('service-deposit-says')).toHaveTextContent(
      'Customers book this service without paying a deposit.',
    );
    fireEvent.click(screen.getByTestId('service-deposit-custom'));
    fireEvent.change(screen.getByLabelText('Deposit percentage'), { target: { value: '25' } });
    fireEvent.click(screen.getByTestId('service-sheet-save'));
    await waitFor(() => expect(sent('catalogue-service-save')).toHaveLength(1));
    expect(sent('catalogue-service-save')[0]).toMatchObject({ deposit: { kind: 'percentage', percent: 25 } });
  });

  it('refuses a fixed amount above the lowest price', async () => {
    render();
    await openMore();
    fireEvent.click(screen.getByTestId('service-deposit-custom'));
    fireEvent.click(screen.getByTestId('service-deposit-type'));
    fireEvent.click(await screen.findByRole('option', { name: 'Fixed amount' }));
    fireEvent.change(await screen.findByLabelText('Deposit amount'), { target: { value: '12001' } });
    expect(screen.getAllByTestId('service-sheet-problem').map((each) => each.textContent)).toContain(
      'The deposit can’t be more than the service’s lowest price.',
    );
    expect(screen.getByLabelText('Deposit amount')).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('the salon’s currency (F05-S01 AC-3, F05-S02 AC-7; NFR-13)', () => {
  it('prices the first service of an empty catalogue, and its fixed deposit, in the salon’s currency', async () => {
    answers['catalogue-services'] = () => Response.json({ currency: 'USD', items: [] });
    render();
    fireEvent.click(await screen.findByTestId('services-new'));
    expect(await screen.findByLabelText('Price (USD)')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('service-sheet-more'));
    fireEvent.click(await screen.findByTestId('service-deposit-custom'));
    fireEvent.click(screen.getByTestId('service-deposit-type'));
    fireEvent.click(await screen.findByRole('option', { name: 'Fixed amount' }));
    const amount = await screen.findByLabelText('Deposit amount');
    expect(amount.closest('[data-slot="input-group"]')).toHaveTextContent('USD');
    expect(screen.queryByText('Rs.')).toBeNull();
  });

  it('prices every sheet in the salon’s currency, not in the first service’s', async () => {
    answers['catalogue-services'] = () => Response.json({ ...SERVICES, currency: 'USD' });
    render();
    await screen.findAllByTestId('service-card');
    fireEvent.click(screen.getByTestId('services-new'));
    expect(await screen.findByLabelText('Price (USD)')).toBeInTheDocument();
  });
});

describe('a service’s photo, and a change answered after its sheet closed (AC-3, AC-10)', () => {
  const BALAYAGE = SERVICES.items[1]!;
  const GEL = SERVICES.items[2]!;
  /** An answer the test gives later, so that the photo call is still running while the person moves on. */
  const later = () => {
    let answer: (response: Response) => void = () => {};
    const pending = new Promise<Response>((resolve) => (answer = resolve));
    return { pending, answer };
  };
  const choosePhoto = () =>
    fireEvent.change(screen.getByTestId('service-photo-file'), {
      target: { files: [new File(['jpeg'], 'balayage.jpg', { type: 'image/jpeg' })] },
    });
  // An existing service's sheet opens with More options, and its photo, shown.
  const edit = async (name: string) => {
    fireEvent.click(within(card(name)).getByTestId('service-card-edit'));
    await screen.findByTestId('service-photo-choose');
  };
  const closeSheet = async () => {
    fireEvent.click(screen.getByTestId('service-sheet-close'));
    await waitFor(() => expect(screen.queryByTestId('service-sheet')).toBeNull());
  };

  it('keeps Save and Delete disabled while a photo uploads, and Close open', async () => {
    const upload = later();
    answers['catalogue-photo'] = () => upload.pending as unknown as Response;
    render();
    await screen.findAllByTestId('service-card');
    await edit('Balayage');
    fireEvent.change(screen.getByTestId('service-sheet-name'), { target: { value: 'Balayage and tone' } });
    choosePhoto();

    expect(await screen.findByTestId('service-photo-choose')).toHaveTextContent('Uploading…');
    expect(screen.getByTestId('service-sheet-save')).toBeDisabled();
    expect(screen.getByTestId('service-sheet-delete')).toBeDisabled();
    expect(screen.getByTestId('service-sheet-close')).toBeEnabled();

    upload.answer(Response.json({ ...BALAYAGE, photoId: 'photo-1', version: 1 }));
    expect(await screen.findByText('Photo saved.')).toBeInTheDocument();
    expect(screen.getByTestId('service-sheet-save')).toBeEnabled();
  });

  it('leaves a New service sheet opened while an earlier upload ran as it is, and creates the new service', async () => {
    const upload = later();
    answers['catalogue-photo'] = () => upload.pending as unknown as Response;
    answers['catalogue-service-create'] = () => Response.json(service('new', 'Root touch-up', COLOUR), { status: 201 });
    render();
    await screen.findAllByTestId('service-card');
    await edit('Balayage');
    choosePhoto();
    await closeSheet();

    fireEvent.click(screen.getByTestId('services-new'));
    fireEvent.change(await screen.findByTestId('service-sheet-name'), { target: { value: 'Root touch-up' } });
    upload.answer(Response.json({ ...BALAYAGE, photoId: 'photo-1', version: 1 }));
    expect(await screen.findByText('Photo saved.')).toBeInTheDocument();

    const sheet = screen.getByTestId('service-sheet');
    expect(within(sheet).getByRole('heading', { name: 'New service' })).toBeInTheDocument();
    expect(screen.getByTestId('service-sheet-name')).toHaveValue('Root touch-up');
    fireEvent.click(screen.getByTestId('service-sheet-save'));
    await waitFor(() => expect(sent('catalogue-service-create')).toHaveLength(1));
    expect(sent('catalogue-service-save')).toEqual([]);
  });

  it('leaves another service’s sheet on that service when an earlier upload fails after it opened', async () => {
    const upload = later();
    answers['catalogue-photo'] = () => upload.pending as unknown as Response;
    render();
    await screen.findAllByTestId('service-card');
    await edit('Balayage');
    choosePhoto();
    await closeSheet();

    await edit('Gel polish');
    upload.answer(Response.json({ code: 'bad_request' }, { status: 400 }));
    await waitFor(() => expect(sent('catalogue-photo')).toHaveLength(1));
    // The refusal belongs to Balayage's sheet, which is closed: Gel polish's photo field says nothing.
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(screen.queryByTestId('service-photo-error')).toBeNull();
    expect(screen.getByTestId('service-sheet-name')).toHaveValue('Gel polish');
  });

  it('leaves another service’s sheet open when an earlier save finishes after it opened', async () => {
    const save = later();
    answers['catalogue-service-save'] = () => save.pending as unknown as Response;
    render();
    await screen.findAllByTestId('service-card');
    await edit('Balayage');
    fireEvent.change(screen.getByTestId('service-sheet-name'), { target: { value: 'Balayage and tone' } });
    fireEvent.click(screen.getByTestId('service-sheet-save'));
    fireEvent.click(screen.getByTestId('service-sheet-close'));
    fireEvent.click(await screen.findByTestId('services-leave-confirm'));
    await waitFor(() => expect(screen.queryByTestId('service-sheet')).toBeNull());

    await edit('Gel polish');
    save.answer(Response.json({ ...BALAYAGE, name: 'Balayage and tone', version: 1 }));
    expect((await screen.findAllByText('Saved.')).length).toBeGreaterThan(0);
    expect(screen.getByTestId('service-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('service-sheet-name')).toHaveValue('Gel polish');
  });

  it('keeps a rule an earlier save was refused for off another service’s sheet', async () => {
    const save = later();
    answers['catalogue-service-save'] = () => save.pending as unknown as Response;
    render();
    await screen.findAllByTestId('service-card');
    await edit('Balayage');
    fireEvent.change(screen.getByTestId('service-sheet-name'), { target: { value: 'Balayage and tone' } });
    fireEvent.click(screen.getByTestId('service-sheet-save'));
    fireEvent.click(screen.getByTestId('service-sheet-close'));
    fireEvent.click(await screen.findByTestId('services-leave-confirm'));
    await waitFor(() => expect(screen.queryByTestId('service-sheet')).toBeNull());

    await edit('Gel polish');
    save.answer(Response.json({ code: 'invalid_state' }, { status: 409 }));
    await waitFor(() => expect(screen.getByTestId('service-sheet-save')).not.toHaveTextContent('Saving'));
    expect(screen.queryByTestId('services-rule')).toBeNull();
    expect(screen.getByTestId('service-sheet-name')).toHaveValue('Gel polish');
  });

  it('saves the service whose sheet is open when an earlier photo removal finishes after it opened', async () => {
    const removal = later();
    answers['catalogue-services'] = () =>
      Response.json({ ...SERVICES, items: [SERVICES.items[0], { ...BALAYAGE, photoId: 'photo-1' }, GEL] });
    answers['catalogue-photo-remove'] = () => removal.pending as unknown as Response;
    answers['catalogue-service-save'] = (body) => Response.json({ ...GEL, ...(body as object), version: 1 });
    render();
    await screen.findAllByTestId('service-card');
    await edit('Balayage');
    fireEvent.click(screen.getByTestId('service-photo-remove'));
    await closeSheet();

    await edit('Gel polish');
    fireEvent.change(screen.getByTestId('service-sheet-name'), { target: { value: 'Gel polish and shape' } });
    removal.answer(Response.json({ ...BALAYAGE, photoId: null, version: 1 }));
    expect(await screen.findByText('Photo removed.')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('service-sheet-save'));
    await waitFor(() => expect(sent('catalogue-service-save')).toHaveLength(1));
    expect(sent('catalogue-service-save')).toEqual([
      expect.objectContaining({ id: GEL.id, name: 'Gel polish and shape', version: GEL.version }),
    ]);
  });
});
