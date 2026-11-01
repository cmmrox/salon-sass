'use client';
import { currencyMark, formatMoney } from '@salon/format';
import { ChevronDown } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useState, type ReactNode } from 'react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Field, FieldContent, FieldDescription, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Sheet, SheetClose, SheetContent, SheetTitle } from '@/components/ui/sheet';
import { Spinner } from '@/components/ui/spinner';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import {
  FIELD_OF,
  highestPrice,
  lowestPrice,
  problemParts,
  type ServiceProblem,
  type ServiceValues,
} from '../client/service-form';
import type { Category } from '../types';
import { DepositField } from './DepositField';
import { PhotoField, type PhotoUpload } from './PhotoField';
import { PopularField } from './PopularField';
import { SHEET_CLASSES } from './sheet-classes';
import { VariantsSection } from './VariantsSection';

type Props = {
  open: boolean;
  isNew: boolean;
  values: ServiceValues;
  /** The problems shown: the prototype's `seErr` messages, at most four, then how many more. */
  problems: ServiceProblem[];
  canSave: boolean;
  saving: boolean;
  categories: Category[];
  currency: string;
  photoId: string | null;
  photoUpload: PhotoUpload;
  onChange: <K extends keyof ServiceValues>(field: K, value: ServiceValues[K]) => void;
  /** A change of several values at once: the variants and the deposit. */
  onValues: (change: (values: ServiceValues) => ServiceValues) => void;
  /** How the salon's Popular list is made, and how many other services are chosen (F05-S02). */
  popularMode: 'chosen' | 'most_booked';
  chosenElsewhere: number;
  /** The salon's own deposit rule in words, for "Salon rule"; null while it cannot be read. */
  salonRule: string | null;
  onSave: () => void;
  onClose: () => void;
  /** Where focus goes when the sheet has closed: what opened it, as it has no trigger of its own. */
  onClosed: () => void;
  onDelete: () => void;
  onPhoto: (file: File) => void;
  onRemovePhoto: () => void;
  /**
   * What the page says while the sheet is open (a conflict with its Reload, a refused rule, an unreachable API),
   * drawn inside the sheet: the page behind its overlay can be neither seen nor used.
   */
  notices?: ReactNode;
};

const PROBLEMS_ID = 'service-problems';
const SHOWN = 4;
const MINOR_PER_RUPEE = 100;

/**
 * The prototype's service sheet (`sePaint`, F05-S01 AC-3): name, price and time; under "More options" the category,
 * short description, buffer, photo and Bookable online. The broken rules are listed above the fields as the person
 * types and **Create service** or **Save changes** stays disabled while they last; **Delete service** for an existing
 * one. The page's notices (a conflict and its Reload, a refused rule) are shown in the sheet while it is open. From
 * F05-S02 a service can have one set of variants instead of its own price and time, and More options ends with
 * Show in Popular and its Deposit. The prototype's pricing kinds, further option groups, processing time and patch
 * test are F17's and not shown.
 */
export function ServiceSheet(props: Props) {
  const { open, isNew, values, problems, canSave, saving, categories, currency, onChange } = props;
  const t = useTranslations('services.sheet');
  // The prototype opens "More options" for an existing service with something set there.
  const [more, setMore] = useState(!isNew);
  // A photo upload is a change of this service too: Save and Delete wait for it (Close does not).
  const working = saving || props.photoUpload.state === 'uploading';
  const invalid = (field: keyof ServiceValues) =>
    problems.some((problem) => {
      const { key, index } = problemParts(problem);
      return index === null && FIELD_OF[key as keyof typeof FIELD_OF] === field;
    });
  const lowest = lowestPrice(values);
  const highest = highestPrice(values);
  const fromPrice =
    lowest === null || highest === null
      ? null
      : lowest < highest
        ? t('fromPrice', { price: formatMoney(lowest * MINOR_PER_RUPEE, currency) })
        : formatMoney(lowest * MINOR_PER_RUPEE, currency);
  const aria = (field: keyof ServiceValues, help?: string) => ({
    'aria-invalid': invalid(field) || undefined,
    'aria-describedby': [help, invalid(field) ? PROBLEMS_ID : undefined].filter(Boolean).join(' ') || undefined,
  });
  const number = (field: 'price' | 'minutes' | 'buffer') => ({
    inputMode: 'numeric' as const,
    value: values[field],
    onChange: (event: React.ChangeEvent<HTMLInputElement>) => onChange(field, event.target.value),
  });
  return (
    <Sheet open={open} onOpenChange={(next) => !next && props.onClose()}>
      <SheetContent
        side="bottom"
        className={cn(SHEET_CLASSES)}
        data-testid="service-sheet"
        aria-describedby={undefined}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          props.onClosed();
        }}
      >
        <div className="flex items-center justify-between gap-3">
          <SheetTitle className="rail:text-workspace-title">{isNew ? t('new') : t('edit')}</SheetTitle>
          <SheetClose asChild>
            <Button variant="outline" size="sm" data-testid="service-sheet-close">
              {t('close')}
            </Button>
          </SheetClose>
        </div>
        {props.notices}
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (canSave && !working) props.onSave();
          }}
        >
          {problems.length > 0 && (
            <Alert variant="destructive" role="alert" id={PROBLEMS_ID} data-testid="service-sheet-problems">
              <AlertTitle className="sr-only">{t('problemsTitle')}</AlertTitle>
              <AlertDescription>
                <ul className="flex flex-col gap-1">
                  {problems.slice(0, SHOWN).map((problem) => {
                    const { key, index } = problemParts(problem);
                    const name =
                      index === null
                        ? ''
                        : values.variants[index]?.name.trim() || t('variants.option', { n: index + 1 });
                    return (
                      <li
                        key={problem}
                        data-testid="service-sheet-problem"
                        data-problem={key}
                        data-index={index ?? undefined}
                      >
                        {t(`problems.${key}`, { name })}
                      </li>
                    );
                  })}
                  {problems.length > SHOWN && <li>{t('more_problems', { count: problems.length - SHOWN })}</li>}
                </ul>
              </AlertDescription>
            </Alert>
          )}
          {!values.bookableOnline && (
            <Alert variant="notice" data-testid="service-sheet-hidden">
              {t('hiddenWarning')}
            </Alert>
          )}
          <Field>
            <FieldLabel htmlFor="service-name">{t('name')}</FieldLabel>
            <Input
              id="service-name"
              maxLength={40}
              placeholder={t('namePlaceholder')}
              value={values.name}
              onChange={(event) => onChange('name', event.target.value)}
              {...aria('name')}
              data-testid="service-sheet-name"
            />
          </Field>
          {values.hasVariants ? (
            <Alert variant="info" className="text-small" data-testid="service-sheet-variant-price">
              {fromPrice ? t('variantPrice', { price: fromPrice }) : t('variantPriceUnknown')}
            </Alert>
          ) : (
            <div className="grid grid-cols-2 gap-3">
              <Field>
                <FieldLabel htmlFor="service-price">{t('price', { mark: currencyMark(currency) })}</FieldLabel>
                <Input id="service-price" {...number('price')} {...aria('price')} data-testid="service-sheet-price" />
              </Field>
              <Field>
                <FieldLabel htmlFor="service-minutes">{t('time')}</FieldLabel>
                <Input
                  id="service-minutes"
                  {...number('minutes')}
                  {...aria('minutes')}
                  data-testid="service-sheet-time"
                />
              </Field>
            </div>
          )}
          <VariantsSection
            values={values}
            problems={problems}
            currency={currency}
            problemsId={PROBLEMS_ID}
            onValues={props.onValues}
          />
          <Collapsible open={more} onOpenChange={setMore} className="rounded-card border border-border">
            <CollapsibleTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                className="h-auto min-h-touch w-full justify-between rounded-card px-4 py-3 text-left whitespace-normal text-foreground"
                data-testid="service-sheet-more"
              >
                <span className="flex flex-col items-start">
                  <span className="font-semibold">{t('more')}</span>
                  {/* Ink, not muted: the sheet is the warm canvas, where muted small text is under 4.5:1. */}
                  <span className="text-small font-normal text-foreground">{t('moreHint')}</span>
                </span>
                <ChevronDown aria-hidden="true" className={cn('transition-transform', more && 'rotate-180')} />
              </Button>
            </CollapsibleTrigger>
            <CollapsibleContent className="flex flex-col gap-4 px-4 pt-1 pb-4" data-testid="service-sheet-more-content">
              <Field>
                <FieldLabel htmlFor="service-category">{t('category')}</FieldLabel>
                <Select value={values.categoryId} onValueChange={(value) => onChange('categoryId', value)}>
                  <SelectTrigger
                    id="service-category"
                    className="w-full"
                    {...aria('categoryId')}
                    data-testid="service-sheet-category"
                  >
                    <SelectValue placeholder={t('categoryPlaceholder')} />
                  </SelectTrigger>
                  <SelectContent position="popper" sideOffset={4}>
                    {categories.map((category) => (
                      <SelectItem
                        key={category.id}
                        value={category.id}
                        data-testid={`service-sheet-category-option-${category.id}`}
                      >
                        {category.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              <Field>
                <FieldLabel htmlFor="service-description">{t('description')}</FieldLabel>
                <Input
                  id="service-description"
                  maxLength={90}
                  value={values.description}
                  onChange={(event) => onChange('description', event.target.value)}
                  {...aria('description')}
                  data-testid="service-sheet-description"
                />
              </Field>
              <Field>
                <FieldLabel htmlFor="service-buffer">{t('buffer')}</FieldLabel>
                <Input
                  id="service-buffer"
                  {...number('buffer')}
                  {...aria('buffer', 'service-buffer-help')}
                  data-testid="service-sheet-buffer"
                />
                <FieldDescription id="service-buffer-help">{t('bufferHelp')}</FieldDescription>
              </Field>
              <PhotoField
                photoId={props.photoId}
                isNew={isNew}
                upload={props.photoUpload}
                disabled={saving}
                onChoose={props.onPhoto}
                onRemove={props.onRemovePhoto}
              />
              <Field orientation="horizontal" className="min-h-touch">
                <Switch
                  id="service-online"
                  checked={values.bookableOnline}
                  onCheckedChange={(checked) => onChange('bookableOnline', checked)}
                  aria-describedby="service-online-help"
                  data-testid="service-sheet-online"
                />
                <FieldContent>
                  <FieldLabel htmlFor="service-online">{t('online')}</FieldLabel>
                  <FieldDescription id="service-online-help">{t('onlineHelp')}</FieldDescription>
                </FieldContent>
              </Field>
              <PopularField
                popular={values.popular}
                mode={props.popularMode}
                chosenElsewhere={props.chosenElsewhere}
                onChange={(popular) => onChange('popular', popular)}
              />
              <DepositField
                deposit={values.deposit}
                currency={currency}
                salonRule={props.salonRule}
                invalid={invalid('deposit')}
                problemsId={PROBLEMS_ID}
                onValues={props.onValues}
              />
            </CollapsibleContent>
          </Collapsible>
          <Button type="submit" className="w-full" disabled={!canSave || working} data-testid="service-sheet-save">
            {saving && <Spinner />}
            {saving ? t('saving') : isNew ? t('create') : t('save')}
          </Button>
          {!isNew && (
            <Button
              type="button"
              variant="destructive"
              className="w-full"
              disabled={working}
              onClick={props.onDelete}
              data-testid="service-sheet-delete"
            >
              {t('delete')}
            </Button>
          )}
        </form>
      </SheetContent>
    </Sheet>
  );
}
