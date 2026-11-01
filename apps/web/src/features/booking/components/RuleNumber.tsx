'use client';
import { Field, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';

/**
 * A typed whole number (F04-S04, the prototype's `num`): a Field with its Input and the unit beside it (`%`,
 * `Rs.`), and the prototype's message beside the field when the value breaks its rule, tied to the input.
 * `context` completes the accessible name, after the visible label, where that label repeats ("Share kept" twice).
 */
export function RuleNumber({
  id,
  label,
  context,
  unit,
  value,
  error,
  onChange,
  disabled,
  testId,
}: {
  id: string;
  label: string;
  context?: string;
  unit: string;
  value: string;
  error: string | null;
  onChange: (value: string) => void;
  disabled: boolean;
  testId: string;
}) {
  const errorId = `${id}-error`;
  const unitId = `${id}-unit`;
  return (
    <Field data-invalid={error ? true : undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <div className="flex items-center gap-2">
        <Input
          id={id}
          inputMode="numeric"
          autoComplete="off"
          className="max-w-36"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          disabled={disabled}
          aria-label={context ? `${label} ${context}` : undefined}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${unitId} ${errorId}` : unitId}
          data-testid={testId}
        />
        <span id={unitId} className="text-small text-foreground">
          {unit}
        </span>
      </div>
      {error && (
        <FieldError id={errorId} data-testid={`${testId}-error`}>
          {error}
        </FieldError>
      )}
    </Field>
  );
}
