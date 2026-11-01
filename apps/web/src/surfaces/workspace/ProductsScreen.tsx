'use client';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useRef, useState } from 'react';
import { toast } from 'sonner';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Toaster } from '@/components/ui/sonner';
import { LeaveDialog, useLeaveGuard } from '@/features/branding';
import { ConfirmDelete, ProductList, ProductSheet, TaxBanner } from '@/features/catalogue';
import type { Kind } from '@/features/identity';
import { ServicesFrame } from './ServicesFrame';
import { useProducts, type ProductsToast } from './use-products';

type Props = {
  kind: Kind;
  /** Whether this person may change the catalogue (`catalogue.price.change`, in a salon that is not read-only). */
  canChange: boolean;
  readOnly: 'support' | 'salon' | null;
};

/**
 * The Products tab of Services & pricing (F05-S03 AC-7, AC-8, AC-10; the F05 block's `prodTab` and `pePaint`): the
 * summary and + New product, the banner of what products are with its VAT sentence and the tax banner, the list, the
 * product sheet and the delete confirmation. Loading, empty, field-error, conflict, refused and unavailable states;
 * leaving the sheet with unsaved edits asks first. Those who may not change the catalogue read the list.
 */
export function ProductsScreen({ kind, canChange, readOnly }: Props) {
  const t = useTranslations('services');
  const router = useRouter();
  const products = useProducts(kind, (key: ProductsToast) => toast.success(t(`toasts.${key}`)));
  const may = canChange && !products.locked;
  const [closing, setClosing] = useState(false);
  // What opened the sheet, which gets focus back when it closes: New product, a row's Edit, the empty state's action.
  const opener = useRef<HTMLElement | null>(null);
  const remember =
    <T,>(open: (value: T) => void) =>
    (value: T) => {
      opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      open(value);
    };
  const giveFocusBack = () => opener.current?.isConnected && opener.current.focus();
  const openNew = remember(products.newProduct);
  const guard = useLeaveGuard(products.sheetDirty, (href) => router.push(href));

  const items = products.products?.items ?? [];
  const sold = items.filter((product) => product.soldAtCheckout).length;
  const vat = products.tax?.vat ?? null;
  const notices = <ProductsNotices products={products} readOnly={readOnly} />;
  const loading = products.loading && !products.products;

  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="services-page">
      <ServicesFrame
        tab="products"
        summary={t('productsSummary', { count: items.length, sold })}
        action={
          may && (
            <Button size="sm" onClick={() => openNew(undefined)} data-testid="products-new">
              {t('newProduct')}
            </Button>
          )
        }
      >
        {!products.sheetOpen && notices}
        <Alert variant="info" className="text-small" data-testid="products-banner">
          {t('productsBanner', { vatOn: String(vat !== null), vat: vat?.name ?? '' })}
        </Alert>
        <TaxBanner tax={products.tax} />
        {loading ? (
          <div
            className="flex flex-col gap-3"
            role="status"
            aria-busy="true"
            aria-label={t('products.loading')}
            data-testid="products-loading"
          >
            <Skeleton className="h-12 w-full rounded-card" />
            <Skeleton className="h-40 w-full rounded-card" />
          </div>
        ) : products.products ? (
          <ProductList
            products={items}
            tax={products.tax ?? null}
            canChange={may}
            onNew={() => openNew(undefined)}
            onEdit={remember(products.editProduct)}
          />
        ) : null}
      </ServicesFrame>
      {products.sheet && (
        <ProductSheet
          key={products.opened}
          open={products.sheetOpen}
          isNew={products.sheet.product === null}
          values={products.sheet.values}
          problems={products.sheetProblems}
          canSave={products.sheetCanSave}
          saving={products.busy}
          // The salon's currency, which the list gives also before its first product: what a save keeps it in.
          currency={products.products?.currency ?? 'LKR'}
          vat={vat}
          onChange={products.changeProduct}
          onSave={() => void products.save()}
          onClose={() => (products.sheetDirty ? setClosing(true) : products.closeSheet())}
          onClosed={giveFocusBack}
          onDelete={() => products.sheet?.product && products.askDelete(products.sheet.product)}
          notices={products.sheetOpen ? notices : null}
        />
      )}
      <ConfirmDelete
        open={products.confirm !== null}
        area="product-delete"
        title={t('deleteProduct.title', { name: products.confirm?.name ?? '' })}
        body={t('deleteProduct.body')}
        confirm={t('deleteProduct.confirm')}
        cancel={t('deleteProduct.cancel')}
        onCancel={products.cancelDelete}
        onConfirm={() => void products.confirmDelete()}
      />
      <LeaveDialog
        area="products"
        open={closing || guard.pending !== null}
        onStay={() => {
          setClosing(false);
          guard.stay();
        }}
        onLeave={() => {
          setClosing(false);
          products.closeSheet();
          guard.leave();
        }}
      />
      <Toaster position="bottom-center" />
    </div>
  );
}

type Products = ReturnType<typeof useProducts>;

/** What the tab says above its list: read-only views, a conflict, an unreachable API, a refusal or a rule. */
function ProductsNotices({ products, readOnly }: { products: Products; readOnly: 'support' | 'salon' | null }) {
  const t = useTranslations('services.notices');
  return (
    <>
      {readOnly && (
        <Alert variant="notice" data-testid="products-read-only">
          {readOnly === 'support' ? t('productsReadOnlySupport') : t('productsReadOnlySalon')}
        </Alert>
      )}
      {products.notice === 'conflict' && (
        <Alert variant="caution" data-testid="products-conflict">
          <AlertTitle>{t('productsConflictTitle')}</AlertTitle>
          <AlertDescription>{t('conflictBody')}</AlertDescription>
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="w-fit"
            onClick={() => void products.reload()}
            data-testid="products-conflict-reload"
          >
            {t('reload')}
          </Button>
        </Alert>
      )}
      {products.notice === 'unavailable' && (
        <Alert variant="caution" data-testid="products-unavailable">
          <AlertTitle>{t('productsUnavailableTitle')}</AlertTitle>
          <AlertDescription>{products.products ? t('unavailableKept') : t('unavailableBody')}</AlertDescription>
          {!products.products && (
            <Button
              variant="outline"
              size="sm"
              className="w-fit"
              onClick={() => void products.reload()}
              data-testid="products-retry"
            >
              {t('retry')}
            </Button>
          )}
        </Alert>
      )}
      {products.notice === 'refused' && (
        <Alert variant="notice" role="alert" data-testid="products-refused">
          {t('productsRefused')}
        </Alert>
      )}
      {products.refusal && (
        <Alert variant="notice" role="alert" data-testid="products-rule" data-reason={products.refusal}>
          {t(products.refusal)}
        </Alert>
      )}
    </>
  );
}
