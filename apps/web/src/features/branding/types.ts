import type { components } from '@salon/api-contracts/branding';

/**
 * What the web app carries about a salon: its public brand, and the id of its published logo when it has
 * one (F04-S02), shown at `/brand-images/<id>`. The contract's salon holds more, which no screen uses yet; a
 * screen that needs more of it takes it then.
 */
export type Salon = Pick<components['schemas']['Salon'], 'slug' | 'name' | 'brandColour' | 'timeZone'> & {
  logo?: string;
};

/** The salon's profile draft, as the settings staff see it (F04-S01). */
export type ProfileDraft = components['schemas']['SalonProfileDraft'];

/** A profile save, as the workspace BFF hands it on: the draft's version and the profile. */
export type ProfileSave = components['schemas']['SaveSalonProfileDraft'];

/** The salon's brand draft, as the settings staff see it (F04-S02). */
export type BrandDraft = components['schemas']['SalonBrandDraft'];

/** An image just uploaded for the brand, not yet in the draft. */
export type UploadedBrandImage = components['schemas']['UploadedSalonBrandImage'];
