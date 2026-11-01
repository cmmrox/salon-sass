'use client';
import { useTranslations } from 'next-intl';
import { Empty, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import type { CatalogueService, Category } from '../types';
import { ServiceCard } from './ServiceCard';

type Props = {
  categories: Category[];
  services: CatalogueService[];
  canChange: boolean;
  busy: boolean;
  onEdit: (service: CatalogueService) => void;
  onToggleOnline: (service: CatalogueService) => void;
  onDuplicate: (service: CatalogueService) => void;
};

/**
 * The Services tab's list (F05-S01 AC-12): every service under its category, in the salon's category order and by
 * name within it, two cards a row on wide screens. A category with no services says so.
 */
export function ServiceGroups({ categories, services, canChange, busy, onEdit, onToggleOnline, onDuplicate }: Props) {
  const t = useTranslations('services');
  const byName = (a: CatalogueService, b: CatalogueService) =>
    a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
  return (
    <div className="flex flex-col gap-2" data-testid="service-groups">
      {categories.map((category) => {
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
            {own.length === 0 ? (
              // The prototype's one quiet line ("No … services yet."), as the component map's Empty.
              <Empty className="items-start border-0 p-0 text-left" data-testid="service-group-empty">
                <EmptyHeader className="items-start text-left">
                  <EmptyTitle className="text-small font-normal">{t('noServices')}</EmptyTitle>
                </EmptyHeader>
              </Empty>
            ) : (
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
            )}
          </section>
        );
      })}
    </div>
  );
}
