'use client';
import { formatDay, formatMoney } from '@salon/format';
import { CalendarIcon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useState, type ReactNode } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Calendar } from '@/components/ui/calendar';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet';
import { Skeleton } from '@/components/ui/skeleton';
import { Spinner } from '@/components/ui/spinner';
import { dayButtonLabel } from '@/lib/dates/calendar-labels';
import { cn } from '@/lib/utils';
import {
  NAME_MAX,
  NO_PREFERENCE,
  SOURCES,
  performs,
  withService,
  type AppointmentProblem,
  type AppointmentValues,
  type Source,
} from '../client/appointment-form';
import { aYearOn, dateOfText, textOfDate } from '../client/booking-time';
import type { BookableMember, BookableService } from '../types';
import { SHEET_CLASSES } from './sheet-classes';

/** What New appointment offers: the services, the team and the open days, once read. */
export type AppointmentChoices = { services: BookableService[]; members: BookableMember[]; openDays: string[] };

type Props = {
  open: boolean;
  today: string;
  /** Null while the services, team and open days are being read. */
  choices: AppointmentChoices | null;
  values: AppointmentValues;
  /** The broken rules shown on their fields: after a search was asked for. */
  problems: AppointmentProblem[];
  searching: boolean;
  /** The free times, the hold and the refusals below the form. */
  results: ReactNode;
  /** What the page says while the sheet is open (it cannot be seen behind the overlay). */
  notices?: ReactNode;
  /** The polite announcement of what just happened: times found, a minute gone from the hold. */
  announcement: string;
  onChange: (values: AppointmentValues) => void;
  onFind: () => void;
  onClose: () => void;
  /** Where focus goes when the sheet has closed: what opened it. */
  onClosed: () => void;
};

const price = (service: BookableService, from: (price: string) => string) =>
  service.priceMinMinor === service.priceMaxMinor
    ? formatMoney(service.priceMinMinor, service.currency)
    : from(formatMoney(service.priceMinMinor, service.currency));

/**
 * New appointment (the prototype's `f7New`, AC-4): the customer's name and mobile, an open day from today to a year
 * ahead, the service with its price and its variant, the professional (No preference, then who performs it), how they
 * booked, and **Find free times**; below, the times, the hold and the refusals.
 */
export function NewAppointmentSheet(props: Props) {
  const t = useTranslations('bookings.newAppointment');
  const channels = useTranslations('bookings.channels');
  const { values, choices, onChange } = props;
  const [picking, setPicking] = useState(false);
  const service = choices?.services.find((each) => each.id === values.serviceId) ?? null;
  const performers = service ? (choices?.members ?? []).filter((member) => performs(member, service)) : [];
  const open = new Set(choices?.openDays ?? []);
  const invalid = (problem: AppointmentProblem) => props.problems.includes(problem);
  const aria = (problem: AppointmentProblem, help?: string) => ({
    'aria-invalid': invalid(problem) || undefined,
    'aria-describedby':
      [help, invalid(problem) ? `new-appointment-${problem}-error` : undefined].filter(Boolean).join(' ') || undefined,
  });
  const error = (problem: AppointmentProblem) =>
    invalid(problem) && (
      <FieldError
        id={`new-appointment-${problem}-error`}
        role={undefined}
        data-testid={`new-appointment-${problem}-error`}
      >
        {t(`problems.${problem}`)}
      </FieldError>
    );
  return (
    <Sheet open={props.open} onOpenChange={(next) => !next && props.onClose()}>
      <SheetContent
        side="bottom"
        className={cn(SHEET_CLASSES)}
        data-testid="new-appointment"
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          props.onClosed();
        }}
      >
        <div className="flex items-center justify-between gap-3">
          <SheetTitle className="rail:text-workspace-title">{t('title')}</SheetTitle>
          <SheetClose asChild>
            <Button variant="outline" size="sm" data-testid="new-appointment-close">
              {t('close')}
            </Button>
          </SheetClose>
        </div>
        <SheetDescription className="-mt-2 text-small" data-testid="new-appointment-intro">
          {t('intro')}
        </SheetDescription>
        <p className="sr-only" role="status" data-testid="new-appointment-announcement">
          {props.announcement}
        </p>
        {props.notices}
        {!choices ? (
          <div
            className="flex flex-col gap-3"
            role="status"
            aria-busy="true"
            aria-label={t('loading')}
            data-testid="new-appointment-loading"
          >
            <Skeleton className="h-12 w-full rounded-field" />
            <Skeleton className="h-12 w-full rounded-field" />
            <Skeleton className="h-12 w-full rounded-field" />
          </div>
        ) : choices.services.length === 0 ? (
          <Alert variant="notice" data-testid="new-appointment-no-services">
            {t('noServices')}
          </Alert>
        ) : (
          <form
            className="flex flex-col gap-4"
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              props.onFind();
            }}
          >
            {/* A problem is said on its field and tied to it; the search moves focus to the first one. */}
            <Field>
              <FieldLabel htmlFor="new-appointment-name">{t('name')}</FieldLabel>
              <Input
                id="new-appointment-name"
                autoComplete="off"
                maxLength={NAME_MAX}
                value={values.name}
                onChange={(event) => onChange({ ...values, name: event.target.value })}
                {...aria('name')}
                data-testid="new-appointment-name"
              />
              {error('name')}
            </Field>
            <Field>
              <FieldLabel htmlFor="new-appointment-mobile">{t('mobile')}</FieldLabel>
              <Input
                id="new-appointment-mobile"
                inputMode="tel"
                autoComplete="off"
                placeholder={t('mobilePlaceholder')}
                value={values.mobile}
                onChange={(event) => onChange({ ...values, mobile: event.target.value })}
                {...aria('mobile', 'new-appointment-mobile-help')}
                data-testid="new-appointment-mobile"
              />
              <FieldDescription id="new-appointment-mobile-help" className="text-foreground">
                {t('mobileHelp')}
              </FieldDescription>
              {error('mobile')}
            </Field>
            <Field>
              <FieldLabel htmlFor="new-appointment-day">{t('day')}</FieldLabel>
              <Popover open={picking} onOpenChange={setPicking}>
                <PopoverTrigger asChild>
                  <Button
                    id="new-appointment-day"
                    variant="outline"
                    className="w-full justify-between font-normal"
                    aria-describedby="new-appointment-day-help"
                    data-testid="new-appointment-day"
                  >
                    {values.day ? formatDay(values.day) : t('dayPlaceholder')}
                    <CalendarIcon aria-hidden="true" />
                  </Button>
                </PopoverTrigger>
                <PopoverContent
                  className="w-auto p-0"
                  align="start"
                  aria-label={t('dayCalendar')}
                  data-testid="new-appointment-calendar"
                >
                  <Calendar
                    labels={{ labelDayButton: dayButtonLabel }}
                    mode="single"
                    today={dateOfText(props.today)}
                    selected={values.day ? dateOfText(values.day) : undefined}
                    defaultMonth={dateOfText(values.day ?? props.today)}
                    startMonth={dateOfText(props.today)}
                    endMonth={dateOfText(aYearOn(props.today))}
                    disabled={(date) => !open.has(textOfDate(date))}
                    onSelect={(picked) => {
                      if (!picked) return;
                      onChange({ ...values, day: textOfDate(picked) });
                      setPicking(false);
                    }}
                  />
                </PopoverContent>
              </Popover>
              <FieldDescription id="new-appointment-day-help" className="text-foreground">
                {choices.openDays.length ? t('dayHelp') : t('noOpenDays')}
              </FieldDescription>
            </Field>
            <Field>
              <FieldLabel htmlFor="new-appointment-service">{t('service')}</FieldLabel>
              <Select
                value={values.serviceId ?? undefined}
                onValueChange={(id) => {
                  const chosen = choices.services.find((each) => each.id === id);
                  if (chosen) onChange(withService(values, chosen, choices.members));
                }}
              >
                <SelectTrigger id="new-appointment-service" data-testid="new-appointment-service">
                  <SelectValue placeholder={t('servicePlaceholder')} />
                </SelectTrigger>
                <SelectContent>
                  {choices.services.map((each) => (
                    <SelectItem
                      key={each.id}
                      value={each.id}
                      data-testid="new-appointment-service-option"
                      data-id={each.id}
                    >
                      {t('serviceOption', {
                        service: each.name,
                        price: price(each, (from) => t('from', { price: from })),
                      })}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            {service && service.variants.length > 0 && (
              <Field>
                <FieldLabel htmlFor="new-appointment-variant">{service.variantSetName ?? t('variant')}</FieldLabel>
                <Select
                  value={values.variantId ?? undefined}
                  onValueChange={(variantId) => onChange({ ...values, variantId })}
                >
                  <SelectTrigger id="new-appointment-variant" data-testid="new-appointment-variant">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {service.variants.map((variant) => (
                      <SelectItem
                        key={variant.id}
                        value={variant.id}
                        data-testid="new-appointment-variant-option"
                        data-id={variant.id}
                      >
                        {t('variantOption', {
                          variant: variant.name,
                          price: formatMoney(variant.priceMinor, service.currency),
                        })}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
            )}
            <Field>
              <FieldLabel htmlFor="new-appointment-professional">{t('professional')}</FieldLabel>
              <Select
                value={values.professional}
                onValueChange={(professional) => onChange({ ...values, professional })}
              >
                <SelectTrigger id="new-appointment-professional" data-testid="new-appointment-professional">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_PREFERENCE} data-testid="new-appointment-professional-option-any">
                    {t('noPreference')}
                  </SelectItem>
                  {performers.map((member) => (
                    <SelectItem
                      key={member.id}
                      value={member.id}
                      data-testid="new-appointment-professional-option"
                      data-id={member.id}
                    >
                      {member.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel htmlFor="new-appointment-source">{t('source')}</FieldLabel>
              <Select
                value={values.source}
                onValueChange={(source) => onChange({ ...values, source: source as Source })}
              >
                <SelectTrigger id="new-appointment-source" data-testid="new-appointment-source">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {SOURCES.map((source) => (
                    <SelectItem key={source} value={source} data-testid={`new-appointment-source-option-${source}`}>
                      {channels(source)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Button
              type="submit"
              disabled={props.searching || !values.serviceId || !values.day}
              data-testid="new-appointment-find"
            >
              {props.searching && <Spinner />}
              {t('find')}
            </Button>
          </form>
        )}
        {props.results}
      </SheetContent>
    </Sheet>
  );
}
