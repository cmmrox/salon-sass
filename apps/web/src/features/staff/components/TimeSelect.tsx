'use client';
import { formatClock } from '@salon/format';
import { useMemo, useState } from 'react';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { QUARTER_HOURS } from '../client/hours-form';

/** Each quarter hour with its label on the configured clock, worked out once for every time Select. */
const OPTIONS = QUARTER_HOURS.map((time) => ({ time, label: formatClock(time), id: time.replace(':', '') }));
const LABELS = new Map(OPTIONS.map(({ time, label }) => [time, label]));

/**
 * A time of day on a quarter hour, chosen from a Select (no native control). Its accessible name says which
 * weekday or holiday it opens or closes, as the prototype's do ("Monday opens").
 */
export function TimeSelect({
  value,
  onChange,
  label,
  testId,
  disabled,
  invalid,
  describedBy,
  narrow,
}: {
  value: string;
  onChange: (value: string) => void;
  label: string;
  testId: string;
  disabled?: boolean;
  invalid?: boolean;
  describedBy?: string;
  /** The schedule sheet's narrower control (the prototype's `tselS`), so that two fit beside a weekday on a phone. */
  narrow?: boolean;
}) {
  // Radix mounts a closed Select's options too (out of the page), for the trigger's text and for a key typed on
  // it. With 96 options, a sheet of fourteen or more of these controls mounted well over a thousand hidden
  // options and re-rendered them on every edit. They are mounted only while this list is open or its trigger has
  // the focus, which is all a key typed on the trigger needs; the trigger names the chosen time itself.
  const [open, setOpen] = useState(false);
  const [focused, setFocused] = useState(false);
  const options = useMemo(
    () =>
      OPTIONS.map(({ time, label, id }) => (
        <SelectItem key={time} value={time} data-testid={`${testId}-option-${id}`}>
          {label}
        </SelectItem>
      )),
    [testId],
  );
  return (
    <Select value={value} onValueChange={onChange} onOpenChange={setOpen} disabled={disabled}>
      <SelectTrigger
        size="sm"
        className={narrow ? 'w-auto min-w-22' : 'w-auto min-w-28'}
        aria-label={label}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
        data-testid={testId}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
      >
        <SelectValue>{LABELS.get(value)}</SelectValue>
      </SelectTrigger>
      <SelectContent position="popper" sideOffset={4} className="max-h-72">
        {open || focused ? options : null}
      </SelectContent>
    </Select>
  );
}
