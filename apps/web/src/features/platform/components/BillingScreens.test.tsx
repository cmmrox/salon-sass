import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import type { AnchorHTMLAttributes } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithMessages } from '@test/render';
import { chooseOption } from '@test/select';
import { InvoiceList, SubscriptionCard } from './BillingScreens';

const replace = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace, refresh: vi.fn() }) }));
vi.mock('next/link', () => ({ default: (props: AnchorHTMLAttributes<HTMLAnchorElement>) => <a {...props} /> }));

const SALON = {
  id: '22222222-0000-4000-8000-000000000002',
  version: 3,
  name: 'Synthetic Salon',
  slug: 'synthetic',
  hostname: 'synthetic.localhost',
  status: 'active' as const,
  country: 'LK',
  currency: 'LKR',
  timeZone: 'Asia/Colombo',
  defaultLanguage: 'en',
  contactEmail: null,
  contactPhone: null,
  address: null,
  plan: { id: 'p1', key: 'basic', name: 'Basic' },
  ownerEmail: 'owner@example.test',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  closedAt: null,
  deleteAt: null,
  deletedAt: null,
};
const SUBSCRIPTION = {
  version: 4,
  state: 'past_due',
  plan: { id: 'p1', key: 'basic', name: 'Basic' },
  monthlyAmount: 250050,
  currency: 'LKR',
  paymentMethod: 'card',
  trialEndsAt: '2026-01-15T00:00:00Z',
  currentPeriod: { start: '2026-01-15T00:00:00Z', end: '2026-02-15T00:00:00Z' },
  nextInvoiceAt: '2026-02-15T00:00:00Z',
  readOnlyAt: '2026-02-05T00:00:00Z',
};
const INVOICE = {
  id: '33333333-0000-4000-8000-000000000003',
  number: 'INV-000042',
  periodStart: '2026-01-15T00:00:00Z',
  periodEnd: '2026-02-15T00:00:00Z',
  amount: 250050,
  currency: 'LKR',
  status: 'open',
  issuedAt: '2026-01-15T00:00:00Z',
  dueAt: '2026-01-22T00:00:00Z',
  paidAt: null,
};
type Call = { url: string; body: Record<string, unknown> | null };
let calls: Call[];

function stubApi(answer: (url: string, body: Record<string, unknown> | null) => Response) {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    const body = init?.body ? JSON.parse(String(init.body)) : null;
    calls.push({ url, body });
    return answer(url, body);
  });
}

beforeEach(() => {
  calls = [];
  vi.stubGlobal('navigator', { locks: { request: (_name: string, work: () => Promise<unknown>) => work() } });
  vi.stubGlobal('BroadcastChannel', undefined);
  vi.stubGlobal('crypto', {
    randomUUID: (() => {
      let n = 0;
      return () => `00000000-0000-4000-8000-${String(++n).padStart(12, '0')}`;
    })(),
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  replace.mockReset();
});

describe('the subscription card', () => {
  it('shows the state as a word, the amount in the salon currency and the billing dates', async () => {
    stubApi(() => Response.json(SUBSCRIPTION));
    renderWithMessages(<SubscriptionCard salon={SALON} onSalonChanged={() => undefined} />);
    expect(await screen.findByTestId('console-subscription-state')).toHaveTextContent('Past due');
    expect(screen.getByTestId('console-subscription-state')).toHaveAttribute('data-slot', 'badge');
    expect(screen.getByTestId('console-subscription-amount')).toHaveTextContent('Rs. 2,500.50');
    expect(screen.getByTestId('console-subscription-method')).toHaveTextContent('Card');
    expect(screen.getByTestId('console-subscription-read-only-at')).toBeInTheDocument();
    expect(screen.queryByTestId('console-no-billing-contact')).not.toBeInTheDocument();
    expect(screen.getByTestId('console-view-invoices')).toHaveAttribute('href', `/console/salons/${SALON.id}/invoices`);
  });

  it('starts a subscription for an unbilled salon without a currency, asking for one, with one key per pending request', async () => {
    let started = false;
    stubApi((url) => {
      if (url.startsWith('/operator-web/subscription?'))
        return started
          ? Response.json({ ...SUBSCRIPTION, state: 'trialing' })
          : Response.json({ code: 'not_found' }, { status: 404 });
      if (url === '/operator-web/principal')
        return Response.json({ id: 'o', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true });
      if (url === '/operator-web/subscription-start') {
        started = calls.filter((call) => call.url === url).length > 1;
        return started
          ? Response.json(SUBSCRIPTION, { status: 201 })
          : Response.json({ code: 'service_unavailable' }, { status: 503 });
      }
      return Response.json({});
    });
    renderWithMessages(
      <SubscriptionCard salon={{ ...SALON, currency: null, ownerEmail: null }} onSalonChanged={() => undefined} />,
    );
    expect(await screen.findByTestId('console-subscription-none')).toBeInTheDocument();
    expect(screen.getByTestId('console-no-billing-contact')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('console-start-subscription'));
    const sheet = await screen.findByTestId('console-start-subscription-sheet');
    expect(within(sheet).getByTestId('console-subscription-currency')).toHaveValue('LKR');
    fireEvent.change(within(sheet).getByTestId('console-monthly-amount'), { target: { value: '2,500.5' } });
    fireEvent.submit(within(sheet).getByTestId('console-start-subscription-submit').closest('form')!);
    expect(await within(sheet).findByTestId('console-uncertain')).toBeInTheDocument();
    fireEvent.submit(within(sheet).getByTestId('console-start-subscription-submit').closest('form')!);
    expect(await screen.findByTestId('console-subscription-notice')).toHaveTextContent('Subscription started');
    const starts = calls.filter((call) => call.url === '/operator-web/subscription-start');
    expect(starts[0]!.body).toMatchObject({
      id: SALON.id,
      monthlyAmount: 250050,
      paymentMethod: 'bank_transfer',
      currency: 'LKR',
    });
    expect(starts[1]!.body!.idempotencyKey).toBe(starts[0]!.body!.idempotencyKey);
  });

  it('explains a refused plan move and keeps the sheet open', async () => {
    stubApi((url) => {
      if (url.startsWith('/operator-web/subscription?')) return Response.json(SUBSCRIPTION);
      if (url.startsWith('/operator-web/plans'))
        return Response.json({
          items: [
            {
              id: 'p2',
              key: 'tiny',
              name: 'Tiny',
              staffSeats: 1,
              branches: 1,
              messageAllowance: 0,
              customDomain: false,
              dedicatedApp: false,
              createdAt: '',
              updatedAt: '',
            },
          ],
          page: 0,
          size: 100,
          total: 1,
        });
      if (url === '/operator-web/plan-move')
        return Response.json({ code: 'entitlement_exceeded', errors: [{ field: 'staffSeats' }] }, { status: 409 });
      return Response.json({ id: 'o', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true });
    });
    renderWithMessages(<SubscriptionCard salon={SALON} onSalonChanged={() => undefined} />);
    fireEvent.click(await screen.findByTestId('console-move-plan'));
    const sheet = await screen.findByTestId('console-move-plan-sheet');
    await waitFor(() => expect(within(sheet).getByTestId('console-move-plan-id')).toBeEnabled());
    await chooseOption(within(sheet).getByTestId('console-move-plan-id'), 'Tiny (tiny), 1 staff seats');
    fireEvent.submit(within(sheet).getByTestId('console-move-plan-submit').closest('form')!);
    expect(await within(sheet).findByTestId('console-move-plan-exceeded')).toBeInTheDocument();
    expect(calls.find((call) => call.url === '/operator-web/plan-move')!.body).toMatchObject({
      id: SALON.id,
      version: 3,
      planId: 'p2',
    });
  });
});

describe('the plan picker in Move plan (BUG-F03-006)', () => {
  it('finds a plan older than the newest 100 by search and moves to it', async () => {
    const plans = Array.from({ length: 150 }, (_, n) => ({
      id: `p-${n}`,
      key: `plan-${n}`,
      name: `Plan ${n}`,
      staffSeats: 5,
      branches: 1,
      messageAllowance: 0,
      customDomain: false,
      dedicatedApp: false,
      createdAt: '',
      updatedAt: '',
    }));
    stubApi((url) => {
      if (url.startsWith('/operator-web/subscription?')) return Response.json(SUBSCRIPTION);
      if (url.startsWith('/operator-web/plans')) {
        const params = new URL(url, 'http://console.localhost').searchParams;
        const q = params.get('q');
        const matching = q ? plans.filter((plan) => plan.key.includes(q)) : plans;
        return Response.json({ items: matching.slice(0, 100), page: 0, size: 100, total: matching.length });
      }
      if (url === '/operator-web/plan-move') return new Response(null, { status: 204 });
      return Response.json({ id: 'o', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true });
    });
    renderWithMessages(<SubscriptionCard salon={SALON} onSalonChanged={() => undefined} />);
    fireEvent.click(await screen.findByTestId('console-move-plan'));
    const sheet = await screen.findByTestId('console-move-plan-sheet');
    await waitFor(() => expect(within(sheet).getByTestId('console-move-plan-id')).toBeEnabled());
    fireEvent.change(within(sheet).getByTestId('console-move-plan-id-search'), { target: { value: 'plan-142' } });
    fireEvent.click(within(sheet).getByTestId('console-move-plan-id-search-submit'));
    await waitFor(() => expect(calls.at(-1)!.url).toBe('/operator-web/plans?page=0&size=100&q=plan-142'));
    await chooseOption(within(sheet).getByTestId('console-move-plan-id'), 'Plan 142 (plan-142), 5 staff seats');
    fireEvent.submit(within(sheet).getByTestId('console-move-plan-submit').closest('form')!);
    await waitFor(() =>
      expect(calls.find((call) => call.url === '/operator-web/plan-move')?.body).toMatchObject({
        id: SALON.id,
        version: 3,
        planId: 'p-142',
      }),
    );
  });
});

describe('the invoice list', () => {
  it('lists invoices with status words and records an exact transfer in minor units', async () => {
    let paid = false;
    stubApi((url) => {
      if (url.startsWith('/operator-web/salon?')) return Response.json(SALON);
      if (url.startsWith('/operator-web/invoices?'))
        return Response.json({
          items: [paid ? { ...INVOICE, status: 'paid', paidAt: '2026-01-20T00:00:00Z' } : INVOICE],
          page: 0,
          size: 20,
          total: 1,
        });
      if (url === '/operator-web/payment-record') {
        paid = true;
        return Response.json(
          {
            id: 'pay',
            method: 'bank_transfer',
            reference: 'BANK-9',
            providerEventId: null,
            amount: 250050,
            receivedOn: '2026-01-20',
            paidAt: '2026-01-20T00:00:00Z',
          },
          { status: 201 },
        );
      }
      return Response.json({ id: 'o', kind: 'operator', role: 'OPERATOR_ADMIN', permissions: [], mfaEnabled: true });
    });
    renderWithMessages(<InvoiceList id={SALON.id} />);
    const row = await screen.findByTestId('console-invoice-row');
    expect(row).toHaveAttribute('data-slot', 'item');
    expect(within(row).getByTestId('console-invoice-number')).toHaveTextContent('INV-000042');
    expect(within(row).getByTestId('console-invoice-status')).toHaveTextContent('Open');
    expect(screen.getByTestId('console-invoices-pagination')).toHaveAttribute('data-slot', 'pagination');
    fireEvent.click(within(row).getByTestId('console-record-transfer'));
    const sheet = await screen.findByTestId('console-record-transfer-sheet');
    expect(within(sheet).getByTestId('console-transfer-amount')).toHaveValue('2500.50');
    fireEvent.change(within(sheet).getByTestId('console-transfer-reference'), { target: { value: 'BANK-9' } });
    fireEvent.change(within(sheet).getByTestId('console-transfer-received-on'), { target: { value: '2026-01-20' } });
    fireEvent.submit(within(sheet).getByTestId('console-record-transfer-submit').closest('form')!);
    expect(await screen.findByTestId('console-transfer-recorded')).toHaveTextContent('INV-000042');
    await waitFor(() => expect(screen.getByTestId('console-invoice-status')).toHaveTextContent('Paid'));
    const record = calls.find((call) => call.url === '/operator-web/payment-record')!;
    expect(record.body).toMatchObject({
      id: INVOICE.id,
      reference: 'BANK-9',
      amount: 250050,
      receivedOn: '2026-01-20',
    });
    expect(record.body!.idempotencyKey).toMatch(/^00000000-/);
  });

  it('shows the empty state when no invoice has been issued', async () => {
    stubApi((url) =>
      url.startsWith('/operator-web/salon?')
        ? Response.json(SALON)
        : Response.json({ items: [], page: 0, size: 20, total: 0 }),
    );
    renderWithMessages(<InvoiceList id={SALON.id} />);
    expect(await screen.findByTestId('console-invoices-empty')).toHaveAttribute('data-slot', 'empty');
  });
});
