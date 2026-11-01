import { describe, expect, it } from 'vitest';
import type { CatalogueService } from '../types';
import { newService, problemsOf, problemsOfFields, requestOf, shownProblems, valuesOf } from './service-form';

const COLOUR = '6b0a3c1e-9a4e-4b8e-9d1f-2a3b4c5d6e7f';
const good = { ...newService(COLOUR), name: 'Root touch-up' };

describe('the service sheet checks the prototype’s limits (F05-S01 AC-3)', () => {
  it('accepts every edge of every range', () => {
    expect(problemsOf({ ...good, name: 'Ab', price: '100', minutes: '5', buffer: '0' }, [])).toEqual([]);
    expect(
      problemsOf(
        { ...good, name: 'n'.repeat(40), price: '500,000', minutes: '480', buffer: '60', description: 'd'.repeat(90) },
        [],
      ),
    ).toEqual([]);
  });

  it.each([
    [{ name: 'B' }, 'nameLength'],
    [{ name: 'n'.repeat(41) }, 'nameLength'],
    [{ description: 'd'.repeat(91) }, 'description'],
    [{ price: '99' }, 'price'],
    [{ price: '500001' }, 'price'],
    [{ price: '2500.50' }, 'price'],
    [{ minutes: '0' }, 'time'],
    [{ minutes: '7' }, 'time'],
    [{ minutes: '485' }, 'time'],
    [{ buffer: '65' }, 'buffer'],
    [{ buffer: '3' }, 'buffer'],
    [{ buffer: '' }, 'buffer'],
    [{ categoryId: '' }, 'category'],
  ])('refuses %o as %s', (change, problem) => {
    expect(problemsOf({ ...good, ...change }, [])).toEqual([problem]);
  });

  it('says a name is taken in any case', () => {
    expect(problemsOf({ ...good, name: ' ROOT TOUCH-UP ' }, ['root touch-up'])).toEqual(['nameTaken']);
  });

  it('lists the problems in the prototype’s order', () => {
    expect(
      problemsOf({ ...good, name: 'B', description: 'd'.repeat(91), buffer: '3', price: '1', minutes: '1' }, []),
    ).toEqual(['nameLength', 'description', 'buffer', 'price', 'time']);
  });

  it('does not call a new service’s empty name too short, but an edited one’s', () => {
    const empty = { ...good, name: '' };
    expect(shownProblems(empty, problemsOf(empty, []), true)).toEqual([]);
    expect(shownProblems(empty, problemsOf(empty, []), false)).toEqual(['nameLength']);
  });
});

describe('what the sheet sends and reads', () => {
  it('sends whole rupees as minor units, trimmed text and no description as null', () => {
    expect(
      requestOf({ ...good, name: '  Root touch-up ', price: '4,500', minutes: '60', buffer: '15', description: '  ' }),
    ).toEqual({
      name: 'Root touch-up',
      categoryId: COLOUR,
      description: null,
      priceMinor: 450_000,
      durationMinutes: 60,
      bufferMinutes: 15,
      bookableOnline: true,
    });
  });

  it('reads a service back into the same values', () => {
    const service = {
      id: 'a',
      name: 'Root touch-up',
      categoryId: COLOUR,
      description: null,
      bookableOnline: false,
      photoId: null,
      photoUrl: null,
      popular: false,
      deposit: { kind: 'salon_rule' },
      version: 2,
      terms: {
        versionId: 'v',
        number: 1,
        priceMinor: 450_000,
        currency: 'LKR',
        segments: [],
        durationMinutes: 60,
        bufferMinutes: 15,
        variantSetName: null,
        variants: [],
        priceMinMinor: 450_000,
        priceMaxMinor: 450_000,
        durationMinMinutes: 60,
        durationMaxMinutes: 60,
      },
    } as CatalogueService;
    expect(valuesOf(service)).toEqual({
      name: 'Root touch-up',
      categoryId: COLOUR,
      description: '',
      price: '4500',
      minutes: '60',
      buffer: '15',
      bookableOnline: false,
    });
  });

  it('turns the API’s field errors into the sheet’s messages', () => {
    expect(problemsOfFields(['name', 'priceMinor', 'durationMinutes', 'bufferMinutes', 'categoryId'], good)).toEqual([
      'nameTaken',
      'price',
      'time',
      'buffer',
      'category',
    ]);
    expect(problemsOfFields(['name'], { ...good, name: 'B' })).toEqual(['nameLength']);
  });
});
