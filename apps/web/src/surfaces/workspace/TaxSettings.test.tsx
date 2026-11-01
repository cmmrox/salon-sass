import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import {
  CATEGORIES,
  NAILS,
  PRODUCTS,
  product,
  SERVICES,
  service,
  TAX_PUBLIC,
  TAX_SAVED,
  TAX_UNSAVED,
} from './catalogue-fixtures';
import { SettingsScreen } from './SettingsScreen';
import { TaxSettingsScreen } from './TaxSettingsScreen';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn(), refresh: vi.fn() }) }));

const ME = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
type Call = { action: string; body: unknown };
let calls: Call[];
let answers: Record<string, (body: unknown) => Response>;
beforeEach(() => {
  push.mockReset();
  calls = [];
  answers = {
    principal: () => Response.json({ kind: 'staff', staffId: ME, permissions: ['settings.read', 'settings.manage'] }),
    'catalogue-tax-settings': () => Response.json(TAX_UNSAVED),
    'catalogue-tax-public': () => Response.json({ current: null }),
    'catalogue-categories': () => Response.json(CATEGORIES),
    'catalogue-services': () => Response.json(SERVICES),
    'catalogue-products': () => Response.json(PRODUCTS),
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

const render = (readOnly: 'support' | 'salon' | null = null) =>
  renderWithMessages(<TaxSettingsScreen kind="staff" timeZone="Asia/Colombo" readOnly={readOnly} />);
const saves = () => calls.filter((call) => call.action === 'catalogue-tax-save').map((call) => call.body);
const lines = () =>
  screen.getAllByTestId('tax-preview-line').map((line) => [line.getAttribute('data-kind'), line.textContent]);

describe('Settings → Tax & charges (F05-S03 AC-1)', () => {
  it('sits between Booking rules and Branding and says it saves at once', () => {
    renderWithMessages(
      <SettingsScreen tab="tax">
        <p>content</p>
      </SettingsScreen>,
    );
    expect(
      within(screen.getByTestId('settings-tabs'))
        .getAllByRole('tab')
        .map((tab) => tab.textContent),
    ).toEqual(['Profile', 'Hours & closures', 'Booking rules', 'Tax & charges', 'Branding']);
    expect(screen.getByTestId('settings-tab-tax')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('settings-intro')).toHaveTextContent('Tax and charges save at once');
  });

  it('loads, then shows both off before the first save with the prototype’s banner', async () => {
    render();
    expect(screen.getByTestId('tax-loading')).toHaveAttribute('aria-busy', 'true');
    expect(await screen.findByTestId('tax-banner-settings')).toHaveTextContent(
      'Turn these on only if your salon is registered for them. Names and rates come from the platform for your country. Changes apply to new bookings and sales only.',
    );
    expect(screen.getByTestId('tax-vat-registered')).not.toBeChecked();
    expect(screen.getByTestId('tax-charge-on')).not.toBeChecked();
    expect(screen.queryByTestId('tax-vat-number')).toBeNull();
    expect(screen.getByLabelText('My salon is registered for VAT')).toBeInTheDocument();
    // The first save may keep both off.
    expect(screen.getByTestId('tax-save')).toBeEnabled();
  });
});

describe('the form (AC-2)', () => {
  it('asks for the registration number and the rate, says what is wrong beside each, and keeps Save disabled', async () => {
    render();
    fireEvent.click(await screen.findByTestId('tax-vat-registered'));
    expect(screen.getByLabelText('VAT registration number')).toBeInTheDocument();
    expect(screen.getByTestId('tax-vat-number-error')).toHaveTextContent(
      'Enter the VAT registration number: 1 to 20 letters, digits and hyphens.',
    );
    expect(screen.getByTestId('tax-vat-number')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByTestId('tax-save')).toBeDisabled();
    fireEvent.change(screen.getByTestId('tax-vat-number'), { target: { value: 'LK 1' } });
    expect(screen.getByTestId('tax-vat-number-error')).toBeInTheDocument();
    fireEvent.change(screen.getByTestId('tax-vat-number'), { target: { value: 'LK-1' } });
    expect(screen.queryByTestId('tax-vat-number-error')).toBeNull();

    fireEvent.click(screen.getByTestId('tax-charge-on'));
    expect(screen.getByTestId('tax-charge-percent')).toHaveValue('10');
    fireEvent.change(screen.getByTestId('tax-charge-percent'), { target: { value: '21' } });
    expect(screen.getByTestId('tax-charge-percent-error')).toHaveTextContent(
      'The rate must be a whole number from 1 to 20%.',
    );
    expect(screen.getByTestId('tax-save')).toBeDisabled();
    fireEvent.change(screen.getByTestId('tax-charge-percent'), { target: { value: '12' } });
    expect(screen.getByTestId('tax-save')).toBeEnabled();
  });

  it('keeps what was typed when a part is turned off and on, and never sends a number or rate for a part that is off', async () => {
    answers['catalogue-tax-save'] = () => Response.json(TAX_UNSAVED);
    render();
    fireEvent.click(await screen.findByTestId('tax-vat-registered'));
    fireEvent.change(screen.getByTestId('tax-vat-number'), { target: { value: 'LK-77' } });
    fireEvent.click(screen.getByTestId('tax-charge-on'));
    fireEvent.change(screen.getByTestId('tax-charge-percent'), { target: { value: '15' } });
    fireEvent.click(screen.getByTestId('tax-charge-products'));
    fireEvent.click(screen.getByTestId('tax-vat-registered'));
    fireEvent.click(screen.getByTestId('tax-charge-on'));
    fireEvent.click(screen.getByTestId('tax-vat-registered'));
    expect(screen.getByTestId('tax-vat-number')).toHaveValue('LK-77');
    fireEvent.click(screen.getByTestId('tax-vat-registered'));

    fireEvent.click(screen.getByTestId('tax-save'));
    await waitFor(() => expect(saves()).toHaveLength(1));
    expect(saves()[0]).toEqual({
      version: 0,
      vat: { registered: false },
      serviceCharge: { on: false, onProducts: false },
    });
  });

  it('shows the API’s field error beside its field', async () => {
    answers['catalogue-tax-save'] = () =>
      Response.json(
        { code: 'bad_request', errors: [{ field: 'serviceCharge.percent', detail: 'must be 1 to 20' }] },
        { status: 400 },
      );
    render();
    fireEvent.click(await screen.findByTestId('tax-charge-on'));
    fireEvent.click(screen.getByTestId('tax-save'));
    expect(await screen.findByTestId('tax-charge-percent-error')).toBeInTheDocument();
    expect(screen.getByTestId('tax-fix')).toBeInTheDocument();
  });
});

describe('saving (AC-3)', () => {
  it('sends the settings from the version read and says they are saved', async () => {
    answers['catalogue-tax-save'] = () => Response.json(TAX_SAVED);
    render();
    fireEvent.click(await screen.findByTestId('tax-vat-registered'));
    fireEvent.change(screen.getByTestId('tax-vat-number'), { target: { value: ' LK-134-567 ' } });
    fireEvent.click(screen.getByTestId('tax-charge-on'));
    fireEvent.click(screen.getByTestId('tax-save'));
    expect(await screen.findByText('Tax and charges saved. New bookings and sales use them.')).toBeInTheDocument();
    expect(saves()).toEqual([
      {
        version: 0,
        vat: { registered: true, registrationNumber: 'LK-134-567' },
        serviceCharge: { on: true, percent: 10, onProducts: false },
      },
    ]);
    // Saved: nothing differs, so Save waits for a change.
    expect(screen.getByTestId('tax-save')).toBeDisabled();
  });

  it('says a stale save is a conflict with Reload, and an identical one that nothing changed', async () => {
    answers['catalogue-tax-settings'] = () => Response.json(TAX_SAVED);
    answers['catalogue-tax-save'] = () => Response.json({ code: 'conflict' }, { status: 409 });
    render();
    fireEvent.change(await screen.findByTestId('tax-charge-percent'), { target: { value: '12' } });
    fireEvent.click(screen.getByTestId('tax-save'));
    expect(await screen.findByTestId('tax-conflict')).toHaveTextContent('Someone else changed the tax and charges');
    fireEvent.click(screen.getByTestId('tax-conflict-reload'));
    await waitFor(() => expect(screen.getByTestId('tax-charge-percent')).toHaveValue('10'));

    answers['catalogue-tax-save'] = () => Response.json({ code: 'invalid_state' }, { status: 409 });
    fireEvent.change(screen.getByTestId('tax-charge-percent'), { target: { value: '12' } });
    fireEvent.click(screen.getByTestId('tax-save'));
    expect(await screen.findByTestId('tax-unchanged')).toHaveTextContent(
      'Nothing changed: these are already your tax settings.',
    );
  });

  it('says when the configured VAT rate is not the current version’s, and lets the same settings be saved for it', async () => {
    answers['catalogue-tax-settings'] = () =>
      Response.json({ ...TAX_SAVED, configured: { ...TAX_SAVED.configured, vatRatePercent: 15 } });
    answers['catalogue-tax-public'] = () => Response.json({ current: TAX_PUBLIC });
    render();
    expect(await screen.findByTestId('tax-new-rate')).toHaveTextContent(
      'The VAT rate is now 15%. Save to apply it to new bookings and sales.',
    );
    expect(screen.getByTestId('tax-save')).toBeEnabled();
  });

  it('lists the history with “You”, the author or “A team member”, and the settings each version changed', async () => {
    answers['catalogue-tax-settings'] = () => Response.json(TAX_SAVED);
    answers['catalogue-tax-history'] = () =>
      Response.json({
        items: [
          {
            versionId: 'v3',
            version: 3,
            createdAt: '2026-11-01T04:30:00Z',
            createdBy: 'other',
            createdByLabel: null,
            changedFields: ['vat.ratePercent'],
          },
          {
            versionId: 'v2',
            version: 2,
            createdAt: '2026-11-01T04:00:00Z',
            createdBy: 'manager',
            createdByLabel: 'manager@alpha.test',
            changedFields: ['serviceCharge.on', 'serviceCharge.percent', 'serviceCharge.onProducts'],
          },
          {
            versionId: 'v1',
            version: 1,
            createdAt: '2026-11-01T03:00:00Z',
            createdBy: ME,
            createdByLabel: 'me@alpha.test',
            changedFields: ['vat.registered', 'vat.registrationNumber'],
          },
        ],
        page: 0,
        size: 10,
        total: 3,
      });
    render();
    fireEvent.click(await screen.findByTestId('tax-history'));
    const rows = await screen.findAllByTestId('tax-history-row');
    expect(rows.map((row) => within(row).getByTestId('tax-history-changed').textContent)).toEqual([
      'Changed: VAT rate',
      'Changed: Service charge, Service charge rate, Service charge on products',
      'Changed: VAT registration, VAT registration number',
    ]);
    expect(within(rows[0]!).getByTestId('tax-history-by')).toHaveTextContent('A team member');
    expect(within(rows[1]!).getByTestId('tax-history-by')).toHaveTextContent('manager@alpha.test');
    expect(within(rows[2]!).getByTestId('tax-history-by')).toHaveTextContent('You');
  });
});

describe('What customers pay (AC-4, AC-5)', () => {
  it('prices the first service bookable online and the first product sold, with each part as the form changes', async () => {
    answers['catalogue-services'] = () =>
      Response.json({
        ...SERVICES,
        items: [
          ...SERVICES.items,
          service('11111111-0000-4000-8000-000000000009', 'Acrylics', NAILS, { priceMinor: 250_000 }),
        ],
      });
    render();
    await screen.findByTestId('tax-preview');
    // Colour comes before Nails; within Colour, Balayage before zebra highlights. Nothing is included yet.
    expect(lines()).toEqual([
      ['service', 'BalayageRs. 12,000'],
      ['product', 'Argan oil shampoo 250 mlRs. 3,200'],
    ]);
    fireEvent.click(screen.getByTestId('tax-vat-registered'));
    fireEvent.click(screen.getByTestId('tax-charge-on'));
    expect(lines()).toEqual([
      ['service', 'BalayageRs. 12,000'],
      ['serviceCharge', 'Includes service charge 10%Rs. 924.50'],
      ['vat', 'Includes VAT 18%Rs. 1,830.51'],
      ['product', 'Argan oil shampoo 250 mlRs. 3,200'],
      ['vat', 'Includes VAT 18%Rs. 488.14'],
    ]);
    fireEvent.click(screen.getByTestId('tax-charge-products'));
    expect(lines().at(-2)).toEqual(['serviceCharge', 'Includes service charge 10%Rs. 246.53']);
    expect(screen.getByTestId('tax-preview-note')).toHaveTextContent(
      'Prices are always shown with tax and service charge included, so the price on your menu is the price customers pay.',
    );
  });

  it('prices the prototype’s examples in the salon’s currency when there is no service or product (AC-15)', async () => {
    answers['catalogue-services'] = () => Response.json({ currency: 'USD', items: [] });
    answers['catalogue-products'] = () => Response.json({ currency: 'USD', items: [] });
    render();
    await screen.findByTestId('tax-tab');
    await waitFor(() =>
      expect(lines()).toEqual([
        ['service', 'A service$2,500'],
        ['product', 'A product$3,200'],
      ]),
    );
  });

  it('prices the example row in the salon’s currency beside a real product (AC-15)', async () => {
    answers['catalogue-services'] = () => Response.json({ currency: 'USD', items: [] });
    answers['catalogue-products'] = () =>
      Response.json({
        currency: 'USD',
        items: [product('44444444-0000-4000-8000-000000000001', 'Hair oil', { currency: 'USD', priceMinor: 1_000 })],
      });
    render();
    await screen.findByTestId('tax-tab');
    await waitFor(() =>
      expect(lines()).toEqual([
        ['service', 'A service$2,500'],
        ['product', 'Hair oil$10'],
      ]),
    );
  });

  it('uses the prototype’s examples when there is no service or product, with the cents of every part', async () => {
    answers['catalogue-services'] = () => Response.json({ currency: 'LKR', items: [] });
    answers['catalogue-products'] = () => Response.json({ currency: 'LKR', items: [] });
    render();
    fireEvent.click(await screen.findByTestId('tax-vat-registered'));
    fireEvent.click(screen.getByTestId('tax-charge-on'));
    expect(lines()).toEqual([
      ['service', 'A serviceRs. 2,500'],
      ['serviceCharge', 'Includes service charge 10%Rs. 192.60'],
      ['vat', 'Includes VAT 18%Rs. 381.36'],
      ['product', 'A productRs. 3,200'],
      ['vat', 'Includes VAT 18%Rs. 488.14'],
    ]);
  });
});

describe('states (AC-11, AC-13)', () => {
  it('lets a support view and a read-only salon read but change nothing', async () => {
    answers['catalogue-tax-settings'] = () => Response.json(TAX_SAVED);
    render('support');
    expect(await screen.findByTestId('tax-read-only')).toHaveTextContent(
      'A support session can read the tax and charges but cannot change it.',
    );
    expect(screen.getByTestId('tax-vat-registered')).toBeDisabled();
    expect(screen.getByTestId('tax-save')).toBeDisabled();
  });

  it('says why a save was refused', async () => {
    answers['catalogue-tax-save'] = () => Response.json({ code: 'permission_denied' }, { status: 403 });
    render();
    fireEvent.click(await screen.findByTestId('tax-save'));
    expect(await screen.findByTestId('tax-refused')).toHaveTextContent(
      'Your account cannot change the salon’s settings.',
    );
  });

  it('says when the settings cannot be reached, with Try again', async () => {
    answers['catalogue-tax-settings'] = () => Response.json({ code: 'service_unavailable' }, { status: 503 });
    render();
    expect(await screen.findByTestId('tax-unavailable')).toHaveTextContent('could not be reached');
    expect(screen.getByTestId('tax-retry')).toBeInTheDocument();
  });
});
