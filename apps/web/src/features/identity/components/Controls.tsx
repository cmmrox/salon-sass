'use client';
import { useTranslations } from 'next-intl';
import { REGEXP_ONLY_DIGITS } from 'input-otp';
import { useState, type ComponentProps, type ReactNode } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button as UiButton } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import { InputOTP, InputOTPGroup, InputOTPSlot } from '@/components/ui/input-otp';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

const controlTestId = (name: string) => `account-${name.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()}`;

/** A feather glyph drawn as a mask in the current text colour (CR-012). */
export const glyph = 'inline-block size-icon shrink-0 bg-current mask-contain mask-center mask-no-repeat';

type FieldText = { name: string; label: string; error?: boolean; login?: boolean; description?: ReactNode };

/** The refused-input message under a field, tied to it by `aria-describedby`; the page's Alert announces the refusal. */
function Refused({ name }: { name: string }) {
  const t = useTranslations('account');
  return (
    <FieldError id={`${name}-error`} role={undefined} data-testid={`${controlTestId(name)}-error`}>
      {t('fieldError')}
    </FieldError>
  );
}

const describedBy = (name: string, error?: boolean, description?: ReactNode) =>
  [description ? `${name}-description` : '', error ? `${name}-error` : ''].filter(Boolean).join(' ') || undefined;

/** A labelled text field with its help and its refused-input message (shadcn/ui Field). `login` is the staff sign-in's larger field (CR-012). */
export function TextField({
  name,
  label,
  error,
  login = false,
  description,
  ...props
}: Omit<ComponentProps<typeof Input>, 'size' | 'name'> & FieldText) {
  return (
    <Field data-invalid={error || undefined}>
      <FieldLabel size={login ? 'login' : 'default'} htmlFor={name}>
        {label}
      </FieldLabel>
      <Input
        {...props}
        size={login ? 'login' : 'default'}
        id={name}
        name={name}
        data-testid={controlTestId(name)}
        aria-invalid={Boolean(error)}
        aria-describedby={describedBy(name, error, description)}
      />
      {description && <FieldDescription id={`${name}-description`}>{description}</FieldDescription>}
      {error && <Refused name={name} />}
    </Field>
  );
}

/**
 * A password field with the visibility toggle drawn on it (shadcn/ui Input Group). The toggle is
 * `<test id>-toggle`, named "Show password"/"Hide password", pressed while the password is shown.
 * Pass `shown` and `onShownChange` to control it (the sign-in hides it again after each attempt).
 */
export function PasswordField({
  name,
  label,
  error,
  login = false,
  description,
  shown,
  onShownChange,
  ...props
}: Omit<ComponentProps<typeof Input>, 'size' | 'name' | 'type'> &
  FieldText & { shown?: boolean; onShownChange?: (shown: boolean) => void }) {
  const t = useTranslations('account');
  const [ownShown, setOwnShown] = useState(false);
  const visible = shown ?? ownShown;
  const toggle = () => {
    onShownChange?.(!visible);
    if (shown === undefined) setOwnShown(!visible);
  };
  return (
    <Field data-invalid={error || undefined}>
      <FieldLabel size={login ? 'login' : 'default'} htmlFor={name}>
        {label}
      </FieldLabel>
      <InputGroup size={login ? 'login' : 'default'}>
        <InputGroupInput
          {...props}
          type={visible ? 'text' : 'password'}
          size={login ? 'login' : 'default'}
          id={name}
          name={name}
          data-testid={controlTestId(name)}
          aria-invalid={Boolean(error)}
          aria-describedby={describedBy(name, error, description)}
        />
        <InputGroupAddon align="inline-end">
          <InputGroupButton
            size="icon-sm"
            className={cn(
              'text-muted-foreground',
              login && 'min-w-(--size-login-toggle) rounded-(--radius-login-field)',
            )}
            data-testid={`${controlTestId(name)}-toggle`}
            aria-label={visible ? t('hidePassword') : t('showPassword')}
            aria-pressed={visible}
            aria-controls={name}
            onClick={toggle}
          >
            <span
              className={`${glyph} ${visible ? 'mask-(--icon-eye-off)' : 'mask-(--icon-eye)'}`}
              aria-hidden="true"
            />
          </InputGroupButton>
        </InputGroupAddon>
      </InputGroup>
      {description && <FieldDescription id={`${name}-description`}>{description}</FieldDescription>}
      {error && <Refused name={name} />}
    </Field>
  );
}

/**
 * The six-digit authenticator code (shadcn/ui Input OTP): one real input, `account-code`, named by
 * its label, digits only, drawn as six slots.
 */
export function CodeField({
  name = 'code',
  label,
  error,
  login = false,
  description,
  value,
  onChange,
  disabled,
}: Omit<FieldText, 'name'> & { name?: string; value: string; onChange: (value: string) => void; disabled?: boolean }) {
  return (
    <Field data-invalid={error || undefined}>
      <FieldLabel size={login ? 'login' : 'default'} htmlFor={name}>
        {label}
      </FieldLabel>
      <InputOTP
        id={name}
        name={name}
        data-testid={controlTestId(name)}
        maxLength={6}
        pattern={REGEXP_ONLY_DIGITS}
        inputMode="numeric"
        autoComplete="one-time-code"
        required
        value={value}
        onChange={onChange}
        disabled={disabled}
        aria-invalid={Boolean(error)}
        aria-describedby={describedBy(name, error, description)}
      >
        <InputOTPGroup>
          {[0, 1, 2, 3, 4, 5].map((index) => (
            <InputOTPSlot key={index} index={index} size={login ? 'login' : 'default'} aria-invalid={Boolean(error)} />
          ))}
        </InputOTPGroup>
      </InputOTP>
      {description && <FieldDescription id={`${name}-description`}>{description}</FieldDescription>}
      {error && <Refused name={name} />}
    </Field>
  );
}

/** A choice from a list (shadcn/ui Select) with its label; its trigger carries the test id, each option `<optionTestId>-<value>`. */
export function ChoiceField({
  id,
  label,
  testId,
  optionTestId,
  value,
  options,
  onChange,
  disabled,
}: {
  id: string;
  label: string;
  testId: string;
  optionTestId: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  return (
    <Field>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Select value={value} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger id={id} data-testid={testId}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent position="popper" sideOffset={4}>
          {options.map((option) => (
            <SelectItem
              key={option.value}
              value={option.value}
              data-testid={`${optionTestId}-${option.value.toLowerCase()}`}
            >
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </Field>
  );
}

/** An account action: the brand's pill, an ink outline (`secondary`) or the negative outline (`danger`). */
export function Button({
  testId,
  secondary,
  danger,
  ...props
}: ComponentProps<typeof UiButton> & { testId: string; secondary?: boolean; danger?: boolean }) {
  return (
    <UiButton {...props} variant={danger ? 'destructive' : secondary ? 'outline' : 'default'} data-testid={testId} />
  );
}

/** One white card of the workspace, with its heading. */
export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card asChild className="p-4 rail:p-6">
      <section>
        <CardTitle asChild className="text-large leading-heading wrap-anywhere">
          <h2>{title}</h2>
        </CardTitle>
        {children}
      </section>
    </Card>
  );
}

/** A check box beside its label (shadcn/ui Field, horizontal); the label is the 44px target. */
export function Check({
  name,
  id = name,
  label,
  checked,
  onChange,
  disabled,
  className,
}: {
  name: string;
  id?: string;
  label: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
  className?: string;
}) {
  return (
    <Field orientation="horizontal" data-disabled={disabled || undefined} className={className}>
      <Checkbox
        id={id}
        disabled={disabled}
        data-testid={controlTestId(name)}
        checked={checked}
        onCheckedChange={(state) => onChange(state === true)}
      />
      <FieldLabel htmlFor={id} className="min-h-touch justify-center text-body font-normal">
        {label}
      </FieldLabel>
    </Field>
  );
}

/** The page this member may not see: an Alert (the server's denial and the client's after a role change). */
export function Denied() {
  const t = useTranslations('account');
  return (
    <Alert variant="notice" role="alert" data-testid="account-denied">
      {t('denied')}
    </Alert>
  );
}

/** Data still pending after the salon's page is shown; `inline` sits inside a section's card. */
export function AccountLoading({ inline = false }: { inline?: boolean }) {
  const t = useTranslations('account');
  const lines = [1, 2, 3].map((id) => <Skeleton key={id} className="h-field" aria-hidden="true" />);
  if (inline)
    return (
      <div className="flex min-w-0 flex-col gap-3" role="status" data-testid="account-list-loading">
        <p>{t('listLoading')}</p>
        {lines}
      </div>
    );
  return (
    <Card className="p-4 rail:p-6" role="status" data-testid="account-loading">
      <p>{t('loading')}</p>
      {lines}
    </Card>
  );
}
