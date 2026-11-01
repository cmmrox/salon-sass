'use client';
import { currencyMark } from '@salon/format';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field, FieldContent, FieldDescription, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Sheet, SheetClose, SheetContent, SheetTitle } from '@/components/ui/sheet';
import { Spinner } from '@/components/ui/spinner';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import { productPriceLimits, type ProductProblem, type ProductValues } from '../client/product-form';
import type { PublicTax } from '../types';
import { SHEET_CLASSES } from './sheet-classes';

type Props = {
  open: boolean;
  isNew: boolean;
  values: ProductValues;
  /** The problems shown: the prototype's `peErr` messages. */
  problems: ProductProblem[];
  canSave: boolean;
  saving: boolean;
  currency: string;
  /** The salon's current VAT; null while it is off, when "Charge VAT" is not shown. */
  vat: NonNullable<PublicTax['vat']> | null;
  onChange: <K extends keyof ProductValues>(field: K, value: ProductValues[K]) => void;
  onSave: () => void;
  onClose: () => void;
  /** Where focus goes when the sheet has closed: what opened it, as it has no trigger of its own. */
  onClosed: () => void;
  onDelete: () => void;
  /** What the page says while the sheet is open (a conflict and its Reload, a refusal), drawn inside the sheet. */
  notices?: ReactNode;
};

const PROBLEMS_ID = 'product-problems';
const FIELD: Record<ProductProblem, 'name' | 'price'> = { name: 'name', taken: 'name', price: 'price' };

/**
 * The prototype's product sheet (`pePaint`, F05-S03 AC-8): the name and price; "Charge VAT" while VAT is on (the
 * choice is kept while it is off, and the sheet says VAT is off); "Sell at checkout"; Add product or Save changes,
 * disabled while a rule is broken; Delete product for an existing one. The broken rules are listed above the fields.
 */
export function ProductSheet(props: Props) {
  const { open, isNew, values, problems, canSave, saving, currency, vat, onChange } = props;
  const t = useTranslations('services.productSheet');
  const aria = (field: 'name' | 'price') => {
    const invalid = problems.some((problem) => FIELD[problem] === field);
    return { 'aria-invalid': invalid || undefined, 'aria-describedby': invalid ? PROBLEMS_ID : undefined };
  };
  return (
    <Sheet open={open} onOpenChange={(next) => !next && props.onClose()}>
      <SheetContent
        side="bottom"
        className={cn(SHEET_CLASSES)}
        data-testid="product-sheet"
        aria-describedby={undefined}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          props.onClosed();
        }}
      >
        <div className="flex items-center justify-between gap-3">
          <SheetTitle className="rail:text-workspace-title">{isNew ? t('new') : t('edit')}</SheetTitle>
          <SheetClose asChild>
            <Button variant="outline" size="sm" data-testid="product-sheet-close">
              {t('close')}
            </Button>
          </SheetClose>
        </div>
        {props.notices}
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (canSave) props.onSave();
          }}
        >
          {problems.length > 0 && (
            <Alert variant="destructive" role="alert" id={PROBLEMS_ID} data-testid="product-sheet-problems">
              <AlertTitle className="sr-only">{t('problemsTitle')}</AlertTitle>
              <AlertDescription>
                <ul className="flex flex-col gap-1">
                  {problems.map((problem) => (
                    <li key={problem} data-testid="product-sheet-problem" data-problem={problem}>
                      {t(`problems.${problem}`, productPriceLimits(currency))}
                    </li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}
          <Field>
            <FieldLabel htmlFor="product-name">{t('name')}</FieldLabel>
            <Input
              id="product-name"
              maxLength={40}
              placeholder={t('namePlaceholder')}
              value={values.name}
              onChange={(event) => onChange('name', event.target.value)}
              {...aria('name')}
              data-testid="product-sheet-name"
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="product-price">{t('price', { mark: currencyMark(currency) })}</FieldLabel>
            <Input
              id="product-price"
              inputMode="numeric"
              value={values.price}
              onChange={(event) => onChange('price', event.target.value)}
              {...aria('price')}
              data-testid="product-sheet-price"
            />
          </Field>
          {vat ? (
            <Field orientation="horizontal" className="min-h-touch">
              <Switch
                id="product-vat"
                checked={values.chargesVat}
                onCheckedChange={(checked) => onChange('chargesVat', checked)}
                aria-describedby="product-vat-help"
                data-testid="product-sheet-vat"
              />
              <FieldContent>
                <FieldLabel htmlFor="product-vat">{t('chargesVat', { vat: vat.name })}</FieldLabel>
                <FieldDescription id="product-vat-help" className="text-foreground">
                  {t('chargesVatHelp', { vat: vat.name, rate: vat.ratePercent })}
                </FieldDescription>
              </FieldContent>
            </Field>
          ) : (
            <p className="m-0 text-small text-foreground" data-testid="product-sheet-vat-off">
              {t('vatOff')}
            </p>
          )}
          <Field orientation="horizontal" className="min-h-touch">
            <Switch
              id="product-sold"
              checked={values.soldAtCheckout}
              onCheckedChange={(checked) => onChange('soldAtCheckout', checked)}
              aria-describedby="product-sold-help"
              data-testid="product-sheet-sold"
            />
            <FieldContent>
              <FieldLabel htmlFor="product-sold">{t('sold')}</FieldLabel>
              <FieldDescription id="product-sold-help" className="text-foreground">
                {t('soldHelp')}
              </FieldDescription>
            </FieldContent>
          </Field>
          <Button type="submit" className="mt-2 w-full" disabled={!canSave || saving} data-testid="product-sheet-save">
            {saving && <Spinner />}
            {saving ? t('saving') : isNew ? t('add') : t('save')}
          </Button>
          {!isNew && (
            <Button
              type="button"
              variant="destructive"
              className="w-full"
              disabled={saving}
              onClick={props.onDelete}
              data-testid="product-sheet-delete"
            >
              {t('delete')}
            </Button>
          )}
        </form>
      </SheetContent>
    </Sheet>
  );
}
