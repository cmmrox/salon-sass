import { describe, expect, it } from 'vitest';

import { decodeSalon, encodeSalon } from './salon-header';

describe('the salon header', () => {
  it('carries a salon there and back, whatever its name', () => {
    for (const salon of [
      { slug: 'beta', name: 'Beta Hair, Beauty & Wellness Lounge of Colombo Seven', brandColour: '#1F2937', timeZone: 'Asia/Colombo' },
      { slug: 'alpha', name: 'ඇල්ෆා ස්ටුඩියෝ "quoted"; ;', brandColour: '#D6086A', timeZone: 'Asia/Colombo' },
    ]) {
      const value = encodeSalon(salon);

      expect(value).toMatch(/^[\x21-\x7e]+$/);
      expect(decodeSalon(value)).toEqual(salon);
    }
  });

  it('carries only the contract\'s fields', () => {
    const value = encodeSalon({ slug: 'alpha', name: 'Alpha', brandColour: '#D6086A', timeZone: 'Asia/Colombo', extra: 'x' } as never);

    expect(decodeSalon(value)).toEqual({ slug: 'alpha', name: 'Alpha', brandColour: '#D6086A', timeZone: 'Asia/Colombo' });
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
