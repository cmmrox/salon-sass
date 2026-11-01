import { describe, expect, it } from 'vitest';
import type { BrandDraft } from '../types';
import { brandFieldOf, brandFormOf, brandSaveOf, sameBrandForm, typedColour } from './brand-form';

const LOGO = { id: '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10', width: 512, height: 512 };
const draft: BrandDraft = {
  version: 3,
  hasUnpublishedChanges: false,
  publishedAt: null,
  publishedBy: null,
  brand: { name: 'Alpha Studio', tagline: null, colour: '#D6086A', logo: LOGO, cover: null },
};

describe('the brand form', () => {
  it('is filled from the draft, with no tagline as empty text', () => {
    expect(brandFormOf(draft)).toEqual({
      name: 'Alpha Studio',
      tagline: '',
      colour: '#D6086A',
      logo: LOGO,
      cover: null,
    });
  });

  it('saves against the draft version with each image as its id and the colour in upper case', () => {
    const form = { ...brandFormOf(draft), colour: ' #2f6b5e ', logo: null };
    expect(brandSaveOf(form, draft)).toEqual({
      version: 3,
      brand: { name: 'Alpha Studio', tagline: '', colour: '#2F6B5E', logo: null, cover: null },
    });
  });

  it('reads a typed colour only when it is #RRGGBB', () => {
    expect(typedColour('#abcdef')).toBe('#ABCDEF');
    expect(typedColour('abcdef')).toBeNull();
    expect(typedColour('#12345')).toBeNull();
  });

  it('counts a new image, and only real edits, as a change', () => {
    const form = brandFormOf(draft);
    expect(sameBrandForm(form, { ...form, name: ' Alpha Studio ', colour: '#d6086a' })).toBe(true);
    expect(sameBrandForm(form, { ...form, cover: { id: 'c', width: 1600, height: 600 } })).toBe(false);
    expect(sameBrandForm(form, { ...form, logo: null })).toBe(false);
  });

  it('names the form field of each API field error', () => {
    expect(brandFieldOf('brand.colour')).toBe('colour');
    expect(brandFieldOf('brand.logo')).toBe('logo');
    expect(brandFieldOf('version')).toBeNull();
  });
});
