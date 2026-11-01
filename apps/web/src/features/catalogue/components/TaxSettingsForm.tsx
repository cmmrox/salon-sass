'use client';
import { useTranslations } from 'next-intl';
import { Field, FieldContent, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import type { TaxForm, TaxProblem } from '../client/tax-form';
import type { TaxSettings } from '../types';

type Props = {
  form: TaxForm;
  problems: TaxProblem[];
  configured: TaxSettings['configured'];
  disabled: boolean;
  onChange: <K extends keyof TaxForm>(field: K, value: TaxForm[K]) => void;
};

/** A labelled Switch with its description, as the prototype's tick rows (`.row`). */
function SwitchField(props: {
  id: string;
  label: string;
  help: string;
  checked: boolean;
  disabled: boolean;
  onChange: (checked: boolean) => void;
  testId: string;
}) {
  return (
    <Field orientation="horizontal" className="min-h-touch" data-disabled={props.disabled || undefined}>
      <Switch
        id={props.id}
        checked={props.checked}
        disabled={props.disabled}
        onCheckedChange={props.onChange}
        aria-describedby={`${props.id}-help`}
        data-testid={props.testId}
      />
      <FieldContent>
        <FieldLabel htmlFor={props.id}>{props.label}</FieldLabel>
        <FieldDescription id={`${props.id}-help`} className="text-foreground">
          {props.help}
        </FieldDescription>
      </FieldContent>
    </Field>
  );
}

/**
 * The Tax & charges form (F05-S03 AC-2, the prototype's `setTax`): VAT under its configured name with "My salon is
 * registered for VAT" and, while on, the registration number; the service charge with "Charge a service charge" and,
 * while on, its rate and "Also on products". A broken rule is said beside its field.
 */
export function TaxSettingsForm({ form, problems, configured, disabled, onChange }: Props) {
  const t = useTranslations('settings.tax');
  const vat = configured.vatName;
  const serviceCharge = configured.serviceChargeName;
  const numberInvalid = problems.includes('registrationNumber');
  const percentInvalid = problems.includes('percent');
  return (
    <div className="flex flex-col gap-6">
      <section className="flex flex-col gap-3" aria-labelledby="tax-vat-title" data-testid="tax-vat">
        <h2 id="tax-vat-title" className="m-0 text-large font-semibold">
          {vat}
        </h2>
        <SwitchField
          id="tax-vat-registered"
          label={t('registered', { vat })}
          help={t('registeredHelp', { rate: configured.vatRatePercent })}
          checked={form.vatRegistered}
          disabled={disabled}
          onChange={(checked) => onChange('vatRegistered', checked)}
          testId="tax-vat-registered"
        />
        {form.vatRegistered && (
          <Field data-invalid={numberInvalid || undefined}>
            <FieldLabel htmlFor="tax-vat-number">{configured.registrationLabel}</FieldLabel>
            <Input
              id="tax-vat-number"
              maxLength={20}
              autoComplete="off"
              placeholder={t('registrationPlaceholder')}
              value={form.registrationNumber}
              disabled={disabled}
              onChange={(event) => onChange('registrationNumber', event.target.value)}
              aria-invalid={numberInvalid || undefined}
              aria-describedby={numberInvalid ? 'tax-vat-number-error' : undefined}
              data-testid="tax-vat-number"
            />
            {numberInvalid && (
              <FieldError id="tax-vat-number-error" data-testid="tax-vat-number-error">
                {t('registrationNumber', { label: configured.registrationLabel })}
              </FieldError>
            )}
          </Field>
        )}
      </section>
      <section className="flex flex-col gap-3" aria-labelledby="tax-charge-title" data-testid="tax-service-charge">
        <h2 id="tax-charge-title" className="m-0 text-large font-semibold">
          {serviceCharge}
        </h2>
        <SwitchField
          id="tax-charge-on"
          label={t('charge', { serviceCharge: serviceCharge.toLowerCase() })}
          help={t('chargeHelp')}
          checked={form.serviceChargeOn}
          disabled={disabled}
          onChange={(checked) => onChange('serviceChargeOn', checked)}
          testId="tax-charge-on"
        />
        {form.serviceChargeOn && (
          <>
            <Field data-invalid={percentInvalid || undefined}>
              <FieldLabel htmlFor="tax-charge-percent">{t('rate')}</FieldLabel>
              <Input
                id="tax-charge-percent"
                inputMode="numeric"
                autoComplete="off"
                className="max-w-36"
                value={form.percent}
                disabled={disabled}
                onChange={(event) => onChange('percent', event.target.value)}
                aria-invalid={percentInvalid || undefined}
                aria-describedby={
                  percentInvalid ? 'tax-charge-percent-help tax-charge-percent-error' : 'tax-charge-percent-help'
                }
                data-testid="tax-charge-percent"
              />
              <FieldDescription id="tax-charge-percent-help" className="text-foreground">
                {t('rateHelp', { max: configured.serviceChargeMaxPercent })}
              </FieldDescription>
              {percentInvalid && (
                <FieldError id="tax-charge-percent-error" data-testid="tax-charge-percent-error">
                  {t('percent', { max: configured.serviceChargeMaxPercent })}
                </FieldError>
              )}
            </Field>
            <SwitchField
              id="tax-charge-products"
              label={t('onProducts')}
              help={t('onProductsHelp', { vat })}
              checked={form.onProducts}
              disabled={disabled}
              onChange={(checked) => onChange('onProducts', checked)}
              testId="tax-charge-products"
            />
          </>
        )}
      </section>
    </div>
  );
}
