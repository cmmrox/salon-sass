import { describe, expect, it } from 'vitest';
import {
  availabilityRequest,
  firstName,
  holdRequest,
  newAppointment,
  NO_PREFERENCE,
  performs,
  problemsOf,
  problemsOfFields,
  started,
  withService,
} from './appointment-form';

const CUT = {
  id: 's-cut',
  name: 'Cut',
  categoryId: 'c-hair',
  currency: 'LKR',
  priceMinMinor: 450000,
  priceMaxMinor: 450000,
  variantSetName: null,
  variants: [],
};
const COLOUR = {
  ...CUT,
  id: 's-colour',
  categoryId: 'c-colour',
  variantSetName: 'Hair length',
  variants: [
    { id: 'v-short', name: 'Short', priceMinor: 600000 },
    { id: 'v-long', name: 'Long', priceMinor: 900000 },
  ],
};
const SANDUNI = { id: 'm-1', name: 'Sanduni Wickramasinghe', serviceIds: ['s-cut'], categoryIds: ['c-colour'] };
const ISHARA = { id: 'm-2', name: 'Ishara Fonseka', serviceIds: ['s-cut'], categoryIds: [] };

describe('New appointment (F07-S03 AC-4)', () => {
  it('starts with Phone and No preference, and is started once something is typed', () => {
    const values = newAppointment();
    expect(values.source).toBe('phone');
    expect(values.professional).toBe(NO_PREFERENCE);
    expect(started(values)).toBe(false);
    expect(started({ ...values, mobile: '0' })).toBe(true);
  });

  it('needs a name of 2 to 60 characters and a mobile, not a landline', () => {
    const values = { ...newAppointment(), name: 'Amaya Rajapaksa', mobile: '077 123 4567' };
    expect(problemsOf(values, 'LK')).toEqual([]);
    expect(problemsOf({ ...values, name: ' A ' }, 'LK')).toEqual(['name']);
    expect(problemsOf({ ...values, name: 'A'.repeat(61) }, 'LK')).toEqual(['name']);
    expect(problemsOf({ ...values, mobile: '0112345678' }, 'LK')).toEqual(['mobile']);
    expect(problemsOf({ ...values, name: '', mobile: '' }, 'LK')).toEqual(['name', 'mobile']);
  });

  it("reads the API's field names as the form's", () => {
    expect(problemsOfFields(['customer.mobile', 'channel'])).toEqual(['mobile']);
    expect(problemsOfFields(['customer.name'])).toEqual(['name']);
  });

  it('knows who performs a service, by the service or its whole category', () => {
    expect(performs(SANDUNI, COLOUR)).toBe(true);
    expect(performs(ISHARA, COLOUR)).toBe(false);
    expect(performs(ISHARA, CUT)).toBe(true);
  });

  it("chooses a service's first variant, and keeps a professional only when they perform it", () => {
    const chosen = withService({ ...newAppointment(), professional: 'm-2' }, COLOUR, [SANDUNI, ISHARA]);
    expect(chosen).toMatchObject({ serviceId: 's-colour', variantId: 'v-short', professional: NO_PREFERENCE });
    expect(withService({ ...newAppointment(), professional: 'm-2' }, CUT, [SANDUNI, ISHARA])).toMatchObject({
      variantId: null,
      professional: 'm-2',
    });
  });

  it('asks for free times with the variant and the professional, if one', () => {
    const values = { ...newAppointment(), serviceId: 's-colour', variantId: 'v-long', day: '2086-06-16' };
    expect(Object.fromEntries(new URLSearchParams(availabilityRequest(values).split('?')[1]))).toEqual({
      serviceId: 's-colour',
      date: '2086-06-16',
      variantId: 'v-long',
    });
    expect(availabilityRequest({ ...values, professional: 'm-1' })).toContain('memberId=m-1');
  });

  it("holds with no professional for No preference, the customer trimmed, the source and the attempt's key", () => {
    const values = {
      ...newAppointment(),
      name: ' Amaya ',
      mobile: ' 077 123 4567 ',
      serviceId: 's-cut',
      day: '2086-06-16',
      source: 'whatsapp' as const,
    };
    expect(holdRequest(values, '10:30', 'key-1')).toEqual({
      idempotencyKey: 'key-1',
      memberId: null,
      serviceId: 's-cut',
      variantId: null,
      date: '2086-06-16',
      time: '10:30',
      customer: { name: 'Amaya', mobile: '077 123 4567' },
      channel: 'whatsapp',
    });
    expect(holdRequest({ ...values, professional: 'm-1' }, '10:30', 'k').memberId).toBe('m-1');
  });

  it('says a first name as the prototype does', () => {
    expect(firstName('Nimal Perera')).toBe('Nimal');
    expect(firstName('  Kavindi ')).toBe('Kavindi');
  });
});
