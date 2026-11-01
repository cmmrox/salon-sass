import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { CATEGORIES, COLOUR, NAILS, SERVICES, service, WAXING } from './catalogue-fixtures';
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
    expect(groups.map((group) => group.getAttribute('data-id'))).toEqual([COLOUR, NAILS, WAXING]);
    expect(
      within(groups[0]!)
        .getAllByTestId('service-card-name')
        .map((name) => name.textContent),
    ).toEqual(['Balayage', 'zebra highlights']);
    expect(within(groups[2]!).getByTestId('service-group-empty')).toHaveTextContent('No services yet.');
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
      },
    ]);
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
      Response.json({ items: [...SERVICES.items.slice(0, 2), { ...changed, version: 1 }] });
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
        items: CATEGORIES.items.map((each) => (each.id === NAILS ? { ...each, name: 'Nails & hands', version: 1 } : each)),
      });
    fireEvent.click(within(conflict).getByTestId('services-conflict-reload'));

    await waitFor(() => expect(screen.getByTestId('category-sheet-name')).toHaveValue('Nails & hands'));
    expect(screen.queryByTestId('services-conflict')).toBeNull();
    expect(sent('catalogue-category-rename')).toHaveLength(1);
  });
});
