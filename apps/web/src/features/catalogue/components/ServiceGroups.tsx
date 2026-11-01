'use client';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Empty, EmptyContent, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import type { CatalogueService, Category } from '../types';
import { ServiceCard } from './ServiceCard';

type Props = {
  categories: Category[];
  services: CatalogueService[];
  canChange: boolean;
  busy: boolean;
  /** Whether a service can be made: it needs a category to go in. */
  canAdd: boolean;
  onNew: () => void;
  onEdit: (service: CatalogueService) => void;
  onToggleOnline: (service: CatalogueService) => void;
  onDuplicate: (service: CatalogueService) => void;
};

/**
 * The Services tab's list (F05-S01 AC-12, AC-18): each category that has services, in the salon's category order, with
 * its services by name, two cards a row on wide screens. The categories without services are folded into one line
 * after them; a salon with no services at all sees the Empty state instead.
 */
export function ServiceGroups({
  categories,
  services,
  canChange,
  busy,
  canAdd,
  onNew,
  onEdit,
  onToggleOnline,
  onDuplicate,
}: Props) {
  const t = useTranslations('services');
  const byName = (a: CatalogueService, b: CatalogueService) =>
    a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
  if (services.length === 0)
    return (
      <Empty surface="card" data-testid="services-empty">
        <EmptyHeader>
          <EmptyTitle>{t('emptyTitle')}</EmptyTitle>
        </EmptyHeader>
        {canChange && (
          <EmptyContent>
            <Button size="sm" onClick={onNew} disabled={!canAdd} data-testid="services-empty-new">
              {t('newService')}
            </Button>
          </EmptyContent>
        )}
      </Empty>
    );
  const hasServices = (category: Category) => services.some((service) => service.categoryId === category.id);
  const empty = categories.filter((category) => !hasServices(category));
  return (
    <div className="flex flex-col gap-2" data-testid="service-groups">
      {categories.filter(hasServices).map((category) => {
        const own = services.filter((service) => service.categoryId === category.id).sort(byName);
        return (
          <section
            key={category.id}
            className="flex flex-col"
            aria-labelledby={`category-${category.id}`}
            data-testid="service-group"
            data-id={category.id}
          >
            <h2 id={`category-${category.id}`} className="mt-6 mb-3 text-large font-semibold wrap-anywhere">
              {category.name}
            </h2>
            <div className="grid gap-3 wide:grid-cols-2">
              {own.map((service) => (
                <ServiceCard
                  key={service.id}
                  service={service}
                  categoryName={category.name}
                  canChange={canChange}
                  busy={busy}
                  onEdit={() => onEdit(service)}
                  onToggleOnline={() => onToggleOnline(service)}
                  onDuplicate={() => onDuplicate(service)}
                />
              ))}
            </div>
          </section>
        );
      })}
      {empty.length > 0 && (
        // The prototype's one quiet line for an empty category, as the component map's Empty, for all of them.
        <Empty className="mt-6 items-start border-0 p-0 text-left" data-testid="services-empty-categories">
          <EmptyHeader className="items-start text-left">
            <EmptyTitle className="text-small font-normal wrap-anywhere">
              {t('noServicesIn', { names: empty.map((category) => category.name).join(', ') })}
            </EmptyTitle>
          </EmptyHeader>
        </Empty>
      )}
    </div>
  );
}
