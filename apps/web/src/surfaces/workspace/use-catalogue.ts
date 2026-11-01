'use client';
import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import {
  categoryProblem,
  categoryProblemOfField,
  newService,
  problemsOf,
  problemsOfFields,
  requestOf,
  sameValues,
  shownProblems,
  valuesOf,
  variantIdRefused,
  type CatalogueService,
  type Category,
  type CategoryList,
  type CategoryProblem,
  type PhotoUpload,
  type PopularMode,
  type PopularSetting,
  type PublicTax,
  type ServiceList,
  type ServiceProblem,
  type ServiceValues,
} from '@/features/catalogue';
import type { PublicBookingRules } from '@/features/booking';
import {
  IdentityFailure,
  identityRequest,
  leaveEndedSession,
  principalInsideLock,
  supportsLocks,
  withIdentityLock,
  type Kind,
} from '@/features/identity';

/** What the page says above its lists: someone else changed them, the API could not be reached, or a change was refused. */
export type CatalogueNotice = 'conflict' | 'unavailable' | 'refused' | null;

/** Why a change was not made, said in an Alert: a rule of the catalogue rather than a broken field. */
export type CatalogueRefusal =
  | { reason: 'inUseService' | 'lastCategory' | 'categoryLimit' | 'serviceLimit' | 'unchanged' | 'popularLimit' }
  | { reason: 'inUseCategory'; count: number }
  | null;

/** The passing notices the page announces in a toast. */
export type CatalogueToast =
  | 'created'
  | 'savedTerms'
  | 'saved'
  | 'duplicated'
  | 'hidden'
  | 'shown'
  | 'deleted'
  | 'photoSaved'
  | 'photoRemoved'
  | 'categoryAdded'
  | 'categoryRenamed'
  | 'categoryDeleted'
  | 'ordered';

type ServiceSheetState = { service: CatalogueService | null; values: ServiceValues; apiProblems: ServiceProblem[] };
type CategorySheetState = { renaming: Category | null; name: string; problem: CategoryProblem | null };
type Confirm = { service: CatalogueService } | { category: Category } | null;

/**
 * The Services & pricing page's state (F05-S01): the categories and services, read together; the service and
 * category sheets with their checks; the confirmations; and every change, each under the session's cross-tab lock.
 * A 403 on a change makes the page read-only from then on and says so.
 */
export function useCatalogue(kind: Kind, notify: (toast: CatalogueToast) => void) {
  const router = useRouter();
  const [categories, setCategories] = useState<CategoryList | null>(null);
  const [services, setServices] = useState<ServiceList | null>(null);
  const [popular, setPopular] = useState<PopularSetting | null>(null);
  // The salon's booking rules as customers see them: null before the first save, undefined while unknown.
  const [rules, setRules] = useState<PublicBookingRules | null | undefined>(undefined);
  // The salon's current tax, for the Services tab's banner (F05-S03): null before the first save, undefined while
  // unknown.
  const [tax, setTax] = useState<PublicTax | null | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<CatalogueNotice>(null);
  const [refusal, setRefusal] = useState<CatalogueRefusal>(null);
  const [locked, setLocked] = useState(false);
  const [busy, setBusy] = useState(false);
  // A sheet keeps its last content while it closes, so that focus can go back to what opened it.
  const [sheet, setSheet] = useState<ServiceSheetState | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [opened, setOpened] = useState(0);
  // The same count, read when a change made from a sheet answers: its result belongs to the opening that sent it, and
  // a sheet closed and another opened meanwhile (or the same service opened again) is left as it is.
  const opening = useRef(0);
  const [photoUpload, setPhotoUpload] = useState<PhotoUpload>({ state: 'idle' });
  const [categorySheet, setCategorySheet] = useState<CategorySheetState | null>(null);
  const [categoryOpen, setCategoryOpen] = useState(false);
  const [confirm, setConfirm] = useState<Confirm>(null);

  /** A failure the page answers itself; a field error is returned for the sheet that sent it. */
  function failed(error: unknown): IdentityFailure | null {
    const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
    if (failure.code === 'invalid_session') {
      leaveEndedSession(kind, router);
      return null;
    }
    if (failure.code === 'bad_request') return failure;
    if (failure.code === 'conflict' || failure.code === 'not_found') setNotice('conflict');
    else if (failure.code === 'service_unavailable' || failure.code === 'browser_unsupported') setNotice('unavailable');
    else if (failure.code === 'limit_reached' || failure.code === 'in_use' || failure.code === 'invalid_state')
      return failure;
    else {
      setNotice('refused');
      setLocked(true);
      setSheetOpen(false);
      setCategoryOpen(false);
    }
    return null;
  }

  /** Reads both lists; null when they could not be read (the page says why). */
  async function load(): Promise<{ categories: CategoryList; services: ServiceList } | null> {
    setLoading(true);
    try {
      const [nextCategories, nextServices, nextPopular, nextRules, nextTax] = await Promise.all([
        identityRequest<CategoryList>(kind, 'catalogue-categories'),
        identityRequest<ServiceList>(kind, 'catalogue-services'),
        identityRequest<PopularSetting>(kind, 'catalogue-popular-setting'),
        // Only the words of a "Salon rule" deposit depend on it: the page works without it.
        identityRequest<{ current: PublicBookingRules | null }>(kind, 'booking-rules-public').then(
          (answer) => answer.current,
          () => undefined,
        ),
        // Only the tax banner depends on it: the page works without it.
        identityRequest<{ current: PublicTax | null }>(kind, 'catalogue-tax-public').then(
          (answer) => answer.current,
          () => undefined,
        ),
      ]);
      setCategories(nextCategories);
      setServices(nextServices);
      setPopular(nextPopular);
      setRules(nextRules);
      setTax(nextTax);
      setNotice((current) => (current === 'refused' ? current : null));
      return { categories: nextCategories, services: nextServices };
    } catch (error) {
      failed(error);
      return null;
    } finally {
      setLoading(false);
    }
  }

  /**
   * Reads the lists again after a conflict, and an open sheet with them: its service or category as it is now,
   * edits dropped, so that the next save is made from the version just read. Nothing is saved. A service or
   * category that is gone closes its sheet, and the page says why.
   */
  async function reload() {
    setRefusal(null);
    const fresh = await load();
    if (!fresh) return;
    if (sheetOpen && sheet?.service) {
      const now = fresh.services.items.find((each) => each.id === sheet.service?.id);
      if (now) setSheet({ service: now, values: valuesOf(now), apiProblems: [] });
      else setSheetOpen(false);
    }
    if (categoryOpen && categorySheet?.renaming) {
      const now = fresh.categories.items.find((each) => each.id === categorySheet.renaming?.id);
      if (now) setCategorySheet({ renaming: now, name: now.name, problem: null });
      else setCategoryOpen(false);
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
    // Loaded once when the page opens; reload is the person's choice after a conflict.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** A change made under the session's lock, the lists read again after it. */
  async function change<T>(action: string, body: unknown): Promise<T> {
    if (!supportsLocks()) throw new IdentityFailure('browser_unsupported');
    return withIdentityLock(kind, async () => {
      await principalInsideLock(kind);
      return identityRequest<T>(kind, action, body);
    });
  }

  async function quietly(work: () => Promise<void>, rules: (failure: IdentityFailure) => CatalogueRefusal) {
    setBusy(true);
    setRefusal(null);
    try {
      await work();
    } catch (error) {
      const left = failed(error);
      if (left) setRefusal(rules(left));
    }
    setBusy(false);
  }

  const otherNames = (id: string | null) =>
    (services?.items ?? []).filter((service) => service.id !== id).map((service) => service.name);

  // --- the service sheet ---------------------------------------------------------------------------------------

  const sheetProblems = sheet ? problemsOf(sheet.values, otherNames(sheet.service?.id ?? null)) : [];
  const shown = sheet
    ? [...new Set([...shownProblems(sheet.values, sheetProblems, sheet.service === null), ...sheet.apiProblems])]
    : [];
  const sheetDirty = Boolean(
    sheetOpen &&
    sheet &&
    !sameValues(sheet.values, sheet.service ? valuesOf(sheet.service) : newService(categories?.items[0]?.id ?? '')),
  );

  /**
   * Saves the sheet: the service when anything of it changed, then its Popular choice when that changed, which is
   * its own call (it is not one of the service's terms, F05-S02 AC-5). A choice refused after the service saved keeps
   * the sheet open on the saved service and says why.
   */
  async function saveService() {
    if (!sheet || sheetProblems.length > 0) return;
    const editing = sheet.service;
    const values = sheet.values;
    const started = opening.current;
    const stillOpen = () => opening.current === started;
    const popularChanged = values.popular !== (editing?.popular ?? false);
    const serviceChanged =
      !editing || !sameValues({ ...values, popular: false }, { ...valuesOf(editing), popular: false });
    setBusy(true);
    setRefusal(null);
    let saved: CatalogueService | null = editing;
    try {
      if (serviceChanged || !popularChanged)
        saved = editing
          ? await change<CatalogueService>('catalogue-service-save', {
              id: editing.id,
              ...requestOf(values),
              version: editing.version,
            })
          : await change<CatalogueService>('catalogue-service-create', requestOf(values));
      if (popularChanged && saved)
        saved = await change<CatalogueService>('catalogue-service-popular', {
          id: saved.id,
          popular: values.popular,
          version: saved.version,
        });
      if (stillOpen()) setSheetOpen(false);
      notify(
        !editing ? 'created' : saved && saved.terms.versionId !== editing.terms.versionId ? 'savedTerms' : 'saved',
      );
      await load();
    } catch (error) {
      if (saved && saved !== editing) {
        // The service saved and its Popular choice did not: the sheet stays on the saved service.
        const kept = saved;
        if (stillOpen()) setSheet((current) => (current ? { ...current, service: kept } : current));
        void load();
      }
      const left = failed(error);
      if (left?.code === 'bad_request' && variantIdRefused(left.fields)) setNotice('conflict');
      else if (!stillOpen()) {
        // The person left this sheet and opened another: what was wrong with this save is not about that one.
      } else if (left?.code === 'bad_request')
        setSheet((current) =>
          current ? { ...current, apiProblems: problemsOfFields(left.fields, current.values) } : current,
        );
      else if (left?.code === 'invalid_state') setRefusal({ reason: 'unchanged' });
      else if (left?.code === 'limit_reached')
        setRefusal({ reason: saved && popularChanged ? 'popularLimit' : 'serviceLimit' });
    }
    setBusy(false);
  }

  async function switchPopular(mode: PopularMode) {
    if (!popular) return;
    await quietly(
      async () => {
        setPopular(await change<PopularSetting>('catalogue-popular-mode', { mode, version: popular.version }));
        notify('saved');
      },
      () => ({ reason: 'unchanged' }),
    );
  }

  /**
   * A photo change's result for the sheet that sent it: applied only while that opening lasts and its service is
   * the one changed. The change itself is saved either way, and the toast and the lists say so.
   */
  function photoChanged(started: number, saved: CatalogueService) {
    if (opening.current !== started) return;
    setSheet((current) => (current?.service?.id === saved.id ? { ...current, service: saved } : current));
  }

  async function uploadPhoto(file: File) {
    const service = sheet?.service;
    if (!service) return;
    const started = opening.current;
    const stillOpen = () => opening.current === started;
    setPhotoUpload({ state: 'uploading' });
    const form = new FormData();
    form.append('serviceId', service.id);
    form.append('file', file);
    try {
      const saved = await change<CatalogueService>('catalogue-photo', form);
      photoChanged(started, saved);
      if (stillOpen()) setPhotoUpload({ state: 'idle' });
      notify('photoSaved');
      await load();
    } catch (error) {
      const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
      if (['bad_request', 'request_rejected', 'service_unavailable'].includes(failure.code)) {
        if (stillOpen()) setPhotoUpload({ state: 'refused', reason: failure.code });
      } else {
        if (stillOpen()) setPhotoUpload({ state: 'idle' });
        failed(error);
      }
    }
  }

  async function removePhoto() {
    const service = sheet?.service;
    if (!service) return;
    const started = opening.current;
    await quietly(
      async () => {
        const saved = await change<CatalogueService>('catalogue-photo-remove', { id: service.id });
        photoChanged(started, saved);
        notify('photoRemoved');
        await load();
      },
      () => null,
    );
  }

  function openSheet(next: ServiceSheetState) {
    setRefusal(null);
    setPhotoUpload({ state: 'idle' });
    setSheet(next);
    opening.current += 1;
    setOpened(opening.current);
    setSheetOpen(true);
  }

  function openCategorySheet(next: CategorySheetState) {
    setRefusal(null);
    setCategorySheet(next);
    setCategoryOpen(true);
  }

  // --- the category sheet --------------------------------------------------------------------------------------

  async function saveCategory() {
    if (!categorySheet || !categories) return;
    const problem = categoryProblem(categorySheet.name, categories.items, categorySheet.renaming?.id ?? null);
    if (problem) {
      setCategorySheet({ ...categorySheet, problem });
      return;
    }
    const renaming = categorySheet.renaming;
    setBusy(true);
    setRefusal(null);
    try {
      if (renaming)
        await change('catalogue-category-rename', {
          id: renaming.id,
          name: categorySheet.name.trim(),
          version: renaming.version,
        });
      else await change('catalogue-category-add', { name: categorySheet.name.trim() });
      setCategoryOpen(false);
      notify(renaming ? 'categoryRenamed' : 'categoryAdded');
      await load();
    } catch (error) {
      const left = failed(error);
      if (left?.code === 'bad_request')
        setCategorySheet((current) =>
          current ? { ...current, problem: categoryProblemOfField(current.name) } : current,
        );
      else if (left?.code === 'invalid_state') setRefusal({ reason: 'unchanged' });
      else if (left?.code === 'limit_reached') setRefusal({ reason: 'categoryLimit' });
    }
    setBusy(false);
  }

  const chosenElsewhere = (services?.items ?? []).filter(
    (service) => service.popular && service.id !== sheet?.service?.id,
  ).length;

  return {
    categories,
    services,
    popular,
    rules,
    switchPopular,
    chosenElsewhere,
    loading,
    notice,
    refusal,
    locked,
    busy,
    tax,
    reload,
    // The service sheet: its content, whether it is open, and which opening this is (each starts afresh).
    sheet,
    sheetOpen,
    opened,
    sheetProblems: shown,
    sheetCanSave: Boolean(sheet) && sheetProblems.length === 0,
    sheetDirty,
    photoUpload,
    newService: () => openSheet({ service: null, values: newService(categories?.items[0]?.id ?? ''), apiProblems: [] }),
    editService: (service: CatalogueService) => openSheet({ service, values: valuesOf(service), apiProblems: [] }),
    changeService: <K extends keyof ServiceValues>(field: K, value: ServiceValues[K]) =>
      setSheet((current) =>
        current ? { ...current, values: { ...current.values, [field]: value }, apiProblems: [] } : current,
      ),
    changeServiceValues: (change: (values: ServiceValues) => ServiceValues) =>
      setSheet((current) => (current ? { ...current, values: change(current.values), apiProblems: [] } : current)),
    closeSheet: () => setSheetOpen(false),
    saveService,
    uploadPhoto,
    removePhoto,
    // Card actions.
    toggleOnline: (service: CatalogueService) =>
      quietly(
        async () => {
          await change('catalogue-service-online', {
            id: service.id,
            bookableOnline: !service.bookableOnline,
            version: service.version,
          });
          notify(service.bookableOnline ? 'hidden' : 'shown');
          await load();
        },
        () => ({ reason: 'unchanged' }),
      ),
    duplicate: (service: CatalogueService) =>
      quietly(
        async () => {
          const copy = await change<CatalogueService>('catalogue-service-duplicate', { id: service.id });
          notify('duplicated');
          await load();
          openSheet({ service: copy, values: valuesOf(copy), apiProblems: [] });
        },
        (failure) => (failure.code === 'limit_reached' ? { reason: 'serviceLimit' } : null),
      ),
    // Deleting, after the confirmation.
    confirm,
    askDeleteService: (service: CatalogueService) => setConfirm({ service }),
    askDeleteCategory: (category: Category) => {
      setRefusal(null);
      if (category.serviceCount > 0) setRefusal({ reason: 'inUseCategory', count: category.serviceCount });
      else if ((categories?.items.length ?? 0) < 2) setRefusal({ reason: 'lastCategory' });
      else setConfirm({ category });
    },
    cancelDelete: () => setConfirm(null),
    confirmDelete: () => {
      const chosen = confirm;
      setConfirm(null);
      if (!chosen) return;
      if ('service' in chosen)
        void quietly(
          async () => {
            await change('catalogue-service-delete', { id: chosen.service.id, version: chosen.service.version });
            setSheetOpen(false);
            notify('deleted');
            await load();
          },
          (failure) => (failure.code === 'in_use' ? { reason: 'inUseService' } : null),
        );
      else
        void quietly(
          async () => {
            await change('catalogue-category-delete', { id: chosen.category.id, version: chosen.category.version });
            notify('categoryDeleted');
            await load();
          },
          (failure) =>
            failure.code === 'in_use'
              ? { reason: 'inUseCategory', count: chosen.category.serviceCount }
              : { reason: 'lastCategory' },
        );
    },
    // Categories.
    categorySheet,
    categoryOpen,
    newCategory: () => openCategorySheet({ renaming: null, name: '', problem: null }),
    renameCategory: (category: Category) =>
      openCategorySheet({ renaming: category, name: category.name, problem: null }),
    typeCategory: (name: string) => setCategorySheet((current) => (current ? { ...current, name } : current)),
    closeCategorySheet: () => setCategoryOpen(false),
    saveCategory,
    move: (category: Category, direction: 'up' | 'down') =>
      quietly(
        async () => {
          const next = await change<CategoryList>('catalogue-category-move', {
            id: category.id,
            direction,
            listVersion: categories?.listVersion,
          });
          setCategories(next);
          notify('ordered');
        },
        () => null,
      ),
  };
}
