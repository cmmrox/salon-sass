'use client';
import { useTranslations } from 'next-intl';
import { useRef } from 'react';
import { Button } from '@/components/ui/button';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Spinner } from '@/components/ui/spinner';
import { photoSource } from './photo-source';

/** Where a photo upload is: idle, uploading, or refused with the API's reason code. */
export type PhotoUpload = { state: 'idle' } | { state: 'uploading' } | { state: 'refused'; reason: string };

type Props = {
  /** The service's photo, or null; a new service has none and cannot have one until it is created. */
  photoId: string | null;
  isNew: boolean;
  upload: PhotoUpload;
  disabled: boolean;
  onChoose: (file: File) => void;
  onRemove: () => void;
};

/**
 * A service's photo (F05-S01 AC-10; web.md "Styling", choosing a file): a Button that opens a visually hidden file
 * input inside a Field, whose label, rules and error belong to it, with the photo shown. On an existing service a
 * chosen file is uploaded at once, with a spinner, and Remove photo applies at once; a new service says to add one
 * after it is created.
 */
export function PhotoField({ photoId, isNew, upload, disabled, onChoose, onRemove }: Props) {
  const t = useTranslations('services.photo');
  const input = useRef<HTMLInputElement>(null);
  const id = 'service-photo';
  const reason =
    upload.state !== 'refused'
      ? null
      : upload.reason === 'request_rejected' || upload.reason === 'service_unavailable'
        ? upload.reason
        : 'bad_request';
  const busy = disabled || upload.state === 'uploading';
  const describedBy = `${id}-description${reason ? ` ${id}-error` : ''}`;
  return (
    <Field data-invalid={reason !== null || undefined}>
      <FieldLabel htmlFor={isNew ? undefined : id} className="flex-row flex-wrap gap-x-1 gap-y-0">
        {t('label')}
        {/* Ink, not muted: the sheet is the warm canvas, where muted small text is under 4.5:1. */}
        <span className="font-normal text-foreground">{t('optional')}</span>
      </FieldLabel>
      {isNew ? (
        <FieldDescription id={`${id}-description`} data-testid="service-photo-after-create">
          {t('afterCreate')}
        </FieldDescription>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-3">
            {photoId && (
              // A photo the salon uploaded, already cropped square by the API.
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={photoSource(photoId)}
                alt={t('chosen')}
                className="size-18 rounded-field object-cover"
                data-testid="service-photo-preview"
              />
            )}
            <Input
              ref={input}
              id={id}
              type="file"
              accept="image/png,image/jpeg,image/webp"
              // Input's own width and height would otherwise outlast sr-only and widen the page.
              className="sr-only size-px min-h-0 min-w-0 p-0"
              tabIndex={-1}
              disabled={busy}
              aria-describedby={describedBy}
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = '';
                if (file) onChoose(file);
              }}
              data-testid="service-photo-file"
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={busy}
              aria-describedby={describedBy}
              onClick={() => input.current?.click()}
              data-testid="service-photo-choose"
            >
              {upload.state === 'uploading' && <Spinner />}
              {upload.state === 'uploading' ? t('uploading') : photoId ? t('replace') : t('choose')}
            </Button>
            {photoId && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={busy}
                onClick={onRemove}
                data-testid="service-photo-remove"
              >
                {t('remove')}
              </Button>
            )}
          </div>
          <FieldDescription id={`${id}-description`}>{t('rules')}</FieldDescription>
          {upload.state === 'uploading' && (
            <p className="sr-only" role="status">
              {t('uploading')}
            </p>
          )}
          {reason && (
            <FieldError id={`${id}-error`} data-testid="service-photo-error">
              {t(`errors.${reason}` as 'errors.bad_request', { rules: t('rules') })}
            </FieldError>
          )}
        </>
      )}
    </Field>
  );
}
