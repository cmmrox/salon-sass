'use client';
import { formatMoney } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import type { CatalogueService } from '../types';
import { photoSource } from './photo-source';

type Props = {
  service: CatalogueService;
  categoryName: string;
  canChange: boolean;
  busy: boolean;
  onEdit: () => void;
  onToggleOnline: () => void;
  onDuplicate: () => void;
};

/**
 * One service as the prototype's Services & pricing card shows it (`aSvc`, F05-S01 AC-12): its name with "Online"
 * or "Hidden online", its category and description, its price, time and buffer, and Edit, Hide or Show online and
 * Duplicate for those who may change the catalogue. A hidden service's card takes the prototype's secondary
 * background (`.svc.off`) without its 0.7 opacity, and its small text is ink rather than muted, which would fall
 * under 4.5:1 there (docs/design/ui-ux-standards.md).
 */
export function ServiceCard({ service, categoryName, canChange, busy, onEdit, onToggleOnline, onDuplicate }: Props) {
  const t = useTranslations('services.card');
  const online = service.bookableOnline;
  const small = cn('text-small', online ? 'text-muted-foreground' : 'text-foreground');
  return (
    <Card
      className={cn('gap-0 p-5', !online && 'bg-secondary')}
      data-testid="service-card"
      data-id={service.id}
      data-online={online}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          {service.photoUrl && (
            // A photo the salon uploaded, already cropped square by the API.
            // eslint-disable-next-line @next/next/no-img-element
            <img
              src={photoSource(service.photoId)}
              alt={t('photo', { name: service.name })}
              className="size-12 shrink-0 rounded-field object-cover"
              data-testid="service-card-photo"
            />
          )}
          <h3 className="min-w-0 text-body font-semibold wrap-anywhere" data-testid="service-card-name">
            {service.name}
          </h3>
        </div>
        <Badge variant={online ? 'positive' : 'outline'} className="shrink-0" data-testid="service-card-status">
          {online ? t('online') : t('hidden')}
        </Badge>
      </div>
      <p className={cn(small, 'mt-1 mb-2 wrap-anywhere')} data-testid="service-card-detail">
        {service.description
          ? t('detail', { category: categoryName, description: service.description })
          : t('detailNone', { category: categoryName })}
      </p>
      <div className="flex flex-wrap items-baseline gap-x-3.5 gap-y-1.5">
        <span className="font-semibold" data-testid="service-card-price">
          {formatMoney(service.terms.priceMinor, service.terms.currency)}
        </span>
        <span className={small} data-testid="service-card-time">
          {t('minutes', { minutes: service.terms.durationMinutes })}
        </span>
        {service.terms.bufferMinutes > 0 && (
          <span className={small} data-testid="service-card-buffer">
            {t('buffer', { minutes: service.terms.bufferMinutes })}
          </span>
        )}
      </div>
      {canChange && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          <Button
            variant="outline"
            size="sm"
            onClick={onEdit}
            aria-label={t('editLabel', { name: service.name })}
            data-testid="service-card-edit"
          >
            {t('edit')}
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={onToggleOnline}
            aria-label={online ? t('hideLabel', { name: service.name }) : t('showLabel', { name: service.name })}
            data-testid="service-card-online"
          >
            {online ? t('hide') : t('show')}
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={onDuplicate}
            aria-label={t('duplicateLabel', { name: service.name })}
            data-testid="service-card-duplicate"
          >
            {t('duplicate')}
          </Button>
        </div>
      )}
    </Card>
  );
}
