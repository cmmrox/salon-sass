'use client';
import { useTranslations } from 'next-intl';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';
import { BRAND_SWATCHES } from '@/styles/brand';
import { typedColour } from '../client/brand-form';

type Props = { colour: string; error: boolean; disabled: boolean; onChange: (colour: string) => void };

/**
 * The brand colour (F04-S02): the prototype's six swatches as a Toggle Group, and the colour typed as
 * `#RRGGBB` with a swatch of what is typed beside it. No native colour input (web.md, "Styling").
 */
export function ColourField({ colour, error, disabled, onChange }: Props) {
  const t = useTranslations('settings.brand');
  const current = typedColour(colour);
  return (
    <div className="flex flex-col gap-3">
      <ToggleGroup
        type="single"
        value={current && (BRAND_SWATCHES as readonly string[]).includes(current) ? current : ''}
        onValueChange={(value) => value && onChange(value)}
        disabled={disabled}
        aria-label={t('swatches')}
        data-testid="brand-swatches"
      >
        {BRAND_SWATCHES.map((swatch) => (
          <ToggleGroupItem
            key={swatch}
            value={swatch}
            aria-label={t('useColour', { colour: swatch })}
            style={{ backgroundColor: swatch }}
            data-testid="brand-swatch"
            data-colour={swatch}
          />
        ))}
      </ToggleGroup>
      <Field data-invalid={error || undefined}>
        <FieldLabel htmlFor="brand-colour">{t('colourCode')}</FieldLabel>
        <div className="flex items-center gap-3">
          <span
            className="size-touch shrink-0 rounded-full border border-border"
            style={current ? { backgroundColor: current } : undefined}
            aria-hidden="true"
            data-testid="brand-colour-sample"
          />
          <Input
            id="brand-colour"
            data-testid="brand-colour"
            className="max-w-40 uppercase"
            value={colour}
            maxLength={7}
            spellCheck={false}
            autoComplete="off"
            disabled={disabled}
            aria-invalid={error}
            aria-describedby={error ? 'brand-colour-description brand-colour-error' : 'brand-colour-description'}
            onChange={(event) => onChange(event.target.value)}
          />
        </div>
        <FieldDescription id="brand-colour-description">{t('colourHelp')}</FieldDescription>
        {error && (
          <FieldError id="brand-colour-error" data-testid="brand-colour-error">
            {t('errors.colour')}
          </FieldError>
        )}
      </Field>
    </div>
  );
}
