'use client';
import { useTranslations } from 'next-intl';
import { useRef } from 'react';
import { Button } from '@/components/ui/button';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Spinner } from '@/components/ui/spinner';
import { MemberAvatar } from './TeamCards';

/** Where a photo upload is: idle, uploading, or refused with the API's reason code. */
export type MemberPhotoUpload = { state: 'idle' } | { state: 'uploading' } | { state: 'refused'; reason: string };

type Props = {
  memberId: string;
  name: string;
  photoId: string | null;
  isNew: boolean;
  upload: MemberPhotoUpload;
  disabled: boolean;
  onChoose: (file: File) => void;
  onRemove: () => void;
};

/**
 * A person's photo (F06-S01 AC-9; web.md "Styling", choosing a file): a Button that opens a visually hidden file input
 * inside a Field, whose label, rules and error belong to it, with the photo or the person's initial shown. On an
 * existing person a chosen file is uploaded at once and Remove applies at once; a new person gets one after they are
 * added.
 */
export function MemberPhotoField({ memberId, name, photoId, isNew, upload, disabled, onChoose, onRemove }: Props) {
  const t = useTranslations('team.sheet.photo');
  const input = useRef<HTMLInputElement>(null);
  const id = 'team-photo';
  const reason =
    upload.state !== 'refused'
      ? null
      : upload.reason === 'request_rejected' || upload.reason === 'service_unavailable'
        ? upload.reason
        : 'bad_request';
  const busy = disabled || upload.state === 'uploading';
  const describedBy = `${id}-description${reason ? ` ${id}-error` : ''}`;
  const person = name.trim() || t('thisPerson');
  return (
    <Field data-invalid={reason !== null || undefined}>
      <FieldLabel htmlFor={isNew ? undefined : id}>{t('label')}</FieldLabel>
      <div className="flex flex-wrap items-center gap-3">
        <MemberAvatar id={memberId} name={name || '?'} photoId={photoId} className="size-21" />
        {isNew ? (
          <FieldDescription id={`${id}-description`} className="min-w-0 flex-1" data-testid="team-photo-after-add">
            {t('afterAdd')}
          </FieldDescription>
        ) : (
          <>
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
              data-testid="team-photo-file"
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={busy}
              aria-describedby={describedBy}
              aria-label={photoId ? t('changeLabel', { name: person }) : t('uploadLabel', { name: person })}
              onClick={() => input.current?.click()}
              data-testid="team-photo-choose"
            >
              {upload.state === 'uploading' && <Spinner />}
              {upload.state === 'uploading' ? t('uploading') : photoId ? t('change') : t('upload')}
            </Button>
            {photoId && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={busy}
                aria-label={t('removeLabel', { name: person })}
                onClick={onRemove}
                data-testid="team-photo-remove"
              >
                {t('remove')}
              </Button>
            )}
          </>
        )}
      </div>
      {!isNew && <FieldDescription id={`${id}-description`}>{t('rules')}</FieldDescription>}
      {upload.state === 'uploading' && (
        <p className="sr-only" role="status">
          {t('uploading')}
        </p>
      )}
      {reason && (
        <FieldError id={`${id}-error`} data-testid="team-photo-error">
          {t(`errors.${reason}` as 'errors.bad_request', { rules: t('rules') })}
        </FieldError>
      )}
    </Field>
  );
}
