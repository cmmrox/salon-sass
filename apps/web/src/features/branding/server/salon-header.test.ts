import { describe, expect, it } from 'vitest';

import { decodeSalon, encodeSalon } from './salon-header';

describe('the salon header', () => {
  it('carries a salon there and back, whatever its name', () => {
    for (const salon of [
      {
        slug: 'beta',
        name: 'Beta Hair, Beauty & Wellness Lounge of Colombo Seven',
        brandColour: '#1F2937',
        timeZone: 'Asia/Colombo',
      },
      { slug: 'alpha', name: 'ඇල්ෆා ස්ටුඩියෝ "quoted"; ;', brandColour: '#D6086A', timeZone: 'Asia/Colombo' },
    ]) {
      const value = encodeSalon(salon);

      expect(value).toMatch(/^[\x21-\x7e]+$/);
      expect(decodeSalon(value)).toEqual(salon);
    }
  });

  it("carries only the contract's fields", () => {
    const value = encodeSalon({
      slug: 'alpha',
      name: 'Alpha',
      brandColour: '#D6086A',
      timeZone: 'Asia/Colombo',
      extra: 'x',
    } as never);

    expect(decodeSalon(value)).toEqual({
      slug: 'alpha',
      name: 'Alpha',
      brandColour: '#D6086A',
      timeZone: 'Asia/Colombo',
    });
  });

  it.each([
    null,
    undefined,
    '',
    'not json',
    '%E0%A4%A',
    encodeURIComponent('null'),
    encodeURIComponent('"alpha"'),
    encodeURIComponent(JSON.stringify({ slug: 'alpha', name: '', brandColour: '#D6086A', timeZone: 'Asia/Colombo' })),
    encodeURIComponent(JSON.stringify({ slug: 'alpha', name: 'Alpha', brandColour: 'red', timeZone: 'Asia/Colombo' })),
    encodeURIComponent(JSON.stringify({ name: 'Alpha', brandColour: '#D6086A', timeZone: 'Asia/Colombo' })),
  ])('is no salon for anything else: %j', (value) => {
    expect(decodeSalon(value)).toBeNull();
  });
});

describe('the published logo in the header (F04-S02)', () => {
  const salon = { slug: 'alpha', name: 'Alpha Studio', brandColour: '#D6086A', timeZone: 'Asia/Colombo' };

  it('travels with the salon when it has one', () => {
    const logo = '0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10';
    expect(decodeSalon(encodeSalon({ ...salon, logo }))).toEqual({ ...salon, logo });
    expect(decodeSalon(encodeSalon(salon))).toEqual(salon);
  });

  it('is dropped when it is not an image id, and the salon keeps its initial', () => {
    const forged = encodeURIComponent(JSON.stringify({ ...salon, logo: '../../etc/passwd' }));
    expect(decodeSalon(forged)).toEqual(salon);
  });
});
