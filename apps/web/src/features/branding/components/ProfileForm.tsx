'use client';
import { ExternalLinkIcon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Card } from '@/components/ui/card';
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import { Textarea } from '@/components/ui/textarea';
import { DESCRIPTION_LENGTH, mapLink, type ProfileField, type ProfileForm } from '../client/profile-form';

type Props = {
  form: ProfileForm;
  errors: Partial<Record<ProfileField, boolean>>;
  disabled: boolean;
  onChange: (field: ProfileField, value: string) => void;
};

const testId = (field: ProfileField) => `profile-${field}`;
const describedBy = (field: ProfileField, error: boolean, description: boolean) =>
  [description ? `${testId(field)}-description` : '', error ? `${testId(field)}-error` : '']
    .filter(Boolean)
    .join(' ') || undefined;

/** A field's label, with the prototype's muted hint in brackets beside it. */
function Label({ field, label, hint }: { field: ProfileField; label: string; hint?: string }) {
  const t = useTranslations('settings.profile');
  return (
    <FieldLabel htmlFor={testId(field)}>
      {label}
      {hint && <span className="font-normal text-muted-foreground">{t('hint', { hint })}</span>}
    </FieldLabel>
  );
}

/** The message beside a field the API (or the position's own form) refused. */
function Refused({ field, children }: { field: ProfileField; children: ReactNode }) {
  return (
    <FieldError id={`${testId(field)}-error`} data-testid={`${testId(field)}-error`}>
      {children}
    </FieldError>
  );
}

/**
 * The Profile tab's form (F04-S01), in the prototype's order: Contact (phone, WhatsApp), Location
 * (address, map position with "Check on map"), About (description with its count) and Social links.
 * Every field is optional; the API checks each, and a refused field shows its message beside it.
 */
export function ProfileForm({ form, errors, disabled, onChange }: Props) {
  const t = useTranslations('settings.profile');
  const text = (field: ProfileField, label: string, options: { hint?: string; placeholder?: string } = {}) => (
    <Field data-invalid={errors[field] || undefined}>
      <Label field={field} label={label} hint={options.hint} />
      <Input
        id={testId(field)}
        data-testid={testId(field)}
        value={form[field]}
        placeholder={options.placeholder}
        disabled={disabled}
        aria-invalid={Boolean(errors[field])}
        aria-describedby={describedBy(field, Boolean(errors[field]), false)}
        onChange={(event) => onChange(field, event.target.value)}
      />
      {errors[field] && <Refused field={field}>{t(`errors.${field}`)}</Refused>}
    </Field>
  );
  const map = mapLink(form.location);
  return (
    <Card className="gap-6" data-testid="profile-form">
      <FieldGroup>
        <h2 className="text-large font-semibold">{t('contact')}</h2>
        {text('phone', t('phone'), { hint: t('phoneHint'), placeholder: t('phonePlaceholder') })}
        {text('whatsapp', t('whatsapp'), { hint: t('whatsappHint'), placeholder: t('whatsappPlaceholder') })}
      </FieldGroup>
      <FieldGroup>
        <h2 className="text-large font-semibold">{t('location')}</h2>
        {text('address', t('address'), { placeholder: t('addressPlaceholder') })}
        <Field data-invalid={errors.location || undefined}>
          <Label field="location" label={t('mapPosition')} />
          <InputGroup>
            <InputGroupInput
              id={testId('location')}
              data-testid={testId('location')}
              value={form.location}
              placeholder={t('mapPlaceholder')}
              inputMode="decimal"
              disabled={disabled}
              aria-invalid={Boolean(errors.location)}
              aria-describedby={describedBy('location', Boolean(errors.location), true)}
              onChange={(event) => onChange('location', event.target.value)}
            />
            <InputGroupAddon align="inline-end">
              {map ? (
                <InputGroupButton asChild variant="link" data-testid="profile-check-map">
                  <a href={map} target="_blank" rel="noopener noreferrer" aria-label={t('checkOnMapLabel')}>
                    {t('checkOnMap')}
                    <ExternalLinkIcon aria-hidden="true" />
                  </a>
                </InputGroupButton>
              ) : (
                <InputGroupButton variant="link" disabled data-testid="profile-check-map">
                  {t('checkOnMap')}
                </InputGroupButton>
              )}
            </InputGroupAddon>
          </InputGroup>
          <FieldDescription id={`${testId('location')}-description`}>{t('mapPositionHelp')}</FieldDescription>
          {errors.location && <Refused field="location">{t('errors.location')}</Refused>}
        </Field>
      </FieldGroup>
      <FieldGroup>
        <h2 className="text-large font-semibold">{t('about')}</h2>
        <Field data-invalid={errors.description || undefined}>
          <Label field="description" label={t('description')} hint={t('descriptionHint')} />
          <Textarea
            id={testId('description')}
            data-testid={testId('description')}
            value={form.description}
            rows={3}
            maxLength={DESCRIPTION_LENGTH}
            disabled={disabled}
            aria-invalid={Boolean(errors.description)}
            aria-describedby={describedBy('description', Boolean(errors.description), true)}
            onChange={(event) => onChange('description', event.target.value)}
          />
          <FieldDescription id={`${testId('description')}-description`} data-testid="profile-description-count">
            {t('descriptionCount', { count: [...form.description].length, limit: DESCRIPTION_LENGTH })}
          </FieldDescription>
          {errors.description && <Refused field="description">{t('errors.description')}</Refused>}
        </Field>
      </FieldGroup>
      <FieldGroup>
        <h2 className="text-large font-semibold">{t('links')}</h2>
        {text('instagram', t('instagram'), { hint: t('optional'), placeholder: t('linkPlaceholder') })}
        {text('facebook', t('facebook'), { hint: t('optional'), placeholder: t('linkPlaceholder') })}
        {text('website', t('website'), { hint: t('optional'), placeholder: t('linkPlaceholder') })}
      </FieldGroup>
    </Card>
  );
}
