'use client';
import { formatMoney } from '@salon/format';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useRef, useState } from 'react';
import { toast } from 'sonner';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Toaster } from '@/components/ui/sonner';
import { LeaveDialog, useLeaveGuard } from '@/features/branding';
import {
  CategoryRows,
  CategorySheet,
  ConfirmDelete,
  PopularCard,
  ServiceGroups,
  ServiceSheet,
  TaxSentence,
  type CatalogueService,
  type Category,
} from '@/features/catalogue';
import type { PublicBookingRules } from '@/features/booking';
import type { Kind } from '@/features/identity';
import { ProductsScreen } from './ProductsScreen';
import { ServicesFrame, type ServicesTab } from './ServicesFrame';
import { useCatalogue, type CatalogueToast } from './use-catalogue';

export type { ServicesTab } from './ServicesFrame';

type Props = {
  kind: Kind;
  tab: ServicesTab;
  /** Whether this person may change the catalogue (`catalogue.price.change`, in a salon that is not read-only). */
  canChange: boolean;
  readOnly: 'support' | 'salon' | null;
};

/**
 * The Services & pricing page (F05-S01, the prototype's `aSvc` with the F05 block's `svTabs`): the Services and
 * Categories tabs, and from F05-S03 the Products tab, which keeps its own state.
 */
export function ServicesScreen(props: Props) {
  return props.tab === 'products' ? <ProductsScreen {...props} /> : <ServicesAndCategories {...props} />;
}

/**
 * The Services and Categories tabs (F05-S01): the summary and New service or New category, the tabs, and the sheets
 * and confirmations they open. Loading, empty, field-error, conflict, refused and unavailable states; leaving the
 * service sheet with unsaved edits asks first. Those who may not change the catalogue read both tabs. The Services
 * tab's one banner carries the tax sentence (F05-S01 AC-17).
 */
function ServicesAndCategories({ kind, tab, canChange, readOnly }: Props) {
  const t = useTranslations('services');
  const router = useRouter();
  const catalogue = useCatalogue(kind, (key: CatalogueToast) => toast.success(t(`toasts.${key}`)));
  const may = canChange && !catalogue.locked;
  const [closing, setClosing] = useState(false);
  // What opened a sheet, which gets focus back when it closes: the sheets open from card and row buttons.
  const opener = useRef<HTMLElement | null>(null);
  const remember =
    <T,>(open: (value: T) => void) =>
    (value: T) => {
      opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      open(value);
    };
  const giveFocusBack = () => opener.current?.isConnected && opener.current.focus();
  const guard = useLeaveGuard(catalogue.sheetDirty, (href) => router.push(href));

  const categories = catalogue.categories?.items ?? [];
  const services = catalogue.services?.items ?? [];
  const online = services.filter((service) => service.bookableOnline).length;
  // The salon's currency, which the list gives also before its first service: what a save keeps amounts in.
  const currency = catalogue.services?.currency ?? 'LKR';
  const salonRule = useSalonRuleWords(catalogue.rules);
  const popularMode = catalogue.popular?.mode ?? 'chosen';

  const action =
    may &&
    (tab === 'services' ? (
      <Button
        size="sm"
        onClick={remember(catalogue.newService)}
        disabled={categories.length === 0}
        data-testid="services-new"
      >
        {t('newService')}
      </Button>
    ) : (
      <Button size="sm" onClick={remember(catalogue.newCategory)} data-testid="categories-new">
        {t('newCategory')}
      </Button>
    ));

  const loading = catalogue.loading && !catalogue.categories;
  // While a sheet is open the page behind its overlay is hidden from view and from assistive technology, so the
  // page's notices are drawn in the sheet instead (BUG found in F05-S01's dry run: a conflict on save said nothing).
  const inSheet = catalogue.sheetOpen || catalogue.categoryOpen;
  const notices = <Notices catalogue={catalogue} readOnly={readOnly} />;
  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="services-page">
      <ServicesFrame
        tab={tab}
        summary={
          tab === 'services'
            ? t('summary', { services: services.length, online })
            : t('categoriesSummary', { count: categories.length })
        }
        action={action}
      >
        {!inSheet && notices}
        <Alert variant="info" className="text-small" data-testid="services-banner">
          {tab === 'services' ? (
            <>
              {t('banner')} <TaxSentence tax={catalogue.tax} of="services" />
            </>
          ) : (
            t('categoriesBanner')
          )}
        </Alert>
        {loading ? (
          <div
            className="flex flex-col gap-3"
            role="status"
            aria-busy="true"
            aria-label={t('loading')}
            data-testid="services-loading"
          >
            <Skeleton className="h-7 w-40" />
            <div className="grid gap-3 wide:grid-cols-2">
              <Skeleton className="h-40 w-full rounded-card" />
              <Skeleton className="h-40 w-full rounded-card" />
            </div>
          </div>
        ) : catalogue.categories && tab === 'services' ? (
          <>
            <PopularCard
              mode={popularMode}
              chosen={chosenNames(categories, services)}
              now={(catalogue.popular?.popular ?? []).map(
                (id) => services.find((service) => service.id === id)?.name ?? '',
              )}
              canChange={may}
              busy={catalogue.busy}
              onMode={(mode) => void catalogue.switchPopular(mode)}
            />
            <ServiceGroups
              categories={categories}
              services={services}
              canChange={may}
              busy={catalogue.busy}
              canAdd={categories.length > 0}
              onNew={() => remember(catalogue.newService)(undefined)}
              onEdit={remember(catalogue.editService)}
              onToggleOnline={(service) => void catalogue.toggleOnline(service)}
              onDuplicate={remember((service) => void catalogue.duplicate(service))}
            />
          </>
        ) : catalogue.categories ? (
          <CategoryRows
            categories={categories}
            canChange={may}
            busy={catalogue.busy}
            onMove={(category, direction) => void catalogue.move(category, direction)}
            onRename={remember(catalogue.renameCategory)}
            onDelete={catalogue.askDeleteCategory}
          />
        ) : null}
      </ServicesFrame>
      {catalogue.sheet && (
        <ServiceSheet
          key={catalogue.opened}
          open={catalogue.sheetOpen}
          isNew={catalogue.sheet.service === null}
          values={catalogue.sheet.values}
          problems={catalogue.sheetProblems}
          canSave={catalogue.sheetCanSave}
          saving={catalogue.busy}
          categories={categories}
          currency={currency}
          photoId={catalogue.sheet.service?.photoId ?? null}
          photoUpload={catalogue.photoUpload}
          onChange={catalogue.changeService}
          onValues={catalogue.changeServiceValues}
          popularMode={popularMode}
          chosenElsewhere={catalogue.chosenElsewhere}
          salonRule={salonRule}
          onSave={() => void catalogue.saveService()}
          onClose={() => (catalogue.sheetDirty ? setClosing(true) : catalogue.closeSheet())}
          onDelete={() => catalogue.sheet?.service && catalogue.askDeleteService(catalogue.sheet.service)}
          onPhoto={(file) => void catalogue.uploadPhoto(file)}
          onRemovePhoto={() => void catalogue.removePhoto()}
          onClosed={giveFocusBack}
          notices={catalogue.sheetOpen ? notices : null}
        />
      )}
      {catalogue.categorySheet && (
        <CategorySheet
          open={catalogue.categoryOpen}
          renaming={catalogue.categorySheet.renaming !== null}
          name={catalogue.categorySheet.name}
          problem={catalogue.categorySheet.problem}
          saving={catalogue.busy}
          onName={catalogue.typeCategory}
          onSave={() => void catalogue.saveCategory()}
          onClose={catalogue.closeCategorySheet}
          onClosed={giveFocusBack}
          notices={catalogue.categoryOpen ? notices : null}
        />
      )}
      <ConfirmDelete
        open={catalogue.confirm !== null && 'service' in catalogue.confirm}
        area="service-delete"
        title={t('deleteService.title', {
          name: catalogue.confirm && 'service' in catalogue.confirm ? catalogue.confirm.service.name : '',
        })}
        body={t('deleteService.body')}
        confirm={t('deleteService.confirm')}
        cancel={t('deleteService.cancel')}
        onCancel={catalogue.cancelDelete}
        onConfirm={catalogue.confirmDelete}
      />
      <ConfirmDelete
        open={catalogue.confirm !== null && 'category' in catalogue.confirm}
        area="category-delete"
        title={t('deleteCategory.title', {
          name: catalogue.confirm && 'category' in catalogue.confirm ? catalogue.confirm.category.name : '',
        })}
        body={t('deleteCategory.body')}
        confirm={t('deleteCategory.confirm')}
        cancel={t('deleteCategory.cancel')}
        onCancel={catalogue.cancelDelete}
        onConfirm={catalogue.confirmDelete}
      />
      <LeaveDialog
        area="services"
        open={closing || guard.pending !== null}
        onStay={() => {
          setClosing(false);
          guard.stay();
        }}
        onLeave={() => {
          setClosing(false);
          catalogue.closeSheet();
          guard.leave();
        }}
      />
      <Toaster position="bottom-center" />
    </div>
  );
}

type Catalogue = ReturnType<typeof useCatalogue>;

/** The salon's chosen Popular services' names, in category then name order, as the menu lists them. */
function chosenNames(categories: Category[], services: CatalogueService[]): string[] {
  const byName = (a: CatalogueService, b: CatalogueService) =>
    a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
  return categories.flatMap((category) =>
    services
      .filter((service) => service.popular && service.categoryId === category.id)
      .sort(byName)
      .map((service) => service.name),
  );
}

/**
 * The salon's own deposit rule in words, for a service's "Salon rule" (F05-S02 AC-7): "20%, up to Rs. 2,000", "Rs.
 * 1,000", "no deposit", or "not set yet" before the rules are first saved; null when they could not be read.
 */
function useSalonRuleWords(rules: PublicBookingRules | null | undefined): string | null {
  const t = useTranslations('services.sheet.deposit.rule');
  if (rules === undefined) return null;
  if (rules === null) return t('unset');
  const deposit = rules.rules.deposit;
  if (deposit.kind === 'percentage')
    return t('percentage', { percent: deposit.percent ?? 0, cap: formatMoney(deposit.capMinor ?? 0, rules.currency) });
  if (deposit.kind === 'amount') return t('amount', { amount: formatMoney(deposit.amountMinor ?? 0, rules.currency) });
  return t('none');
}

/** What the page says above its lists: read-only views, a conflict, an unreachable API, a refusal or a rule. */
function Notices({ catalogue, readOnly }: { catalogue: Catalogue; readOnly: 'support' | 'salon' | null }) {
  const t = useTranslations('services.notices');
  const refusal = catalogue.refusal;
  return (
    <>
      {readOnly && (
        <Alert variant="notice" data-testid="services-read-only">
          {readOnly === 'support' ? t('readOnlySupport') : t('readOnlySalon')}
        </Alert>
      )}
      {catalogue.notice === 'conflict' && (
        <Alert variant="caution" data-testid="services-conflict">
          <AlertTitle>{t('conflictTitle')}</AlertTitle>
          <AlertDescription>{t('conflictBody')}</AlertDescription>
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="w-fit"
            onClick={() => void catalogue.reload()}
            data-testid="services-conflict-reload"
          >
            {t('reload')}
          </Button>
        </Alert>
      )}
      {catalogue.notice === 'unavailable' && (
        <Alert variant="caution" data-testid="services-unavailable">
          <AlertTitle>{t('unavailableTitle')}</AlertTitle>
          <AlertDescription>{catalogue.categories ? t('unavailableKept') : t('unavailableBody')}</AlertDescription>
          {!catalogue.categories && (
            <Button
              variant="outline"
              size="sm"
              className="w-fit"
              onClick={() => void catalogue.reload()}
              data-testid="services-retry"
            >
              {t('retry')}
            </Button>
          )}
        </Alert>
      )}
      {catalogue.notice === 'refused' && (
        <Alert variant="notice" role="alert" data-testid="services-refused">
          {t('refused')}
        </Alert>
      )}
      {refusal && (
        <Alert variant="notice" role="alert" data-testid="services-rule" data-reason={refusal.reason}>
          {refusal.reason === 'inUseCategory' ? t('inUseCategory', { count: refusal.count }) : t(refusal.reason)}
        </Alert>
      )}
    </>
  );
}
