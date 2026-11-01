import { formatPhoneNational } from '@salon/format';
import type { ProfileDraft, ProfileSave } from '../types';

/**
 * The Profile tab's form (F04-S01): every field as the person types it. Phone numbers are typed and
 * shown in the national format; the map position is one value, `6.9061, 79.8611`.
 */
export type ProfileForm = {
  address: string;
  location: string;
  phone: string;
  whatsapp: string;
  description: string;
  instagram: string;
  facebook: string;
  website: string;
};

export type ProfileField = keyof ProfileForm;

const PROFILE_FIELDS: ProfileField[] = [
  'phone',
  'whatsapp',
  'address',
  'location',
  'description',
  'instagram',
  'facebook',
  'website',
];

/** The description's limit, the API's rule; the count beside the field shows it. */
export const DESCRIPTION_LENGTH = 500;

type Branch = ProfileDraft['profile']['branches'][number];

/** The branch the Profile tab edits: the salon's default branch (the only one in R1). */
function defaultBranch(draft: ProfileDraft): Branch | undefined {
  return draft.profile.branches.find((branch) => branch.isDefault) ?? draft.profile.branches[0];
}

/** The form filled from a draft the API answered. */
export function formOf(draft: ProfileDraft): ProfileForm {
  const branch = defaultBranch(draft);
  const location = branch?.location;
  return {
    address: branch?.address ?? '',
    location: location ? `${location.latitude}, ${location.longitude}` : '',
    phone: branch?.phone ? formatPhoneNational(branch.phone) : '',
    whatsapp: branch?.whatsapp ? formatPhoneNational(branch.whatsapp) : '',
    description: draft.profile.description ?? '',
    instagram: draft.profile.links.instagram ?? '',
    facebook: draft.profile.links.facebook ?? '',
    website: draft.profile.links.website ?? '',
  };
}

/** Whether two forms hold the same values, as the person sees them. */
export function sameForm(a: ProfileForm, b: ProfileForm): boolean {
  return PROFILE_FIELDS.every((field) => a[field].trim() === b[field].trim());
}

/**
 * The map position typed as one value: two decimal numbers separated by a comma. Empty is no position;
 * anything else that is not two numbers is `invalid`, for the field's message. The range is the API's
 * rule, checked there.
 */
export function parseLocation(text: string): { latitude: number; longitude: number } | null | 'invalid' {
  const value = text.trim();
  if (value === '') return null;
  const match = /^(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)$/.exec(value);
  if (!match) return 'invalid';
  return { latitude: Number(match[1]), longitude: Number(match[2]) };
}

/** Google Maps at the typed position, for "Check on map"; null while the position is not two numbers. */
export function mapLink(text: string): string | null {
  const location = parseLocation(text);
  if (location === null || location === 'invalid') return null;
  return `https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}`;
}

/** The save the form makes against a draft, or the field the browser can already tell is wrong. */
export function saveOf(form: ProfileForm, draft: ProfileDraft): { save: ProfileSave } | { invalid: ProfileField } {
  const location = parseLocation(form.location);
  if (location === 'invalid') return { invalid: 'location' };
  const branch = defaultBranch(draft);
  return {
    save: {
      version: draft.version,
      profile: {
        description: form.description,
        links: { instagram: form.instagram, facebook: form.facebook, website: form.website },
        branches: branch
          ? [{ id: branch.id, address: form.address, location, phone: form.phone, whatsapp: form.whatsapp }]
          : [],
      },
    },
  };
}

/** The form field an API field error names (`profile.branches[0].phone` is `phone`); null for none. */
export function fieldOf(apiField: string): ProfileField | null {
  const links = /^profile\.links\.(instagram|facebook|website)$/.exec(apiField);
  if (links) return links[1] as ProfileField;
  if (apiField === 'profile.description') return 'description';
  const branch = /^profile\.branches\[\d+\]\.(address|location|phone|whatsapp)(?:\.\w+)?$/.exec(apiField);
  return branch ? (branch[1] as ProfileField) : null;
}
