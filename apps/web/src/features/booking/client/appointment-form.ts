import { isMobileNumber } from '@salon/format/mobile';
import type { BookableMember, BookableService, BookingChannel } from '../types';

/** The sources the desk chooses from, Phone first (the prototype's `F7SRC`, AC-4). */
export const SOURCES = [
  'phone',
  'whatsapp',
  'front_desk',
  'walk_in',
  'instagram',
  'google',
] as const satisfies readonly BookingChannel[];
export type Source = (typeof SOURCES)[number];

/** "No preference" in the professional Select: no `memberId` is sent, and the API assigns one (F07-S02 AC-5). */
export const NO_PREFERENCE = 'any';

/** A customer's name, as the API takes it: 2 to 60 characters, trimmed. */
export const NAME_MAX = 60;
const NAME_MIN = 2;

/** What the desk has entered in New appointment. */
export type AppointmentValues = {
  name: string;
  mobile: string;
  day: string | null;
  serviceId: string | null;
  variantId: string | null;
  professional: string;
  source: Source;
};

export type AppointmentProblem = 'name' | 'mobile';

export const newAppointment = (): AppointmentValues => ({
  name: '',
  mobile: '',
  day: null,
  serviceId: null,
  variantId: null,
  professional: NO_PREFERENCE,
  source: 'phone',
});

/** Whether the desk has started the form: something typed, which leaving would lose (AC-11's leave guard). */
export const started = (values: AppointmentValues) => values.name.trim() !== '' || values.mobile.trim() !== '';

/** The broken rules that keep a search from running (AC-4), in the form's order. */
export function problemsOf(values: AppointmentValues, region: string): AppointmentProblem[] {
  const problems: AppointmentProblem[] = [];
  const name = values.name.trim();
  if (name.length < NAME_MIN || name.length > NAME_MAX) problems.push('name');
  if (!isMobileNumber(values.mobile, region)) problems.push('mobile');
  return problems;
}

/** The fields the API names in a refusal, as the form's own. */
export function problemsOfFields(fields: string[]): AppointmentProblem[] {
  const problems: AppointmentProblem[] = [];
  if (fields.includes('customer.name')) problems.push('name');
  if (fields.includes('customer.mobile')) problems.push('mobile');
  return problems;
}

/** Whether a member performs a service: chosen by name, or with its whole category (F06-S01). */
export const performs = (member: BookableMember, service: Pick<BookableService, 'id' | 'categoryId'>) =>
  member.serviceIds.includes(service.id) || member.categoryIds.includes(service.categoryId);

/** The values after a service is chosen: its first variant when it has variants, and a professional who performs it. */
export function withService(
  values: AppointmentValues,
  service: BookableService,
  members: BookableMember[],
): AppointmentValues {
  const keeps =
    values.professional === NO_PREFERENCE || members.some((m) => m.id === values.professional && performs(m, service));
  return {
    ...values,
    serviceId: service.id,
    variantId: service.variants[0]?.id ?? null,
    professional: keeps ? values.professional : NO_PREFERENCE,
  };
}

/** The BFF's free-times read for the values: the service, its variant, the day and the professional, if one. */
export function availabilityRequest(values: AppointmentValues): string {
  const params = new URLSearchParams({ serviceId: values.serviceId ?? '', date: values.day ?? '' });
  if (values.variantId) params.set('variantId', values.variantId);
  if (values.professional !== NO_PREFERENCE) params.set('memberId', values.professional);
  return `booking-availability?${params.toString()}`;
}

/**
 * The hold for a chosen time: the professional chosen, or none for "No preference" (the API assigns one), the
 * customer as typed (the API stores the mobile in E.164), the source, and the attempt's `Idempotency-Key`.
 */
export function holdRequest(values: AppointmentValues, time: string, idempotencyKey: string) {
  return {
    idempotencyKey,
    memberId: values.professional === NO_PREFERENCE ? null : values.professional,
    serviceId: values.serviceId,
    variantId: values.variantId,
    date: values.day,
    time,
    customer: { name: values.name.trim(), mobile: values.mobile.trim() },
    channel: values.source,
  };
}

/** A person's first name, as the prototype says it in a chip and a confirmation ("10:30 · Nimal"). */
export const firstName = (name: string) => name.trim().split(/\s+/)[0] ?? name;
