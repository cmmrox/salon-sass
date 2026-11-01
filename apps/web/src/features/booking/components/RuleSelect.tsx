'use client';
import { Field, FieldLabel } from '@/components/ui/field';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

/**
 * One rule chosen from a list (F04-S04, the prototype's `sel`): a Field whose label names the Select (no
 * native control). Each option carries the test id `<testId>-option-<value>`.
 */
export function RuleSelect({
  id,
  label,
  value,
  options,
  onChange,
  disabled,
  testId,
}: {
  id: string;
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
  disabled: boolean;
  testId: string;
}) {
  return (
    <Field>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Select value={value} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger id={id} className="w-full" data-testid={testId}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent position="popper" sideOffset={4}>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value} data-testid={`${testId}-option-${option.value}`}>
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </Field>
  );
}
