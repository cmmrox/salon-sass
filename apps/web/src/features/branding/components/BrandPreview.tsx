import { useTranslations } from 'next-intl';
import type { CSSProperties } from 'react';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { brandPreviewStyle } from '@/styles/brand';
import type { BrandImage } from '../client/brand-form';

/** An image beside the salon's name, which says nothing the name does not: no text alternative. */
const DECORATIVE = '';

type Props = { name: string; tagline: string; colour: string; logo: BrandImage | null; cover: BrandImage | null };

/** An image of the brand on the salon's own hostname (decision 086). */
export const brandImageUrl = (image: BrandImage) => `/brand-images/${image.id}`;

/**
 * The Branding tab's preview (F04-S02): the cover (the prototype's neutral one when there is none), the logo
 * as a circle (the salon's initial when there is none), the name, the tagline and "Book an appointment" in
 * the draft colour, as the customer app will show them. The draft colour reaches this card only, so an
 * unpublished colour never looks published. The button is a picture of the customer's, so it is not a
 * control here.
 */
export function BrandPreview({ name, tagline, colour, logo, cover }: Props) {
  const t = useTranslations('settings.brand');
  const initial = Array.from(name.trim())[0] ?? '';
  return (
    <Card
      className="gap-0 overflow-hidden p-0"
      style={brandPreviewStyle(colour) as CSSProperties}
      data-testid="brand-preview"
    >
      <div
        className="h-32.5 bg-secondary bg-[url(/brand/neutral-cover.svg)] bg-cover bg-position-[center_30%]"
        style={cover ? { backgroundImage: `url(${brandImageUrl(cover)})` } : undefined}
        data-testid="brand-preview-cover"
        data-image={cover?.id ?? 'neutral'}
      />
      <div className="flex flex-col gap-2 px-4 pb-4">
        <Avatar className="-mt-9.5 size-19 border-4 border-card bg-card" data-testid="brand-preview-logo">
          {logo && <AvatarImage src={brandImageUrl(logo)} alt={DECORATIVE} />}
          <AvatarFallback className="bg-secondary text-title font-semibold text-foreground">{initial}</AvatarFallback>
        </Avatar>
        <div className="flex min-w-0 flex-col">
          <p className="m-0 font-semibold wrap-anywhere" data-testid="brand-preview-name">
            {name.trim() || t('namePlaceholder')}
          </p>
          {tagline.trim() && (
            <p className="m-0 text-small wrap-anywhere text-muted-foreground" data-testid="brand-preview-tagline">
              {tagline.trim()}
            </p>
          )}
        </div>
        <Button asChild size="sm" className="w-fit cursor-default" data-testid="brand-preview-book">
          <span>{t('previewBook')}</span>
        </Button>
      </div>
    </Card>
  );
}
