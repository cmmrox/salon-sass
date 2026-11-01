'use client';
import { formatClock } from '@salon/format';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { QUARTER_HOURS } from '../client/hours-form';

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
  return (
    <Select value={value} onValueChange={onChange} disabled={disabled}>
      <SelectTrigger
        size="sm"
        className={narrow ? 'w-auto min-w-22' : 'w-auto min-w-28'}
        aria-label={label}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
        data-testid={testId}
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent position="popper" sideOffset={4} className="max-h-72">
        {QUARTER_HOURS.map((time) => (
          <SelectItem key={time} value={time} data-testid={`${testId}-option-${time.replace(':', '')}`}>
            {formatClock(time)}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
