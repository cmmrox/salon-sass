'use client';
import { REGEXP_ONLY_DIGITS } from 'input-otp';
import { useTranslations } from 'next-intl';
import { useState, type ComponentProps, type ReactNode } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import { InputOTP, InputOTPGroup, InputOTPSlot } from '@/components/ui/input-otp';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { Eye, EyeOff } from 'lucide-react';
import type { OperatorFailure } from '../client/operator-session';

/** A console test id: `console-<kebab name>`. */
export const consoleTestId = (name: string) => `console-${name.replace(/[.]/g, '-').replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()}`;

type FieldProps = { name: string; label: string; description?: ReactNode; error?: boolean };

const describedBy = (id: string, description?: ReactNode, error?: boolean) => [description ? `${id}-description` : '', error ? `${id}-error` : ''].filter(Boolean).join(' ') || undefined;

function Refused({ id }: { id: string }) {
  const t = useTranslations('console');
  return <FieldError id={`${id}-error`} role={undefined} data-testid={`${id}-error`}>{t('fieldError')}</FieldError>;
}

/** A labelled text input with its help and refused-input message (shadcn/ui Field and Input). */
export function ConsoleTextField({ name, label, description, error, ...props }: Omit<ComponentProps<typeof Input>, 'size' | 'name'> & FieldProps) {
  const id = consoleTestId(name);
  return (
    <Field data-invalid={error || undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Input {...props} id={id} name={name} data-testid={id} aria-invalid={Boolean(error)} aria-describedby={describedBy(id, description, error)} />
      {description && <FieldDescription id={`${id}-description`}>{description}</FieldDescription>}
      {error && <Refused id={id} />}
    </Field>
  );
}

/** Multi-line plain text (shadcn/ui Textarea). */
export function ConsoleTextArea({ name, label, description, error, ...props }: ComponentProps<typeof Textarea> & FieldProps) {
  const id = consoleTestId(name);
  return (
    <Field data-invalid={error || undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Textarea {...props} id={id} name={name} data-testid={id} aria-invalid={Boolean(error)} aria-describedby={describedBy(id, description, error)} />
      {description && <FieldDescription id={`${id}-description`}>{description}</FieldDescription>}
      {error && <Refused id={id} />}
    </Field>
  );
}

/** A password with its visibility toggle (shadcn/ui Input Group); `<id>-toggle` is pressed while shown. */
export function ConsolePasswordField({ name, label, description, error, ...props }: Omit<ComponentProps<typeof Input>, 'size' | 'name' | 'type'> & FieldProps) {
  const t = useTranslations('console');
  const [shown, setShown] = useState(false);
  const id = consoleTestId(name);
  return (
    <Field data-invalid={error || undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <InputGroup>
        <InputGroupInput {...props} id={id} name={name} type={shown ? 'text' : 'password'} data-testid={id} aria-invalid={Boolean(error)} aria-describedby={describedBy(id, description, error)} />
        <InputGroupAddon align="inline-end">
          <InputGroupButton size="icon-sm" className="text-muted-foreground" data-testid={`${id}-toggle`} aria-label={shown ? t('hidePassword') : t('showPassword')} aria-pressed={shown} aria-controls={id} onClick={() => setShown(!shown)}>
            {shown ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
          </InputGroupButton>
        </InputGroupAddon>
      </InputGroup>
      {description && <FieldDescription id={`${id}-description`}>{description}</FieldDescription>}
      {error && <Refused id={id} />}
    </Field>
  );
}

/** The six-digit authenticator code (shadcn/ui Input OTP). */
export function ConsoleCodeField({ label, description, error, value, onChange }: { label: string; description?: ReactNode; error?: boolean; value: string; onChange: (value: string) => void }) {
  const id = 'console-code';
  return (
    <Field data-invalid={error || undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <InputOTP id={id} name="code" data-testid={id} maxLength={6} pattern={REGEXP_ONLY_DIGITS} inputMode="numeric" autoComplete="one-time-code" required value={value} onChange={onChange} aria-invalid={Boolean(error)} aria-describedby={describedBy(id, description, error)}>
        <InputOTPGroup>{[0, 1, 2, 3, 4, 5].map((index) => <InputOTPSlot key={index} index={index} aria-invalid={Boolean(error)} />)}</InputOTPGroup>
      </InputOTP>
      {description && <FieldDescription id={`${id}-description`}>{description}</FieldDescription>}
      {error && <Refused id={id} />}
    </Field>
  );
}

/** A choice from a list (shadcn/ui Select): the trigger carries the test id, each option `<id>-option-<value>`. */
export function ConsoleChoiceField({ name, label, description, error, value, options, placeholder, onChange, disabled }: FieldProps & { value: string; options: { value: string; label: string }[]; placeholder?: string; onChange: (value: string) => void; disabled?: boolean }) {
  const id = consoleTestId(name);
  return (
    <Field data-invalid={error || undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Select value={value} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger id={id} data-testid={id} aria-invalid={Boolean(error)} aria-describedby={describedBy(id, description, error)}>
          <SelectValue placeholder={placeholder} />
        </SelectTrigger>
        <SelectContent position="popper" sideOffset={4}>
          {options.map((option) => <SelectItem key={option.value} value={option.value} data-testid={`${id}-option-${option.value}`}>{option.label}</SelectItem>)}
        </SelectContent>
      </Select>
      {description && <FieldDescription id={`${id}-description`}>{description}</FieldDescription>}
      {error && <Refused id={id} />}
    </Field>
  );
}

/** The translated message for a refused console operation; never a raw code. */
export function useFailureMessage() {
  const t = useTranslations('console');
  return (failure: OperatorFailure) => (t.has(failure.code as 'service_unavailable') ? t(failure.code as 'service_unavailable') : t('service_unavailable'));
}

/** A refusal or outage, with a retry when one is useful. Announced to assistive technology. */
export function ConsoleFailure({ failure, onRetry, busy }: { failure: OperatorFailure; onRetry?: () => void; busy?: boolean }) {
  const t = useTranslations('console');
  const message = useFailureMessage();
  return (
    <Alert variant="notice" role="alert" data-testid="console-error" data-code={failure.code}>
      <p>{message(failure)}</p>
      {failure.retryAfter > 0 && <p data-testid="console-retry-wait">{t('wait', { seconds: failure.retryAfter })}</p>}
      {onRetry && <Button variant="outline" size="sm" className="mt-2" data-testid="console-retry" disabled={busy} onClick={onRetry}>{t('retry')}</Button>}
    </Alert>
  );
}

/** Support (or anyone without management permissions): a clear denial, never dead links. */
export function ConsoleDenied() {
  const t = useTranslations('console');
  return <Alert variant="notice" role="alert" data-testid="console-denied">{t('denied')}</Alert>;
}

/** Content still loading after the page is shown: a skeleton shaped like the rows. */
export function ConsoleLoading({ rows = 3 }: { rows?: number }) {
  const t = useTranslations('console');
  return (
    <Card className="gap-3 p-4 rail:p-6" role="status" data-testid="console-loading">
      <p className="m-0">{t('listLoading')}</p>
      {Array.from({ length: rows }, (_, index) => <Skeleton key={index} className="h-field" aria-hidden="true" />)}
    </Card>
  );
}

/** Whether a failure leaves the outcome of a mutation unknown, so the same request may be retried. */
export function uncertain(failure: OperatorFailure): boolean {
  return failure.code === 'service_unavailable' && (failure.status === 0 || failure.status >= 500);
}
