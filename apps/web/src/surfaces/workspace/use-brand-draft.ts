'use client';
import { useState } from 'react';
import {
  brandFieldOf,
  brandFormOf,
  brandSaveOf,
  sameBrandForm,
  type BrandDraft,
  type BrandField,
  type BrandFormValues,
  type UploadedBrandImage,
} from '@/features/branding';
import { IdentityFailure, type Kind } from '@/features/identity';
import { useSettingsDraft, type SettingsDraftShape } from './use-settings-draft';

const BRAND: SettingsDraftShape<BrandDraft, BrandFormValues, BrandField> = {
  subject: 'brand',
  formOf: brandFormOf,
  sameForm: sameBrandForm,
  saveOf: (form, draft) => ({ save: brandSaveOf(form, draft) }),
  fieldOf: brandFieldOf,
};

export type ImageKind = 'logo' | 'cover';

/** Where an image choice is: idle, uploading, or refused with the API's reason (a field error or an outage). */
export type UploadState = { state: 'idle' } | { state: 'uploading' } | { state: 'refused'; reason: string };

/**
 * The Branding tab's state (F04-S02): the settings draft pattern for the brand, with its reset, and its two
 * image choices. Uploading keeps an image without changing the draft; the image joins the form, and so
 * the draft when it is saved. An uploaded image not yet saved counts as an unsaved edit.
 */
export function useBrandDraft(kind: Kind, notify: (event: 'saved' | 'published' | 'discarded' | 'reset') => void) {
  const brand = useSettingsDraft(kind, BRAND, notify);
  const [uploads, setUploads] = useState<Record<ImageKind, UploadState>>({
    logo: { state: 'idle' },
    cover: { state: 'idle' },
  });

  async function upload(image: ImageKind, file: File) {
    setUploads((current) => ({ ...current, [image]: { state: 'uploading' } }));
    const form = new FormData();
    form.append('kind', image);
    form.append('file', file);
    try {
      const kept = await brand.request<UploadedBrandImage>('brand-image', form);
      brand.setForm(
        (current) => current && { ...current, [image]: { id: kept.id, width: kept.width, height: kept.height } },
      );
      setUploads((current) => ({ ...current, [image]: { state: 'idle' } }));
    } catch (error) {
      const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
      if (
        failure.code === 'invalid_session' ||
        failure.code === 'support_read_only' ||
        failure.code === 'salon_read_only'
      ) {
        brand.failed(failure);
        setUploads((current) => ({ ...current, [image]: { state: 'idle' } }));
        return;
      }
      setUploads((current) => ({ ...current, [image]: { state: 'refused', reason: failure.code } }));
    }
  }

  return {
    ...brand,
    uploads,
    upload,
    edit: (field: 'name' | 'tagline' | 'colour', value: string) =>
      brand.setForm((current) => current && { ...current, [field]: value }),
    removeImage: (image: ImageKind) => {
      brand.setForm((current) => current && { ...current, [image]: null });
      setUploads((current) => ({ ...current, [image]: { state: 'idle' } }));
    },
  };
}
