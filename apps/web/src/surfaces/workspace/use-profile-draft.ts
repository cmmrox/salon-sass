'use client';
import {
  fieldOf,
  formOf,
  sameForm,
  saveOf,
  type ProfileDraft,
  type ProfileField,
  type ProfileFormValues,
} from '@/features/branding';
import type { Kind } from '@/features/identity';
import { useSettingsDraft, type SettingsDraftShape } from './use-settings-draft';

const PROFILE: SettingsDraftShape<ProfileDraft, ProfileFormValues, ProfileField> = {
  subject: 'profile',
  formOf,
  sameForm,
  saveOf,
  fieldOf,
};

/** The Profile tab's state (F04-S01): the settings draft pattern for the business profile. */
export function useProfileDraft(kind: Kind, notify: (event: 'saved' | 'published' | 'discarded' | 'reset') => void) {
  const profile = useSettingsDraft(kind, PROFILE, notify);
  return {
    ...profile,
    edit: (field: ProfileField, value: string) =>
      profile.setForm((current) => current && { ...current, [field]: value }),
  };
}
