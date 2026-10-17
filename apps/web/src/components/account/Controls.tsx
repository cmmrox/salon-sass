'use client';
import { useTranslations } from 'next-intl';
import type { ComponentProps, InputHTMLAttributes, ReactNode } from 'react';
import { Button as UiButton } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

const controlTestId = (name: string) => `account-${name.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()}`;

/**
 * A labelled field with its refused-input message tied to it. `login` is the staff sign-in's larger
 * field (CR-012); `inputClassName` leaves room for a control drawn on the field.
 */
export function Field({ name, label, error, login = false, inputClassName, ...props }: Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> & { name: string; label: string; error?: boolean; login?: boolean; inputClassName?: string }) {
  const t = useTranslations('account');
  return (
    <Label size={login ? 'login' : 'default'} htmlFor={name}>
      {label}
      <Input {...props} size={login ? 'login' : 'default'} className={inputClassName} id={name} name={name} data-testid={controlTestId(name)} aria-invalid={Boolean(error)} aria-describedby={error ? `${name}-error` : undefined} />
      {error && <span id={`${name}-error`} className="text-destructive" data-testid={`${controlTestId(name)}-error`}>{t('fieldError')}</span>}
    </Label>
  );
}

/** An account action: the brand's pill, an ink outline (`secondary`) or the negative outline (`danger`). */
export function Button({ testId, secondary, danger, ...props }: ComponentProps<typeof UiButton> & { testId: string; secondary?: boolean; danger?: boolean }) {
  return <UiButton {...props} variant={danger ? 'destructive' : secondary ? 'outline' : 'default'} data-testid={testId} />;
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

/** A checkbox whose whole label is its 44px target. */
export function Check({ name, label, checked, onChange, disabled, className }: { name: string; label: string; checked: boolean; disabled?: boolean; onChange: (checked: boolean) => void; className?: string }) {
  return (
    <Label className={cn('min-h-touch flex-row items-center gap-3 text-body font-normal', className)}>
      <Checkbox disabled={disabled} data-testid={controlTestId(name)} checked={checked} onCheckedChange={(state) => onChange(state === true)} />
      {label}
    </Label>
  );
}

/** Data still pending after the salon's page is shown; `inline` sits inside a section's card. */
export function AccountLoading({ inline = false }: { inline?: boolean }) {
  const t = useTranslations('account');
  const lines = [1, 2, 3].map((id) => <Skeleton key={id} className="h-field" aria-hidden="true" />);
  if (inline) return <div className="flex min-w-0 flex-col gap-3" role="status" data-testid="account-list-loading"><p>{t('listLoading')}</p>{lines}</div>;
  return <Card className="p-4 rail:p-6" role="status" data-testid="account-loading"><p>{t('loading')}</p>{lines}</Card>;
}
