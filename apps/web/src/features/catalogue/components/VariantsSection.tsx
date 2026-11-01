'use client';
import { currencyMark, formatMoney } from '@salon/format';
import { X } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Field, FieldContent, FieldDescription, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Item, ItemActions, ItemContent, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Switch } from '@/components/ui/switch';
import {
  VARIANT_FIELD_OF,
  VARIANTS_MAX,
  problemParts,
  rowKey,
  whole,
  withVariantsOn,
  type ServiceProblem,
  type ServiceValues,
  type VariantValues,
} from '../client/service-form';

type Props = {
  values: ServiceValues;
  problems: ServiceProblem[];
  currency: string;
  /** The id of the list of problems, which an invalid field names. */
  problemsId: string;
  onValues: (change: (values: ServiceValues) => ServiceValues) => void;
};

const MINOR_PER_RUPEE = 100;

/**
 * The service sheet's one set of variants (F05-S02 AC-1, AC-3; the prototype's option group, `seAddC`, `seDelC`, and
 * its price list preview, `svCombos`): a Switch turns them on, then the set's name, a row for each variant (name,
 * price, time and its remove button) and **+ Add variant**, up to ten, and each variant's price and time listed.
 * Pricing kinds, more groups, add-ons and "show only when" are F17's and not shown.
 */
export function VariantsSection({ values, problems, currency, problemsId, onValues }: Props) {
  const t = useTranslations('services.sheet.variants');
  const mark = currencyMark(currency);
  const invalid = (index: number, field: VariantField) =>
    problems.some((problem) => {
      const { key, index: at } = problemParts(problem);
      return (
        at === index && key in VARIANT_FIELD_OF && VARIANT_FIELD_OF[key as keyof typeof VARIANT_FIELD_OF] === field
      );
    });
  const setInvalid = problems.includes('setName');
  const change = (index: number, field: VariantField, value: string) =>
    onValues((current) => ({
      ...current,
      variants: current.variants.map((variant, at) => (at === index ? { ...variant, [field]: value } : variant)),
    }));
  const toggle = (on: boolean) =>
    onValues((current) =>
      on ? withVariantsOn(current, [t('option', { n: 1 }), t('option', { n: 2 })]) : { ...current, hasVariants: false },
    );
  const add = () =>
    onValues((current) => {
      const last = current.variants.at(-1);
      const next: VariantValues = {
        key: rowKey(),
        id: null,
        name: t('option', { n: current.variants.length + 1 }),
        price: last?.price ?? current.price,
        minutes: last?.minutes ?? current.minutes,
      };
      return { ...current, variants: [...current.variants, next] };
    });
  const remove = (index: number) =>
    onValues((current) => ({ ...current, variants: current.variants.filter((_, at) => at !== index) }));
  const preview = problems.some((problem) => problemParts(problem).key.startsWith('variant') || problem === 'setName');

  return (
    <Card className="gap-4 border-0 bg-secondary p-4" data-surface="canvas" data-testid="service-variants">
      <Field orientation="horizontal" className="min-h-touch">
        <Switch
          id="service-variants"
          checked={values.hasVariants}
          onCheckedChange={toggle}
          aria-describedby="service-variants-help"
          data-testid="service-variants-toggle"
        />
        <FieldContent>
          <FieldLabel htmlFor="service-variants">{t('toggle')}</FieldLabel>
          <FieldDescription id="service-variants-help">{t('toggleHelp')}</FieldDescription>
        </FieldContent>
      </Field>
      {values.hasVariants && (
        <>
          <Field>
            <FieldLabel htmlFor="service-variant-set">{t('setName')}</FieldLabel>
            <Input
              id="service-variant-set"
              maxLength={30}
              placeholder={t('setNamePlaceholder')}
              value={values.setName}
              onChange={(event) => onValues((current) => ({ ...current, setName: event.target.value }))}
              aria-invalid={setInvalid || undefined}
              aria-describedby={setInvalid ? problemsId : undefined}
              data-testid="service-variant-set"
            />
          </Field>
          <div className="flex flex-col gap-2" role="group" aria-label={t('listLabel')}>
            <div
              className="grid grid-cols-[minmax(0,1fr)_5.25rem_3.75rem_2.75rem] gap-1.5 text-small text-foreground"
              aria-hidden="true"
            >
              <span>{t('name')}</span>
              <span>{t('price', { mark })}</span>
              <span>{t('minutes')}</span>
            </div>
            {values.variants.map((variant, index) => (
              <div
                key={variant.key}
                className="grid grid-cols-[minmax(0,1fr)_5.25rem_3.75rem_2.75rem] items-center gap-1.5"
                data-testid="service-variant-row"
                data-index={index}
              >
                {(['name', 'price', 'minutes'] as const).map((field) => (
                  <Input
                    key={field}
                    className="min-w-0 px-2.5"
                    inputMode={field === 'name' ? undefined : 'numeric'}
                    maxLength={field === 'name' ? 30 : undefined}
                    value={variant[field]}
                    onChange={(event) => change(index, field, event.target.value)}
                    aria-label={t(`${field}Label`, { n: index + 1 })}
                    aria-invalid={invalid(index, field) || undefined}
                    aria-describedby={invalid(index, field) ? problemsId : undefined}
                    data-testid={`service-variant-${field === 'minutes' ? 'time' : field}`}
                  />
                ))}
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="min-h-touch px-0"
                  onClick={() => remove(index)}
                  aria-label={t('remove', { n: index + 1 })}
                  data-testid="service-variant-remove"
                >
                  <X aria-hidden="true" />
                </Button>
              </div>
            ))}
          </div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="w-fit"
            disabled={values.variants.length >= VARIANTS_MAX}
            onClick={add}
            data-testid="service-variants-add"
          >
            {t('add')}
          </Button>
          <section className="flex flex-col gap-2" aria-labelledby="service-variant-preview-title">
            <h3 id="service-variant-preview-title" className="m-0 text-body font-semibold">
              {t('preview')}
            </h3>
            {preview ? (
              <p className="m-0 text-small text-foreground" data-testid="service-variant-preview-empty">
                {t('previewBlocked')}
              </p>
            ) : (
              <ItemGroup data-testid="service-variant-preview">
                {values.variants.map((variant) => (
                  <Item key={variant.key} variant="divided" data-testid="service-variant-preview-row">
                    <ItemContent>
                      <ItemTitle className="font-normal wrap-anywhere">{variant.name.trim()}</ItemTitle>
                    </ItemContent>
                    <ItemActions className="text-small">
                      <span className="font-medium">
                        {formatMoney((whole(variant.price) ?? 0) * MINOR_PER_RUPEE, currency)}
                      </span>
                      <span>{t('previewTime', { minutes: whole(variant.minutes) ?? 0 })}</span>
                    </ItemActions>
                  </Item>
                ))}
              </ItemGroup>
            )}
          </section>
        </>
      )}
    </Card>
  );
}

type VariantField = 'name' | 'price' | 'minutes';
