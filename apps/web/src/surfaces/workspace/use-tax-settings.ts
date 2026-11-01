'use client';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import {
  EXAMPLE_PRODUCT,
  EXAMPLE_SERVICE,
  newVatRate,
  previewOf,
  sameTax,
  taxFormOf,
  taxProblemOfField,
  taxProblemsOf,
  taxRequestOf,
  type CategoryList,
  type ProductListAnswer,
  type PublicTax,
  type ServiceList,
  type TaxForm,
  type TaxHistory,
  type TaxHistoryState,
  type TaxProblem,
  type TaxSettings,
} from '@/features/catalogue';
import {
  ensurePrincipal,
  IdentityFailure,
  identityRequest,
  leaveEndedSession,
  principalInsideLock,
  supportsLocks,
  withIdentityLock,
  type Kind,
} from '@/features/identity';
import type { SettingsNotice } from './use-settings-draft';

/** What the tab says above the form: the shared settings notices, or that a save changed nothing. */
export type TaxNotice = SettingsNotice | 'unchanged';

/** What the preview prices: the salon's first service bookable online and first product sold at checkout. */
type Examples = {
  service: { name: string | null; priceMinor: number };
  product: { name: string | null; priceMinor: number; chargesVat: boolean };
  currency: string;
};

/**
 * The first service bookable online in the Services tab's order (category order, then name) and the first product
 * sold at checkout by name, or the prototype's examples when there are none (F05-S03 AC-5, AC-15).
 */
function examplesOf(categories: CategoryList | null, services: ServiceList | null, products: ProductListAnswer | null) {
  const position = new Map((categories?.items ?? []).map((category) => [category.id, category.position]));
  const byName = (a: string, b: string) => a.localeCompare(b, undefined, { sensitivity: 'base' });
  const service = [...(services?.items ?? [])]
    .filter((each) => each.bookableOnline && position.has(each.categoryId))
    .sort((a, b) => (position.get(a.categoryId) ?? 0) - (position.get(b.categoryId) ?? 0) || byName(a.name, b.name))[0];
  const product = [...(products?.items ?? [])]
    .filter((each) => each.soldAtCheckout)
    .sort((a, b) => byName(a.name, b.name))[0];
  return {
    service: service ? { name: service.name, priceMinor: service.terms.priceMinor } : EXAMPLE_SERVICE,
    product: product
      ? { name: product.name, priceMinor: product.priceMinor, chargesVat: product.chargesVat }
      : EXAMPLE_PRODUCT,
    // A real row's own currency, or the salon's for an example row (AC-15).
    currency: service?.terms.currency ?? product?.currency ?? services?.currency ?? products?.currency ?? 'LKR',
  } satisfies Examples;
}

/**
 * The Tax & charges tab's state (F05-S03): the settings as settings staff keep them, the salon's current tax (for
 * the rate the current version copied), the form with its problems, the preview, the save (a new version at once,
 * made from the version read, under the session's cross-tab lock), and the history, read when its sheet opens.
 */
export function useTaxSettings(kind: Kind, notify: () => void) {
  const router = useRouter();
  const [settings, setSettings] = useState<TaxSettings | null>(null);
  const [current, setCurrent] = useState<PublicTax | null>(null);
  const [form, setForm] = useState<TaxForm | null>(null);
  const [examples, setExamples] = useState<Examples>({
    service: EXAMPLE_SERVICE,
    product: EXAMPLE_PRODUCT,
    currency: 'LKR',
  });
  const [viewer, setViewer] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<TaxNotice>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [apiProblems, setApiProblems] = useState<TaxProblem[]>([]);
  const [saving, setSaving] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [history, setHistory] = useState<TaxHistoryState>({ state: 'loading' });

  function failed(error: unknown): IdentityFailure | null {
    const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
    if (failure.code === 'invalid_session') {
      leaveEndedSession(kind, router);
      return null;
    }
    if (failure.code === 'conflict') setNotice('conflict');
    else if (failure.code === 'invalid_state') setNotice('unchanged');
    else if (failure.code === 'service_unavailable' || failure.code === 'browser_unsupported') setNotice('unavailable');
    else if (failure.code === 'bad_request') return failure;
    else {
      setRefusal(failure.code);
      setNotice('refused');
    }
    return null;
  }

  /** Reads what the preview prices; the tab works without it, with the prototype's examples. */
  async function readExamples() {
    const optional = <T>(action: string) => identityRequest<T>(kind, action).catch(() => null);
    const [categories, services, products] = await Promise.all([
      optional<CategoryList>('catalogue-categories'),
      optional<ServiceList>('catalogue-services'),
      optional<ProductListAnswer>('catalogue-products'),
    ]);
    setExamples(examplesOf(categories, services, products));
  }

  async function load() {
    setLoading(true);
    try {
      const principal = await ensurePrincipal(kind);
      setViewer(principal.staffId ?? null);
      const [next, published] = await Promise.all([
        identityRequest<TaxSettings>(kind, 'catalogue-tax-settings'),
        identityRequest<{ current: PublicTax | null }>(kind, 'catalogue-tax-public'),
        readExamples(),
      ]);
      setSettings(next);
      setCurrent(published.current);
      setForm(taxFormOf(next.settings));
      setApiProblems([]);
      setNotice(null);
    } catch (error) {
      failed(error);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    let active = true;
    Promise.resolve().then(() => {
      if (active) void load();
    });
    return () => {
      active = false;
    };
    // Loaded once when the tab opens; reload is the person's choice after a conflict.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function readHistory(page: number) {
    setHistory({ state: 'loading' });
    try {
      setHistory({
        state: 'ready',
        history: await identityRequest<TaxHistory>(kind, `catalogue-tax-history?page=${page}`),
      });
    } catch (error) {
      const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
      if (failure.code === 'invalid_session') leaveEndedSession(kind, router);
      else setHistory({ state: 'unavailable' });
    }
  }

  const max = settings?.configured.serviceChargeMaxPercent ?? 0;
  const problems = form ? [...new Set([...taxProblemsOf(form, max), ...apiProblems])] : [];
  const rate = settings ? newVatRate(settings, current) : null;
  // Unsaved edits are what differs from the current version (or from both off before the first save).
  const dirty = Boolean(form && settings && !sameTax(form, settings.settings ?? taxValuesOff()));

  async function save() {
    if (!settings || !form || taxProblemsOf(form, max).length > 0) return;
    if (!supportsLocks()) {
      setNotice('unavailable');
      return;
    }
    setSaving(true);
    try {
      const saved = await withIdentityLock(kind, async () => {
        await principalInsideLock(kind);
        return identityRequest<TaxSettings>(kind, 'catalogue-tax-save', taxRequestOf(form, settings.version));
      });
      setSettings(saved);
      setForm(taxFormOf(saved.settings));
      const published = await identityRequest<{ current: PublicTax | null }>(kind, 'catalogue-tax-public').catch(
        () => null,
      );
      if (published) setCurrent(published.current);
      setApiProblems([]);
      setNotice(null);
      notify();
    } catch (error) {
      const refused = failed(error);
      if (refused) {
        const fields = refused.fields
          .map(taxProblemOfField)
          .filter((problem): problem is TaxProblem => problem !== null);
        setApiProblems(fields);
        setNotice('fix');
      }
    }
    setSaving(false);
  }

  return {
    settings,
    form,
    problems,
    viewer,
    loading,
    notice,
    refusal,
    saving,
    dirty,
    /** The VAT rate configuration gives now, when the current version copied another: a save applies it. */
    newRate: rate,
    preview:
      form && settings ? previewOf(form, settings.configured.vatRatePercent, examples.service, examples.product) : [],
    currency: examples.currency,
    /** The first save may keep both off; afterwards a change, or the new VAT rate, is needed. */
    canSave:
      Boolean(form && settings) &&
      (form ? taxProblemsOf(form, max).length === 0 : false) &&
      (settings?.settings === null || dirty || rate !== null),
    historyOpen,
    history,
    reload: load,
    save,
    change: <K extends keyof TaxForm>(field: K, value: TaxForm[K]) => {
      setApiProblems([]);
      setForm((now) => (now ? { ...now, [field]: value } : now));
    },
    setHistoryOpen: (open: boolean) => {
      setHistoryOpen(open);
      if (open) void readHistory(0);
    },
    historyPage: (page: number) => void readHistory(page),
  };
}

/** The settings before the first save: both parts off. */
function taxValuesOff() {
  return {
    vat: { registered: false, registrationNumber: null },
    serviceCharge: { on: false, percent: null, onProducts: false },
  };
}
