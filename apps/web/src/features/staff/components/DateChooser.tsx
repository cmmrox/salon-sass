'use client';
import { formatDay } from '@salon/format';
import { CalendarIcon } from 'lucide-react';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Calendar } from '@/components/ui/calendar';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { dayButtonLabel } from '@/lib/dates/calendar-labels';
import { dateOfText, lastClosableDate, textOfDate } from '../client/hours-form';

/**
 * Choosing one date from today to a year ahead (web.md's component map: a Popover with a Calendar, opened from a Button
 * showing the chosen date). `today` is the salon's, so the calendar follows the application's clock.
 */
export function DateChooser({
  id,
  today,
  value,
  placeholder,
  calendarLabel,
  disabled,
  describedBy,
  testId,
  onChange,
}: {
  id: string;
  today: string;
  value: string | null;
  placeholder: string;
  calendarLabel: string;
  disabled?: boolean;
  describedBy?: string;
  testId: string;
  onChange: (date: string) => void;
}) {
  const [picking, setPicking] = useState(false);
  const last = lastClosableDate(today);
  return (
    <Popover open={picking} onOpenChange={setPicking}>
      <PopoverTrigger asChild>
        <Button
          id={id}
          variant="outline"
          className="w-full justify-between font-normal"
          disabled={disabled}
          aria-describedby={describedBy}
          data-testid={testId}
        >
          {value ? formatDay(value) : placeholder}
          <CalendarIcon aria-hidden="true" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        className="w-auto p-0"
        align="start"
        aria-label={calendarLabel}
        data-testid={`${testId}-calendar`}
      >
        <Calendar
          labels={{ labelDayButton: dayButtonLabel }}
          mode="single"
          selected={value ? dateOfText(value) : undefined}
          defaultMonth={value ? dateOfText(value) : dateOfText(today)}
          startMonth={dateOfText(today)}
          endMonth={dateOfText(last)}
          disabled={[{ before: dateOfText(today) }, { after: dateOfText(last) }]}
          onSelect={(picked) => {
            if (!picked) return;
            onChange(textOfDate(picked));
            setPicking(false);
          }}
        />
      </PopoverContent>
    </Popover>
  );
}
