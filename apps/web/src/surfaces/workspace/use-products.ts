'use client';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import {
  newProduct,
  productProblemOfField,
  productProblemsOf,
  productRequestOf,
  productValuesOf,
  sameProduct,
  type Product,
  type ProductListAnswer,
  type ProductProblem,
  type ProductValues,
  type PublicTax,
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

/** What the tab says above the list: someone else changed it, the API could not be reached, or a change was refused. */
export type ProductsNotice = 'conflict' | 'unavailable' | 'refused' | null;

/** Why a change was not made, said in an Alert: a rule of the products rather than a broken field. */
export type ProductsRefusal = 'productLimit' | 'productUnchanged' | null;

/** The passing notices the tab announces in a toast. */
export type ProductsToast = 'productAdded' | 'productSaved' | 'productDeleted';

type SheetState = {
  product: Product | null;
  values: ProductValues;
  /** The fields the person has changed, whose problems are shown as they type. */
  touched: ('name' | 'price')[];
  apiProblems: ProductProblem[];
};

const FIELD_OF: Record<ProductProblem, 'name' | 'price'> = { name: 'name', taken: 'name', price: 'price' };

/**
 * The Products tab's state (F05-S03 AC-7, AC-8): the products and the salon's current tax, read together; the product
 * sheet with its checks; the delete confirmation; and every change, under the session's cross-tab lock. A 403 on a
 * change makes the tab read-only from then on and says so.
 */
export function useProducts(kind: Kind, notify: (toast: ProductsToast) => void) {
  const router = useRouter();
  const [products, setProducts] = useState<ProductListAnswer | null>(null);
  // The salon's current tax: null before the first save, undefined while it could not be read.
  const [tax, setTax] = useState<PublicTax | null | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<ProductsNotice>(null);
  const [refusal, setRefusal] = useState<ProductsRefusal>(null);
  const [locked, setLocked] = useState(false);
  const [busy, setBusy] = useState(false);
  // The sheet keeps its last content while it closes, so that focus can go back to what opened it.
  const [sheet, setSheet] = useState<SheetState | null>(null);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [opened, setOpened] = useState(0);
  const [confirm, setConfirm] = useState<Product | null>(null);

  /** A failure the tab answers itself; a field error is returned for the sheet that sent it. */
  function failed(error: unknown): IdentityFailure | null {
    const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
    if (failure.code === 'invalid_session') {
      leaveEndedSession(kind, router);
      return null;
    }
    if (failure.code === 'bad_request') return failure;
    if (failure.code === 'conflict' || failure.code === 'not_found') setNotice('conflict');
    else if (failure.code === 'service_unavailable' || failure.code === 'browser_unsupported') setNotice('unavailable');
    else if (failure.code === 'limit_reached') setRefusal('productLimit');
    else if (failure.code === 'invalid_state') setRefusal('productUnchanged');
    else {
      setNotice('refused');
      setLocked(true);
      setSheetOpen(false);
    }
    return null;
  }

  /** Reads the products and the salon's tax; null when the products could not be read (the tab says why). */
  async function load(): Promise<ProductListAnswer | null> {
    setLoading(true);
    try {
      const [next, published] = await Promise.all([
        identityRequest<ProductListAnswer>(kind, 'catalogue-products'),
        // Only the tax column and the banners depend on it: the tab works without it.
        identityRequest<{ current: PublicTax | null }>(kind, 'catalogue-tax-public').then(
          (answer) => answer.current,
          () => undefined,
        ),
      ]);
      setProducts(next);
      setTax(published);
      setNotice((current) => (current === 'refused' ? current : null));
      return next;
    } catch (error) {
      failed(error);
      return null;
    } finally {
      setLoading(false);
    }
  }

  /** Reads the products again after a conflict, and the open sheet's product as it is now, edits dropped. */
  async function reload() {
    setRefusal(null);
    const fresh = await load();
    if (!fresh || !sheetOpen || !sheet?.product) return;
    const now = fresh.items.find((each) => each.id === sheet.product?.id);
    if (now) setSheet({ product: now, values: productValuesOf(now), touched: [], apiProblems: [] });
    else setSheetOpen(false);
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

  async function change<T>(action: string, body: unknown): Promise<T> {
    if (!supportsLocks()) throw new IdentityFailure('browser_unsupported');
    return withIdentityLock(kind, async () => {
      await principalInsideLock(kind);
      return identityRequest<T>(kind, action, body);
    });
  }

  const otherNames = (id: string | null) =>
    (products?.items ?? []).filter((product) => product.id !== id).map((product) => product.name);
  const problems = sheet ? productProblemsOf(sheet.values, otherNames(sheet.product?.id ?? null)) : [];
  const shown = sheet
    ? [...new Set([...problems.filter((problem) => sheet.touched.includes(FIELD_OF[problem])), ...sheet.apiProblems])]
    : [];
  const unchanged = Boolean(sheet?.product && sameProduct(sheet.values, sheet.product));

  function open(state: SheetState) {
    setNotice((current) => (current === 'refused' ? current : null));
    setRefusal(null);
    setSheet(state);
    setOpened((count) => count + 1);
    setSheetOpen(true);
  }

  async function save() {
    if (!sheet || problems.length > 0) return;
    setBusy(true);
    setRefusal(null);
    try {
      const request = productRequestOf(sheet.values);
      if (sheet.product) {
        await change('catalogue-product-save', { ...request, id: sheet.product.id, version: sheet.product.version });
      } else {
        await change('catalogue-product-add', request);
      }
      setSheetOpen(false);
      notify(sheet.product ? 'productSaved' : 'productAdded');
      await load();
    } catch (error) {
      const refused = failed(error);
      if (refused) {
        const values = sheet.values;
        const fields = refused.fields
          .map((field) => productProblemOfField(field, values))
          .filter((problem): problem is ProductProblem => problem !== null);
        setSheet((now) => (now ? { ...now, apiProblems: fields } : now));
      }
    }
    setBusy(false);
  }

  async function confirmDelete() {
    const product = confirm;
    setConfirm(null);
    if (!product) return;
    setBusy(true);
    setRefusal(null);
    try {
      await change('catalogue-product-delete', { id: product.id, version: product.version });
      setSheetOpen(false);
      notify('productDeleted');
      await load();
    } catch (error) {
      failed(error);
    }
    setBusy(false);
  }

  return {
    products,
    tax,
    loading,
    notice,
    refusal,
    locked,
    busy,
    reload,
    sheet,
    sheetOpen,
    opened,
    sheetProblems: shown,
    sheetCanSave: Boolean(sheet) && problems.length === 0 && !unchanged,
    sheetDirty: Boolean(
      sheetOpen &&
      sheet &&
      (sheet.product
        ? !sameProduct(sheet.values, sheet.product)
        : sheet.values.name !== '' || sheet.values.price !== ''),
    ),
    newProduct: () => open({ product: null, values: newProduct(), touched: [], apiProblems: [] }),
    editProduct: (product: Product) =>
      open({ product, values: productValuesOf(product), touched: [], apiProblems: [] }),
    changeProduct: <K extends keyof ProductValues>(field: K, value: ProductValues[K]) =>
      setSheet((now) =>
        now
          ? {
              ...now,
              values: { ...now.values, [field]: value },
              touched:
                (field === 'name' || field === 'price') && !now.touched.includes(field)
                  ? [...now.touched, field]
                  : now.touched,
              apiProblems: [],
            }
          : now,
      ),
    save,
    closeSheet: () => setSheetOpen(false),
    askDelete: (product: Product) => setConfirm(product),
    cancelDelete: () => setConfirm(null),
    confirm,
    confirmDelete,
  };
}
