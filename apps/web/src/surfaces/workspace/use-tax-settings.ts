'use client';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
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
  useCatalogueChanges,
  useCategories,
  useProductList,
  usePublicTax,
  useServices,
  useSetCatalogueAnswer,
  useTaxHistory,
  useTaxSettings as useTaxSettingsRead,
  type CategoryList,
  type ProductListAnswer,
  type ServiceList,
  type TaxForm,
  type TaxHistoryState,
  type TaxProblem,
  type TaxSettings,
} from '@/features/catalogue';
import { IdentityFailure, leaveEndedSession, usePrincipal, type Kind } from '@/features/identity';
import { useReadFailure } from '@/lib/api-core/data-layer';
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
  const reads = {
    principal: usePrincipal(kind),
    settings: useTaxSettingsRead(kind),
    current: usePublicTax(kind),
  };
  // What the preview prices; the tab works without them, with the prototype's examples.
  const examplesFrom = { categories: useCategories(kind), services: useServices(kind), products: useProductList(kind) };
  const change = useCatalogueChanges(kind).run;
  const setAnswer = useSetCatalogueAnswer(kind);
  const ready = Boolean(reads.principal.data && reads.settings.data && reads.current.data);
  const settings = ready ? (reads.settings.data ?? null) : null;
  const current = ready ? (reads.current.data?.current ?? null) : null;
  const viewer = reads.principal.data?.staffId ?? null;
  const examples = examplesOf(
    examplesFrom.categories.data ?? null,
    examplesFrom.services.data ?? null,
    examplesFrom.products.data ?? null,
  );
  // The form starts from each version of the settings read or saved, and keeps the person's edits in between.
  const [form, setForm] = useState<TaxForm | null>(null);
  const [formOf, setFormOf] = useState<TaxSettings | null>(null);
  if (settings && settings !== formOf) {
    setFormOf(settings);
    setForm(taxFormOf(settings.settings));
  }
  const loading = Object.values(reads).some((each) => each.isFetching) || (!ready && !failedRead(reads));
  const [notice, setNotice] = useState<TaxNotice>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [apiProblems, setApiProblems] = useState<TaxProblem[]>([]);
  const [saving, setSaving] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);

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

  /** Reads the tab again: the settings, the salon's current tax and who is looking. */
  async function load() {
    const results = await Promise.all([
      reads.principal.refetch(),
      reads.settings.refetch(),
      reads.current.refetch(),
      examplesFrom.categories.refetch(),
      examplesFrom.services.refetch(),
      examplesFrom.products.refetch(),
    ]);
    if (results.slice(0, 3).some((each) => each.error)) return;
    setForm(results[1]?.data ? taxFormOf(results[1].data.settings) : null);
    setApiProblems([]);
    setNotice(null);
  }

  useReadFailure([reads.principal, reads.settings, reads.current], (failure) => failed(failure));

  // The history of changes, a page at a time, read while its sheet is open.
  const [historyPage, setHistoryPage] = useState(0);
  const historyRead = useTaxHistory(kind, historyPage, historyOpen);
  const history: TaxHistoryState =
    historyRead.isFetching || (!historyRead.data && !historyRead.error)
      ? { state: 'loading' }
      : historyRead.error || !historyRead.data
        ? { state: 'unavailable' }
        : { state: 'ready', history: historyRead.data };
  useReadFailure([historyRead], (failure) => {
    if (failure.code === 'invalid_session') leaveEndedSession(kind, router);
  });

  const max = settings?.configured.serviceChargeMaxPercent ?? 0;
  const problems = form ? [...new Set([...taxProblemsOf(form, max), ...apiProblems])] : [];
  const rate = settings ? newVatRate(settings, current) : null;
  // Unsaved edits are what differs from the current version (or from both off before the first save).
  const dirty = Boolean(form && settings && !sameTax(form, settings.settings ?? taxValuesOff()));

  async function save() {
    if (!settings || !form || taxProblemsOf(form, max).length > 0) return;
    setSaving(true);
    try {
      const saved = await change('catalogue-tax-save', taxRequestOf(form, settings.version));
      setAnswer('catalogue-tax-settings', saved);
      setForm(taxFormOf(saved.settings));
      setFormOf(saved);
      await reads.current.refetch();
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
      // Opening it reads its first page again.
      setHistoryOpen(open);
      if (open) setHistoryPage(0);
    },
    historyPage: (page: number) => setHistoryPage(page),
  };
}

/** Whether one of the tab's reads failed, so that it is not loading any more. */
function failedRead(reads: Record<string, { error: unknown }>) {
  return Object.values(reads).some((each) => each.error);
}

/** The settings before the first save: both parts off. */
function taxValuesOff() {
  return {
    vat: { registered: false, registrationNumber: null },
    serviceCharge: { on: false, percent: null, onProducts: false },
  };
}
