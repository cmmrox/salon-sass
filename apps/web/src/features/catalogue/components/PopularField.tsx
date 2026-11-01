'use client';
import { useTranslations } from 'next-intl';
import { Field, FieldContent, FieldDescription, FieldLabel } from '@/components/ui/field';
import { Switch } from '@/components/ui/switch';

type Props = {
  popular: boolean;
  /** How the salon's Popular list is made: "Most booked" makes the choice wait. */
  mode: 'chosen' | 'most_booked';
  /** How many of the salon's other services are chosen. */
  chosenElsewhere: number;
  onChange: (popular: boolean) => void;
};

const MAX_POPULAR = 6;

/**
 * "Show in Popular" in the service sheet (F05-S02 AC-5, AC-6; the F05 block's additions to `sePaint`): disabled while
 * the salon shows its most booked services, or when six others are already chosen, and it says why.
 */
export function PopularField({ popular, mode, chosenElsewhere, onChange }: Props) {
  const t = useTranslations('services.sheet.popular');
  const full = !popular && chosenElsewhere >= MAX_POPULAR;
  const disabled = mode === 'most_booked' || full;
  return (
    <Field orientation="horizontal" className="min-h-touch" data-disabled={disabled || undefined}>
      <Switch
        id="service-popular"
        checked={popular}
        disabled={disabled}
        onCheckedChange={onChange}
        aria-describedby="service-popular-help"
        data-testid="service-sheet-popular"
      />
      <FieldContent>
        <FieldLabel htmlFor="service-popular">{t('label')}</FieldLabel>
        <FieldDescription
          id="service-popular-help"
          className="text-foreground"
          data-testid="service-sheet-popular-help"
        >
          {mode === 'most_booked' ? t('mostBooked') : full ? t('full', { count: MAX_POPULAR }) : t('help')}
        </FieldDescription>
      </FieldContent>
    </Field>
  );
}
