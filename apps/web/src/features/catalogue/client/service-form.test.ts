import { describe, expect, it } from 'vitest';
import type { CatalogueService } from '../types';
import {
  newService,
  problemsOf,
  problemsOfFields,
  requestOf,
  sameValues,
  shownProblems,
  valuesOf,
  variantIdRefused,
  withVariantsOn,
  type ServiceValues,
} from './service-form';

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
      deposit: { kind: 'salon_rule' },
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
      hasVariants: false,
      setName: '',
      variants: [],
      deposit: { choice: 'salon_rule', customKind: 'percentage', percent: '20', amount: '1000' },
      popular: false,
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

const variant = (name: string, price: string, minutes = '30', id: string | null = null) => ({
  key: name,
  id,
  name,
  price,
  minutes,
});
const cut: ServiceValues = {
  ...good,
  hasVariants: true,
  setName: 'Hair length',
  variants: [variant('Short', '2500', '30', 'v1'), variant('Long', '4000', '60', 'v2')],
};

describe('a service’s variants (F05-S02 AC-1)', () => {
  it('accepts 2 and 10 variants, names of 30 characters and every edge of price and time', () => {
    expect(problemsOf(cut, [])).toEqual([]);
    const ten = Array.from({ length: 10 }, (_, i) =>
      variant(`${'n'.repeat(28)}${i}`, i % 2 ? '100' : '500,000', '480'),
    );
    expect(problemsOf({ ...cut, variants: ten }, [])).toEqual([]);
  });

  it('says what is wrong with the set and with each variant, naming it', () => {
    const one = { ...cut, setName: 'H', variants: [variant('Short', '2500')] };
    expect(problemsOf(one, [])).toEqual(['setName', 'variantCount']);
    const eleven = { ...cut, variants: Array.from({ length: 11 }, (_, i) => variant(`V${i}`, '1000')) };
    expect(problemsOf(eleven, [])).toEqual(['variantCount']);
    const broken = {
      ...cut,
      variants: [
        variant('', '99', '7'),
        variant('Short', '2500'),
        variant(' SHORT ', '500001', '485'),
        variant('x'.repeat(31), '100'),
      ],
    };
    expect(problemsOf(broken, [])).toEqual([
      'variantName:0',
      'variantPrice:0',
      'variantTime:0',
      'variantTwice:2',
      'variantPrice:2',
      'variantTime:2',
      'variantName:3',
    ]);
  });

  it('does not check the service’s own price and time while it has variants', () => {
    expect(problemsOf({ ...cut, price: '1', minutes: '1' }, [])).toEqual([]);
  });

  it('sends the variants with their ids, a new one without, and no price or time of the service', () => {
    const request = requestOf({ ...cut, variants: [...cut.variants, variant(' Extra long ', '5,500', '90')] });
    expect(request).not.toHaveProperty('priceMinor');
    expect(request).not.toHaveProperty('durationMinutes');
    expect(request.variants).toEqual({
      name: 'Hair length',
      items: [
        { id: 'v1', name: 'Short', priceMinor: 250_000, durationMinutes: 30 },
        { id: 'v2', name: 'Long', priceMinor: 400_000, durationMinutes: 60 },
        { name: 'Extra long', priceMinor: 550_000, durationMinutes: 90 },
      ],
    });
  });

  it('turns variants on with two from the service’s own price and time, and keeps those it had', () => {
    const on = withVariantsOn({ ...good, price: '3000', minutes: '45' }, ['Option 1', 'Option 2']);
    expect(on.hasVariants).toBe(true);
    expect(on.variants.map(({ name, price, minutes, id }) => ({ name, price, minutes, id }))).toEqual([
      { name: 'Option 1', price: '3000', minutes: '45', id: null },
      { name: 'Option 2', price: '3000', minutes: '45', id: null },
    ]);
    expect(withVariantsOn({ ...cut, hasVariants: false }, ['A', 'B']).variants).toEqual(cut.variants);
  });

  it('reads a service’s variants and deposit back, and its own price and time as the lowest and shortest', () => {
    const values = valuesOf({
      id: 'a',
      name: 'Haircut',
      categoryId: COLOUR,
      description: null,
      bookableOnline: true,
      photoId: null,
      photoUrl: null,
      popular: true,
      deposit: { kind: 'amount', amountMinor: 150_000 },
      version: 3,
      terms: {
        versionId: 'v',
        number: 2,
        priceMinor: 250_000,
        currency: 'LKR',
        segments: [{ kind: 'active', minutes: 30 }],
        durationMinutes: 30,
        bufferMinutes: 0,
        variantSetName: 'Hair length',
        variants: [
          {
            id: 'v1',
            name: 'Short',
            priceMinor: 250_000,
            segments: [{ kind: 'active', minutes: 30 }],
            durationMinutes: 30,
          },
          {
            id: 'v2',
            name: 'Long',
            priceMinor: 400_000,
            segments: [{ kind: 'active', minutes: 60 }],
            durationMinutes: 60,
          },
        ],
        priceMinMinor: 250_000,
        priceMaxMinor: 400_000,
        durationMinMinutes: 30,
        durationMaxMinutes: 60,
      },
    });
    expect(values).toMatchObject({
      price: '2500',
      minutes: '30',
      hasVariants: true,
      setName: 'Hair length',
      popular: true,
      deposit: { choice: 'custom', customKind: 'amount', amount: '1500' },
    });
    expect(values.variants.map((each) => [each.id, each.name, each.price, each.minutes])).toEqual([
      ['v1', 'Short', '2500', '30'],
      ['v2', 'Long', '4000', '60'],
    ]);
  });

  it('turns the API’s variant field errors into the sheet’s messages, and a variant id it does not know into a conflict', () => {
    expect(
      problemsOfFields(
        [
          'variants.name',
          'variants.items',
          'variants.items[1].name',
          'variants.items[0].priceMinor',
          'variants.items[1].durationMinutes',
        ],
        cut,
      ),
    ).toEqual(['setName', 'variantCount', 'variantTwice:1', 'variantPrice:0', 'variantTime:1']);
    expect(variantIdRefused(['variants.items[1].id'])).toBe(true);
    expect(variantIdRefused(['variants.items[1].name'])).toBe(false);
  });
});

describe('a service’s deposit (F05-S02 AC-7)', () => {
  const custom = (change: Partial<ServiceValues['deposit']>) => ({
    ...good,
    price: '2500',
    deposit: { ...good.deposit, choice: 'custom' as const, ...change },
  });

  it('sends the salon’s rule, none, a percentage or an amount in minor units', () => {
    expect(requestOf(good).deposit).toEqual({ kind: 'salon_rule' });
    expect(requestOf({ ...good, deposit: { ...good.deposit, choice: 'none' } }).deposit).toEqual({ kind: 'none' });
    expect(requestOf(custom({ customKind: 'percentage', percent: '25' })).deposit).toEqual({
      kind: 'percentage',
      percent: 25,
    });
    expect(requestOf(custom({ customKind: 'amount', amount: '1,000' })).deposit).toEqual({
      kind: 'amount',
      amountMinor: 100_000,
    });
  });

  it('checks a percentage from 1 to 100 and an amount from Rs. 100 to Rs. 100,000 and not above the lowest price', () => {
    expect(problemsOf(custom({ customKind: 'percentage', percent: '1' }), [])).toEqual([]);
    expect(problemsOf(custom({ customKind: 'percentage', percent: '100' }), [])).toEqual([]);
    expect(problemsOf(custom({ customKind: 'percentage', percent: '0' }), [])).toEqual(['depositPercent']);
    expect(problemsOf(custom({ customKind: 'percentage', percent: '101' }), [])).toEqual(['depositPercent']);
    expect(problemsOf(custom({ customKind: 'amount', amount: '99' }), [])).toEqual(['depositAmount']);
    expect(problemsOf(custom({ customKind: 'amount', amount: '2500' }), [])).toEqual([]);
    expect(problemsOf(custom({ customKind: 'amount', amount: '2600' }), [])).toEqual(['depositAboveLowest']);
    expect(
      problemsOf({ ...cut, deposit: { ...good.deposit, choice: 'custom', customKind: 'amount', amount: '2600' } }, []),
    ).toEqual(['depositAboveLowest']);
    expect(problemsOfFields(['deposit.amountMinor'], custom({ customKind: 'amount', amount: '2600' }))).toEqual([
      'depositAboveLowest',
    ]);
    expect(problemsOfFields(['deposit.percent'], good)).toEqual(['depositPercent']);
  });

  it('counts a Popular choice as an edit to keep, apart from the service', () => {
    expect(sameValues(good, { ...good, popular: true })).toBe(false);
    expect(sameValues(good, { ...good })).toBe(true);
  });
});
