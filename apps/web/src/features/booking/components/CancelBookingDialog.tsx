'use client';
import { formatClock, formatDay } from '@salon/format';
import { useTranslations } from 'next-intl';
import { useState } from 'react';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { whenOf } from '../client/booking-time';
import type { Booking, CancelReason } from '../types';

const REASONS: CancelReason[] = ['customer_asked', 'salon_unable', 'booked_by_mistake', 'other'];
/** The longest note the API keeps. */
const NOTE_MAX = 200;

type Props = {
  booking: Booking | null;
  open: boolean;
  onKeep: () => void;
  onCancel: (reason: CancelReason, note: string) => void;
};

/**
 * The confirmation before a booking is cancelled (the prototype's `f7Cancel`, AC-8): the customer, day and time, a
 * required reason and a note for the team of up to 200 characters; **Cancel booking** and **Keep booking**.
 */
export function CancelBookingDialog({ booking, open, onKeep, onCancel }: Props) {
  const t = useTranslations('bookings.cancel');
  const [reason, setReason] = useState<CancelReason | null>(null);
  const [note, setNote] = useState('');
  const [missing, setMissing] = useState(false);
  const when = booking ? whenOf(booking) : null;
  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && onKeep()}>
      <AlertDialogContent data-testid="cancel-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>{t('title')}</AlertDialogTitle>
          <AlertDialogDescription className="wrap-anywhere" data-testid="cancel-summary">
            {booking && when
              ? t('body', { name: booking.customer.name, day: formatDay(when.date), time: formatClock(when.starts) })
              : null}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <Field>
          <FieldLabel htmlFor="cancel-reason">{t('reason')}</FieldLabel>
          <Select
            value={reason ?? undefined}
            onValueChange={(next) => {
              setReason(next as CancelReason);
              setMissing(false);
            }}
          >
            <SelectTrigger
              id="cancel-reason"
              aria-required="true"
              aria-invalid={missing || undefined}
              aria-describedby={missing ? 'cancel-reason-error' : undefined}
              data-testid="cancel-reason"
            >
              <SelectValue placeholder={t('reasonPlaceholder')} />
            </SelectTrigger>
            <SelectContent>
              {REASONS.map((each) => (
                <SelectItem key={each} value={each} data-testid={`cancel-reason-option-${each}`}>
                  {t(`reasons.${each}`)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {missing && (
            <FieldError id="cancel-reason-error" data-testid="cancel-reason-error">
              {t('reasonMissing')}
            </FieldError>
          )}
        </Field>
        <Field>
          <FieldLabel htmlFor="cancel-note" className="flex-row flex-wrap gap-x-1 gap-y-0">
            {t('note')}
            <span className="font-normal">{t('noteHint')}</span>
          </FieldLabel>
          <Textarea
            id="cancel-note"
            rows={2}
            maxLength={NOTE_MAX}
            value={note}
            onChange={(event) => setNote(event.target.value.slice(0, NOTE_MAX))}
            aria-describedby="cancel-note-count"
            data-testid="cancel-note"
          />
          <FieldDescription id="cancel-note-count" className="text-right" data-testid="cancel-note-count">
            {t('noteCount', { count: note.length, max: NOTE_MAX })}
          </FieldDescription>
        </Field>
        <AlertDialogFooter>
          <AlertDialogCancel data-testid="cancel-keep">{t('keep')}</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            onClick={(event) => {
              if (!reason) {
                // The dialog stays open until a reason is chosen.
                event.preventDefault();
                setMissing(true);
                return;
              }
              onCancel(reason, note.trim());
            }}
            data-testid="cancel-confirm"
          >
            {t('confirm')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
