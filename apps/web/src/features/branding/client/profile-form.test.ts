import { describe, expect, it } from 'vitest';
import type { ProfileDraft } from '../types';
import { fieldOf, formOf, mapLink, parseLocation, sameForm, saveOf } from './profile-form';

const BRANCH = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
const draft: ProfileDraft = {
  version: 4,
  hasUnpublishedChanges: true,
  publishedAt: null,
  publishedBy: null,
  profile: {
    description: 'A friendly salon.',
    links: { instagram: 'https://instagram.com/x', facebook: null, website: null },
    branches: [
      {
        id: BRANCH,
        isDefault: true,
        address: 'No. 24, Flower Road',
        location: { latitude: 6.9061, longitude: 79.8611 },
        phone: '+94112504417',
        whatsapp: '+94774123300',
      },
    ],
  },
};

describe('the profile form', () => {
  it('is filled from the draft with phone numbers in the national format and the position as one value', () => {
    expect(formOf(draft)).toEqual({
      address: 'No. 24, Flower Road',
      location: '6.9061, 79.8611',
      phone: '0112 504 417',
      whatsapp: '077 412 3300',
      description: 'A friendly salon.',
      instagram: 'https://instagram.com/x',
      facebook: '',
      website: '',
    });
  });

  it('saves against the draft’s version for its default branch, a typed position as two numbers', () => {
    const form = { ...formOf(draft), location: ' 7.29 ,80.63 ', phone: '077 123 4567' };
    expect(saveOf(form, draft)).toEqual({
      save: {
        version: 4,
        profile: {
          description: 'A friendly salon.',
          links: { instagram: 'https://instagram.com/x', facebook: '', website: '' },
          branches: [
            {
              id: BRANCH,
              address: 'No. 24, Flower Road',
              location: { latitude: 7.29, longitude: 80.63 },
              phone: '077 123 4567',
              whatsapp: '077 412 3300',
            },
          ],
        },
      },
    });
  });

  it('refuses a position that is not two numbers before anything is sent', () => {
    expect(saveOf({ ...formOf(draft), location: 'Colombo' }, draft)).toEqual({ invalid: 'location' });
    expect(parseLocation('')).toBeNull();
    expect(parseLocation('6.9, 79.8, 1')).toBe('invalid');
    expect(parseLocation('-33.86,151.2')).toEqual({ latitude: -33.86, longitude: 151.2 });
  });

  it('links Check on map to the typed position only when it is two numbers', () => {
    expect(mapLink('6.9061, 79.8611')).toBe('https://www.google.com/maps/search/?api=1&query=6.9061,79.8611');
    expect(mapLink('')).toBeNull();
    expect(mapLink('near the temple')).toBeNull();
  });

  it('names the form field of each API field error', () => {
    expect(fieldOf('profile.branches[0].phone')).toBe('phone');
    expect(fieldOf('profile.branches[0].location.latitude')).toBe('location');
    expect(fieldOf('profile.links.website')).toBe('website');
    expect(fieldOf('profile.description')).toBe('description');
    expect(fieldOf('profile.branches[0].id')).toBeNull();
    expect(fieldOf('version')).toBeNull();
  });

  it('counts surrounding spaces as no change', () => {
    expect(sameForm(formOf(draft), { ...formOf(draft), address: ' No. 24, Flower Road ' })).toBe(true);
    expect(sameForm(formOf(draft), { ...formOf(draft), address: 'Elsewhere' })).toBe(false);
  });
});
