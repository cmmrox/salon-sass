'use client';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import {
  categoryProblem,
  categoryProblemOfField,
  newService,
  problemsOf,
  problemsOfFields,
  requestOf,
  sameService,
  shownProblems,
  valuesOf,
  type CatalogueService,
  type Category,
  type CategoryList,
  type CategoryProblem,
  type PhotoUpload,
  type ServiceList,
  type ServiceProblem,
  type ServiceValues,
} from '@/features/catalogue';
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
  | { reason: 'inUseService' | 'lastCategory' | 'categoryLimit' | 'serviceLimit' | 'unchanged' }
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
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<CatalogueNotice>(null);
  const [refusal, setRefusal] = useState<CatalogueRefusal>(null);
  const [locked, setLocked] = useState(false);
  const [busy, setBusy] = useState(false);
  // A sheet keeps its last content while it closes, so that focus can go back to what opened it.
  const [sheet, setSheet] = useState<ServiceSheetState | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [opened, setOpened] = useState(0);
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

  async function load() {
    setLoading(true);
    try {
      const [nextCategories, nextServices] = await Promise.all([
        identityRequest<CategoryList>(kind, 'catalogue-categories'),
        identityRequest<ServiceList>(kind, 'catalogue-services'),
      ]);
      setCategories(nextCategories);
      setServices(nextServices);
      setNotice((current) => (current === 'refused' ? current : null));
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
    !sameService(sheet.values, sheet.service ? valuesOf(sheet.service) : newService(categories?.items[0]?.id ?? '')),
  );

  async function saveService() {
    if (!sheet || sheetProblems.length > 0) return;
    const editing = sheet.service;
    setBusy(true);
    setRefusal(null);
    try {
      const saved = editing
        ? await change<CatalogueService>('catalogue-service-save', {
            id: editing.id,
            ...requestOf(sheet.values),
            version: editing.version,
          })
        : await change<CatalogueService>('catalogue-service-create', requestOf(sheet.values));
      setSheetOpen(false);
      notify(!editing ? 'created' : saved.terms.versionId !== editing.terms.versionId ? 'savedTerms' : 'saved');
      await load();
    } catch (error) {
      const left = failed(error);
      if (left?.code === 'bad_request')
        setSheet((current) =>
          current ? { ...current, apiProblems: problemsOfFields(left.fields, current.values) } : current,
        );
      else if (left?.code === 'invalid_state') setRefusal({ reason: 'unchanged' });
      else if (left?.code === 'limit_reached') setRefusal({ reason: 'serviceLimit' });
    }
    setBusy(false);
  }

  async function uploadPhoto(file: File) {
    const service = sheet?.service;
    if (!service) return;
    setPhotoUpload({ state: 'uploading' });
    const form = new FormData();
    form.append('serviceId', service.id);
    form.append('file', file);
    try {
      const saved = await change<CatalogueService>('catalogue-photo', form);
      setSheet((current) => (current ? { ...current, service: saved } : current));
      setPhotoUpload({ state: 'idle' });
      notify('photoSaved');
      await load();
    } catch (error) {
      const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
      if (['bad_request', 'request_rejected', 'service_unavailable'].includes(failure.code))
        setPhotoUpload({ state: 'refused', reason: failure.code });
      else {
        setPhotoUpload({ state: 'idle' });
        failed(error);
      }
    }
  }

  async function removePhoto() {
    const service = sheet?.service;
    if (!service) return;
    await quietly(
      async () => {
        const saved = await change<CatalogueService>('catalogue-photo-remove', { id: service.id });
        setSheet((current) => (current ? { ...current, service: saved } : current));
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
    setOpened((count) => count + 1);
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

  return {
    categories,
    services,
    loading,
    notice,
    refusal,
    locked,
    busy,
    reload: load,
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
