'use client';
import { useTranslations } from 'next-intl';
import { useRef } from 'react';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { Button } from '@/components/ui/button';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Spinner } from '@/components/ui/spinner';
import type { BrandImage } from '../client/brand-form';
import { brandImageUrl } from './BrandPreview';

type Props = {
  kind: 'logo' | 'cover';
  image: BrandImage | null;
  /** Where the choice is: idle, uploading, or refused with the API's reason code. */
  upload: { state: 'idle' } | { state: 'uploading' } | { state: 'refused'; reason: string };
  error: boolean;
  disabled: boolean;
  initial: string;
  onChoose: (file: File) => void;
  onRemove: () => void;
};

/**
 * Choosing a logo or a cover (F04-S02, web.md "Styling"): a Button that opens a visually hidden file input
 * inside a Field, whose label, description and error belong to it, with the chosen image shown, an uploading
 * state, the refusal beside it, and "Use default logo" or "Use default cover".
 */
export function ImageField({ kind, image, upload, error, disabled, initial, onChoose, onRemove }: Props) {
  const t = useTranslations('settings.brand');
  const input = useRef<HTMLInputElement>(null);
  const id = `brand-${kind}`;
  const refused = upload.state === 'refused' ? upload.reason : error ? 'bad_request' : null;
  const reason =
    refused === null
      ? null
      : refused === 'request_rejected' || refused === 'service_unavailable'
        ? refused
        : 'bad_request';
  return (
    <Field data-invalid={refused !== null || undefined}>
      <FieldLabel htmlFor={id} className="flex-row flex-wrap gap-x-1 gap-y-0">
        {t(`${kind}.label`)}
        <span className="font-normal text-muted-foreground">{t('hint', { hint: t(`${kind}.hint`) })}</span>
      </FieldLabel>
      <div className="flex flex-wrap items-center gap-3">
        {kind === 'logo' ? (
          <Avatar
            className="size-14 border border-border"
            data-testid="brand-logo-chosen"
            data-image={image?.id ?? 'none'}
          >
            {image && <AvatarImage src={brandImageUrl(image)} alt={t('logo.chosen')} />}
            <AvatarFallback className="bg-secondary font-semibold text-foreground">{initial}</AvatarFallback>
          </Avatar>
        ) : (
          <div
            className="h-14 w-28 shrink-0 rounded-field border border-border bg-secondary bg-[url(/brand/neutral-cover.svg)] bg-cover bg-center"
            style={image ? { backgroundImage: `url(${brandImageUrl(image)})` } : undefined}
            role="img"
            aria-label={image ? t('cover.chosen') : t('cover.neutral')}
            data-testid="brand-cover-chosen"
            data-image={image?.id ?? 'none'}
          />
        )}
        <Input
          ref={input}
          id={id}
          type="file"
          accept="image/png,image/jpeg,image/webp"
          className="sr-only"
          tabIndex={-1}
          disabled={disabled || upload.state === 'uploading'}
          aria-describedby={`${id}-description${reason ? ` ${id}-error` : ''}`}
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = '';
            if (file) onChoose(file);
          }}
          data-testid={`${id}-file`}
        />
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled || upload.state === 'uploading'}
          aria-describedby={`${id}-description${reason ? ` ${id}-error` : ''}`}
          onClick={() => input.current?.click()}
          data-testid={`${id}-choose`}
        >
          {upload.state === 'uploading' && <Spinner />}
          {upload.state === 'uploading' ? t('uploading') : t(`${kind}.choose`)}
        </Button>
        {image && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={disabled || upload.state === 'uploading'}
            onClick={onRemove}
            data-testid={`${id}-default`}
          >
            {t(`${kind}.useDefault`)}
          </Button>
        )}
      </div>
      <FieldDescription id={`${id}-description`}>{t(`${kind}.rules`)}</FieldDescription>
      {upload.state === 'uploading' && (
        <p className="sr-only" role="status">
          {t('uploading')}
        </p>
      )}
      {reason && (
        <FieldError id={`${id}-error`} data-testid={`${id}-error`}>
          {t(`imageErrors.${reason}` as 'imageErrors.bad_request', { rules: t(`${kind}.rules`) })}
        </FieldError>
      )}
    </Field>
  );
}
