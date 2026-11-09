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
 * showing the chosen date). `today` is the salon's, so the calendar follows the application's clock. With `earliest` (the
 * leave's first day, F06-S03 AC-15) the calendar opens on that day's month and offers nothing before it.
 */
export function DateChooser({
  id,
  today,
  earliest,
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
  /** The first date offered, when later than today. */
  earliest?: string | null;
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
  const first = earliest && earliest > today ? earliest : today;
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
          defaultMonth={dateOfText(earliest ? first : (value ?? today))}
          startMonth={dateOfText(first)}
          endMonth={dateOfText(last)}
          disabled={[{ before: dateOfText(first) }, { after: dateOfText(last) }]}
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
