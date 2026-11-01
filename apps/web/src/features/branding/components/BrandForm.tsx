'use client';
import { useTranslations } from 'next-intl';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Field, FieldError, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { NAME_LENGTH, TAGLINE_LENGTH, type BrandField, type BrandFormValues } from '../client/brand-form';
import { ColourField } from './ColourField';
import { ImageField } from './ImageField';

type Upload = { state: 'idle' } | { state: 'uploading' } | { state: 'refused'; reason: string };

type Props = {
  form: BrandFormValues;
  errors: Partial<Record<BrandField, boolean>>;
  uploads: Record<'logo' | 'cover', Upload>;
  disabled: boolean;
  onEdit: (field: 'name' | 'tagline' | 'colour', value: string) => void;
  onChoose: (kind: 'logo' | 'cover', file: File) => void;
  onRemove: (kind: 'logo' | 'cover') => void;
  onReset: () => void;
};

/**
 * The Branding tab's form (F04-S02), in the prototype's order: Salon details (name, tagline), Images (logo,
 * cover), Brand colour (swatches and `#RRGGBB`), and Reset to default branding, confirmed in an Alert Dialog.
 */
export function BrandForm({ form, errors, uploads, disabled, onEdit, onChoose, onRemove, onReset }: Props) {
  const t = useTranslations('settings.brand');
  const text = (field: 'name' | 'tagline', limit: number) => (
    <Field data-invalid={errors[field] || undefined}>
      <FieldLabel htmlFor={`brand-${field}`}>{t(field)}</FieldLabel>
      <Input
        id={`brand-${field}`}
        data-testid={`brand-${field}`}
        value={form[field]}
        maxLength={limit}
        disabled={disabled}
        aria-invalid={Boolean(errors[field])}
        aria-describedby={errors[field] ? `brand-${field}-error` : undefined}
        onChange={(event) => onEdit(field, event.target.value)}
      />
      {errors[field] && (
        <FieldError id={`brand-${field}-error`} data-testid={`brand-${field}-error`}>
          {t(`errors.${field}`)}
        </FieldError>
      )}
    </Field>
  );
  const initial = Array.from(form.name.trim())[0] ?? '';
  return (
    <Card className="gap-6 p-4 rail:p-6" data-testid="brand-form">
      <FieldGroup>
        <h2 className="text-large font-semibold">{t('details')}</h2>
        {text('name', NAME_LENGTH)}
        {text('tagline', TAGLINE_LENGTH)}
      </FieldGroup>
      <FieldGroup>
        <h2 className="text-large font-semibold">{t('images')}</h2>
        {(['logo', 'cover'] as const).map((kind) => (
          <ImageField
            key={kind}
            kind={kind}
            image={form[kind]}
            upload={uploads[kind]}
            error={Boolean(errors[kind])}
            disabled={disabled}
            initial={initial}
            onChoose={(file) => onChoose(kind, file)}
            onRemove={() => onRemove(kind)}
          />
        ))}
      </FieldGroup>
      <FieldGroup>
        <h2 className="text-large font-semibold">{t('colour')}</h2>
        <ColourField
          colour={form.colour}
          error={Boolean(errors.colour)}
          disabled={disabled}
          onChange={(colour) => onEdit('colour', colour)}
        />
      </FieldGroup>
      <AlertDialog>
        <AlertDialogTrigger asChild>
          <Button variant="outline" className="w-full" disabled={disabled} data-testid="brand-reset">
            {t('reset')}
          </Button>
        </AlertDialogTrigger>
        <AlertDialogContent data-testid="brand-reset-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>{t('resetTitle')}</AlertDialogTitle>
            <AlertDialogDescription>{t('resetBody')}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel data-testid="brand-reset-cancel">{t('keep')}</AlertDialogCancel>
            <AlertDialogAction onClick={onReset} data-testid="brand-reset-confirm">
              {t('reset')}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}
