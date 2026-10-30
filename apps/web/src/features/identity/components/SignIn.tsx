'use client';
import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { useRouter } from 'next/navigation';
import type { Kind } from '../types';
import { IdentityFailure, identityRequest, supportsLocks, withIdentityLock } from '../client/session';
import { formatInstant } from '@salon/format';
import { Alert } from '@/components/ui/alert';
import { Button as UiButton } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Spinner } from '@/components/ui/spinner';
import { Button, CodeField, glyph, PasswordField, TextField } from './Controls';

const secret = 'rounded-field bg-background p-3 font-mono text-small whitespace-pre-wrap wrap-anywhere';

type Step = 'initial' | 'enroll_mfa' | 'verify_mfa';

/**
 * Staff sign-in: the password, then the authenticator's enrolment or its code. Longer than 200
 * lines: the steps share one challenge, cross-tab lock and refusal state.
 */
export function SignIn({ kind, timeZone }: { kind: Kind; timeZone: string }) {
  const t = useTranslations('account');
  const router = useRouter();
  const [values, setValues] = useState({ identifier: '', password: '', code: '' });
  const [showPassword, setShowPassword] = useState(false);
  const [passwordHelp, setPasswordHelp] = useState(false);
  const [step, setStep] = useState<Step>('initial');
  const [expiry, setExpiry] = useState('');
  const [setup, setSetup] = useState<{ secret?: string; otpauthUri?: string }>({});
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<IdentityFailure | null>(null);
  // Unknown until the browser is inspected: controls stay disabled, but no unsupported warning flashes.
  const [supported, setSupported] = useState<boolean | null>(null);
  const [wait, setWait] = useState(0);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    Promise.resolve().then(() => setSupported(supportsLocks()));
  }, []);
  useEffect(() => {
    if (!wait) return;
    const id = setTimeout(() => setWait(wait - 1), 1000);
    return () => clearTimeout(id);
  }, [wait]);
  const field = (name: 'identifier' | 'password') => ({
    value: values[name],
    onChange: (e: React.ChangeEvent<HTMLInputElement>) => setValues({ ...values, [name]: e.target.value }),
    error: failure?.fields.includes(name),
  });
  async function submit() {
    setBusy(true);
    setFailure(null);
    try {
      await withIdentityLock(kind, async () => {
        if (step === 'initial') {
          const result = await identityRequest<{ state: string; expiresAt?: string }>(kind, 'sign-in', {
            identifier: values.identifier,
            password: values.password,
            deviceLabel: t('defaultDevice'),
          });
          setValues((v) => ({ ...v, password: '' }));
          setShowPassword(false);
          if (result.state === 'enroll_mfa' || result.state === 'verify_mfa') {
            setStep(result.state);
            setExpiry(result.expiresAt ?? '');
            if (result.state === 'enroll_mfa') setSetup(await identityRequest(kind, 'mfa-enroll', {}));
          } else {
            router.replace('/workspace/account');
            router.refresh();
          }
        } else {
          await identityRequest(kind, 'mfa-verify', { code: values.code });
          setSetup({});
          setStep('initial');
          setExpiry('');
          setValues((v) => ({ ...v, code: '', password: '' }));
          router.replace('/workspace/account');
          router.refresh();
        }
      });
    } catch (e) {
      const f = e instanceof IdentityFailure ? e : new IdentityFailure('service_unavailable');
      setFailure(f);
      setWait(f.retryAfter);
      setShowPassword(false);
      setValues((v) => ({ ...v, password: '', code: '' }));
    } finally {
      setBusy(false);
    }
  }
  const message = failure
    ? t.has(failure.code as 'service_unavailable')
      ? t(failure.code as 'service_unavailable')
      : t('service_unavailable')
    : '';
  return (
    <Card
      asChild
      className="block w-full max-w-(--size-login-card) gap-0 rounded-(--radius-login-card) border border-border bg-surface-translucent px-(--space-login-card-inline) py-(--space-login-card-block) shadow-(--shadow-login-card) max-login:px-6 max-login:py-8"
    >
      <section data-testid="staff-sign-in-card" aria-labelledby="staff-sign-in-title">
        <header className="mb-(--space-login-form-top) flex flex-col gap-(--space-login-heading-gap) text-center">
          <h1
            id="staff-sign-in-title"
            className="text-login-title leading-heading font-normal tracking-normal wrap-anywhere max-login:text-login-welcome"
            data-testid="account-title"
          >
            {t('staffTitle')}
          </h1>
          <h2 className="text-login-welcome leading-body font-normal tracking-normal wrap-anywhere max-login:text-title">
            {step === 'initial' ? t('welcomeBack') : t('mfaTitle')}
          </h2>
          {step === 'initial' && (
            <p className="text-login-help text-muted-foreground max-login:text-body">{t('signInHelp')}</p>
          )}
        </header>
        {supported === false && (
          <Alert variant="notice" role="status" className="mb-4" data-testid="account-browser-support">
            {t('browser_unsupported')}
          </Alert>
        )}
        {failure && (
          <Alert variant="notice" className="mb-4" data-testid="account-error">
            {message}
          </Alert>
        )}
        {wait > 0 && (
          <Alert variant="notice" role="status" className="mb-4" data-testid="account-retry-wait">
            {t('wait', { seconds: wait })}
          </Alert>
        )}
        <form
          className="min-w-0"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <FieldGroup className="gap-6">
            {step === 'initial' && (
              <>
                <TextField
                  login
                  name="identifier"
                  label={t('identifier')}
                  autoComplete="username"
                  required
                  {...field('identifier')}
                />
                <PasswordField
                  login
                  name="password"
                  label={t('password')}
                  autoComplete="current-password"
                  required
                  shown={showPassword}
                  onShownChange={setShowPassword}
                  {...field('password')}
                />
                <div className="-mt-5 mb-0 flex justify-end">
                  <UiButton
                    type="button"
                    variant="link"
                    size="link"
                    data-testid="account-forgot-password"
                    aria-expanded={passwordHelp}
                    aria-controls="account-password-help"
                    onClick={() => setPasswordHelp(!passwordHelp)}
                  >
                    {t('forgotPassword')}
                  </UiButton>
                </div>
                {passwordHelp && (
                  <Alert
                    id="account-password-help"
                    data-testid="account-password-help"
                    role="status"
                    className="block rounded-(--radius-login-field) p-3 text-small"
                  >
                    {t('passwordHelp')}
                  </Alert>
                )}
              </>
            )}
            {step !== 'initial' && (
              <>
                {step === 'enroll_mfa' && (
                  <>
                    <p>{t('mfaHelp')}</p>
                    <Alert variant="notice" role={undefined} className="text-small" data-testid="account-mfa-notice">
                      {t('mfaNotice')}
                    </Alert>
                    <Field>
                      <FieldLabel size="login" htmlFor="account-setup-key">
                        {t('setupKey')}
                      </FieldLabel>
                      <output id="account-setup-key" className={secret} data-testid="account-setup-key">
                        {setup.secret}
                      </output>
                    </Field>
                    <Field>
                      <FieldLabel size="login" htmlFor="account-setup-uri">
                        {t('setupUri')}
                      </FieldLabel>
                      <output id="account-setup-uri" className={secret} data-testid="account-setup-uri">
                        {setup.otpauthUri}
                      </output>
                    </Field>
                    <Button
                      type="button"
                      testId="account-copy-key"
                      secondary
                      onClick={() => {
                        void navigator.clipboard.writeText(setup.secret ?? '').then(() => setCopied(true));
                      }}
                    >
                      {t('copyKey')}
                    </Button>
                    {copied && (
                      <Alert variant="success" role="status" data-testid="account-copied">
                        {t('copied')}
                      </Alert>
                    )}
                  </>
                )}
                <CodeField
                  login
                  label={t('code')}
                  value={values.code}
                  onChange={(code) => setValues({ ...values, code })}
                  error={failure?.fields.includes('code')}
                  description={
                    <>
                      {expiry && (
                        <span className="block" data-testid="account-code-expiry">
                          {t('expires', { time: formatInstant(expiry, timeZone) })}
                        </span>
                      )}
                      <span className="block">{t('codeHelp')}</span>
                    </>
                  }
                />
              </>
            )}
            <Button
              type="submit"
              testId="account-sign-in-submit"
              className="min-h-(--size-login-field) rounded-(--radius-login-field) py-2 text-login-action"
              disabled={busy || !supported || wait > 0}
              aria-busy={busy || undefined}
            >
              {busy ? (
                <>
                  <Spinner />
                  {t('loading')}
                </>
              ) : step !== 'initial' ? (
                t('verify')
              ) : (
                t('signIn')
              )}
            </Button>
            {step !== 'initial' && (
              <Button
                type="button"
                testId="account-restart-sign-in"
                secondary
                disabled={busy}
                onClick={() => {
                  setStep('initial');
                  setSetup({});
                  setFailure(null);
                }}
              >
                {t('signIn')}
              </Button>
            )}
          </FieldGroup>
        </form>
        <p
          className="mt-8 flex items-center justify-center gap-3 text-center text-body text-muted-foreground max-login:text-small"
          data-testid="staff-sign-in-secure"
        >
          <span className={`${glyph} mask-(--icon-lock)`} aria-hidden="true" />
          {t('secureTeam')}
        </p>
      </section>
    </Card>
  );
}
