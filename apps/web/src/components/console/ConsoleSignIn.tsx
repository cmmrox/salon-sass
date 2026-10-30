'use client';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardDescription, CardTitle } from '@/components/ui/card';
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Spinner } from '@/components/ui/spinner';
import { formatInstant, viewerTimeZone } from '@/lib/format';
import { operatorRequest, OperatorFailure, supportsLocks, withOperatorLock } from '@/features/platform/client/operator-session';
import { ConsoleCodeField, ConsoleFailure, ConsolePasswordField, ConsoleTextField } from '@/features/platform/components/ConsoleControls';

const secret = 'rounded-field bg-background p-3 font-mono text-small whitespace-pre-wrap wrap-anywhere';
type Step = 'password' | 'enroll_mfa' | 'verify_mfa';

/**
 * Operator sign-in (docs/architecture/operator-console.md): email and password, then always MFA,
 * enrolling an authenticator on first sign-in. The setup key and link are shown only during that
 * step and never stored; tokens and the challenge credential stay in HttpOnly cookies. Every step
 * holds the cross-tab operator lock; a browser without Web Locks is told it cannot sign in.
 */
export function ConsoleSignIn() {
  const t = useTranslations('console');
  const router = useRouter();
  const [values, setValues] = useState({ email: '', password: '', code: '' });
  const [step, setStep] = useState<Step>('password');
  const [expiry, setExpiry] = useState('');
  const [setup, setSetup] = useState<{ secret?: string; otpauthUri?: string }>({});
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [supported, setSupported] = useState<boolean | null>(null);
  const [copied, setCopied] = useState(false);
  const [wait, setWait] = useState(0);
  useEffect(() => { Promise.resolve().then(() => setSupported(supportsLocks())); }, []);
  useEffect(() => { if (!wait) return; const id = setTimeout(() => setWait(wait - 1), 1000); return () => clearTimeout(id); }, [wait]);

  async function submit() {
    setBusy(true); setFailure(null);
    try {
      await withOperatorLock(async () => {
        if (step === 'password') {
          const result = await operatorRequest<{ state: Step; expiresAt?: string }>('sign-in', { email: values.email, password: values.password, deviceLabel: t('defaultDevice') });
          setValues((v) => ({ ...v, password: '' }));
          setStep(result.state); setExpiry(result.expiresAt ?? '');
          if (result.state === 'enroll_mfa') setSetup(await operatorRequest('mfa-enroll', {}));
        } else {
          await operatorRequest('mfa-verify', { code: values.code });
          setSetup({});
          router.replace('/console/salons'); router.refresh();
        }
      });
    } catch (error) {
      const f = error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
      setFailure(f); setWait(f.retryAfter);
      setValues((v) => ({ ...v, password: '', code: '' }));
    } finally { setBusy(false); }
  }

  function restart() { setStep('password'); setSetup({}); setFailure(null); setExpiry(''); setCopied(false); setValues((v) => ({ ...v, code: '', password: '' })); }

  return (
    <Card asChild className="mx-auto w-full max-w-(--size-login-card) gap-6 p-6 rail:p-8">
      <section aria-labelledby="console-sign-in-title" data-testid="console-sign-in-card">
        <header className="flex flex-col gap-2">
          <CardTitle asChild className="text-workspace-title leading-heading wrap-anywhere"><h1 id="console-sign-in-title" data-testid="console-sign-in-title">{step === 'password' ? t('signInTitle') : t('mfaTitle')}</h1></CardTitle>
          <CardDescription className="text-body">{step === 'password' ? t('signInHelp') : step === 'enroll_mfa' ? t('mfaEnrollHelp') : t('mfaVerifyHelp')}</CardDescription>
        </header>
        {supported === false && <Alert variant="notice" role="status" data-testid="console-browser-support">{t('browser_unsupported')}</Alert>}
        {failure && <ConsoleFailure failure={failure} />}
        <form className="min-w-0" noValidate onSubmit={(event) => { event.preventDefault(); void submit(); }}>
          <FieldGroup className="gap-5">
            {step === 'password' && <>
              <ConsoleTextField name="email" type="email" autoComplete="username" required label={t('email')} value={values.email} error={failure?.fields.includes('email')} onChange={(e) => setValues({ ...values, email: e.target.value })} />
              <ConsolePasswordField name="password" autoComplete="current-password" required label={t('password')} value={values.password} error={failure?.fields.includes('password')} onChange={(e) => setValues({ ...values, password: e.target.value })} />
            </>}
            {step === 'enroll_mfa' && setup.secret && <>
              <Alert variant="notice" role={undefined} className="text-small" data-testid="console-mfa-notice">{t('mfaNotice')}</Alert>
              <Field><FieldLabel htmlFor="console-setup-key">{t('setupKey')}</FieldLabel><output id="console-setup-key" className={secret} data-testid="console-setup-key">{setup.secret}</output></Field>
              <Field><FieldLabel htmlFor="console-setup-uri">{t('setupUri')}</FieldLabel><output id="console-setup-uri" className={secret} data-testid="console-setup-uri">{setup.otpauthUri}</output></Field>
              <Button type="button" variant="outline" data-testid="console-copy-key" onClick={() => { void navigator.clipboard?.writeText(setup.secret ?? '').then(() => setCopied(true)); }}>{t('copyKey')}</Button>
              {copied && <Alert variant="success" role="status" data-testid="console-copied">{t('copied')}</Alert>}
            </>}
            {step !== 'password' && <ConsoleCodeField label={t('code')} value={values.code} error={failure?.fields.includes('code')} onChange={(code) => setValues({ ...values, code })} description={expiry ? <span data-testid="console-code-expiry">{t('expires', { time: formatInstant(expiry, viewerTimeZone()) })}</span> : undefined} />}
            {wait > 0 && <Alert variant="notice" role="status" data-testid="console-retry-wait">{t('wait', { seconds: wait })}</Alert>}
            <Button type="submit" data-testid="console-sign-in-submit" disabled={busy || !supported || wait > 0} aria-busy={busy || undefined}>{busy ? <><Spinner />{t('loading')}</> : step === 'password' ? t('signIn') : t('verify')}</Button>
            {step !== 'password' && <Button type="button" variant="outline" data-testid="console-restart" disabled={busy} onClick={restart}>{t('startAgain')}</Button>}
          </FieldGroup>
        </form>
      </section>
    </Card>
  );
}
