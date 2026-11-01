import { isHexColour } from '@/styles/brand';
import type { BrandDraft } from '../types';

/** A brand image as the draft names it: its id and the size it was kept at. */
export type BrandImage = { id: string; width: number; height: number };

/**
 * The Branding tab's form (F04-S02): the name, tagline and colour as typed, and the images chosen (each one
 * the draft already uses, or one just uploaded, or none).
 */
export type BrandFormValues = {
  name: string;
  tagline: string;
  colour: string;
  logo: BrandImage | null;
  cover: BrandImage | null;
};

export type BrandField = 'name' | 'tagline' | 'colour' | 'logo' | 'cover';

/** The name's and tagline's limits, the API's rules; the inputs say so too. */
export const NAME_LENGTH = 60;
export const TAGLINE_LENGTH = 80;

export function brandFormOf(draft: BrandDraft): BrandFormValues {
  return {
    name: draft.brand.name,
    tagline: draft.brand.tagline ?? '',
    colour: draft.brand.colour,
    logo: draft.brand.logo,
    cover: draft.brand.cover,
  };
}

/** The colour as the API keeps it, or null while what is typed is not `#RRGGBB`. */
export function typedColour(text: string): string | null {
  const value = text.trim().toUpperCase();
  return isHexColour(value) ? value : null;
}

export function sameBrandForm(a: BrandFormValues, b: BrandFormValues): boolean {
  return (
    a.name.trim() === b.name.trim() &&
    a.tagline.trim() === b.tagline.trim() &&
    (typedColour(a.colour) ?? a.colour.trim()) === (typedColour(b.colour) ?? b.colour.trim()) &&
    a.logo?.id === b.logo?.id &&
    a.cover?.id === b.cover?.id
  );
}

/** The save the form makes against a draft. */
export function brandSaveOf(form: BrandFormValues, draft: BrandDraft) {
  return {
    version: draft.version,
    brand: {
      name: form.name,
      tagline: form.tagline,
      colour: typedColour(form.colour) ?? form.colour,
      logo: form.logo?.id ?? null,
      cover: form.cover?.id ?? null,
    },
  };
}

/** The form field an API field error names (`brand.colour` is `colour`); null for none. */
export function brandFieldOf(apiField: string): BrandField | null {
  const match = /^brand\.(name|tagline|colour|logo|cover)$/.exec(apiField);
  return match ? (match[1] as BrandField) : null;
}
