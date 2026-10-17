'use client';
import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { useRouter } from 'next/navigation';
import type { Kind } from '@/lib/identity/server';
import { IdentityFailure, identityRequest, supportsLocks, withIdentityLock } from '@/lib/identity/browser';
import { formatInstant } from '@/lib/format';
import { Button, Field } from './Controls';
import styles from './Account.module.css';
import login from './StaffSignIn.module.css';

type Step = 'initial' | 'enroll_mfa' | 'verify_mfa';
export function SignIn({ kind, timeZone }: { kind: Kind; timeZone: string }) {
  const t = useTranslations('account'); const router = useRouter();
  const [values, setValues] = useState({ identifier: '', password: '', code: '' });
  const [showPassword, setShowPassword] = useState(false); const [passwordHelp, setPasswordHelp] = useState(false);
  const [step, setStep] = useState<Step>('initial'); const [expiry, setExpiry] = useState('');
  const [setup, setSetup] = useState<{ secret?: string; otpauthUri?: string }>({});
  const [busy, setBusy] = useState(false); const [failure, setFailure] = useState<IdentityFailure | null>(null);
  // Unknown until the browser is inspected: controls stay disabled, but no unsupported warning flashes.
  const [supported, setSupported] = useState<boolean | null>(null); const [wait, setWait] = useState(0); const [copied, setCopied] = useState(false);
  useEffect(() => { Promise.resolve().then(() => setSupported(supportsLocks())); }, []);
  useEffect(() => { if (!wait) return; const id = setTimeout(() => setWait(wait - 1), 1000); return () => clearTimeout(id); }, [wait]);
  const field = (name: keyof typeof values) => ({ value: values[name], onChange: (e: React.ChangeEvent<HTMLInputElement>) => setValues({ ...values, [name]: e.target.value }), error: failure?.fields.includes(name) });
  async function submit() {
    setBusy(true); setFailure(null);
    try {
      await withIdentityLock(kind, async () => {
        if (step === 'initial') {
          const result = await identityRequest<{ state: string; expiresAt?: string }>(kind, 'sign-in', { identifier: values.identifier, password: values.password, deviceLabel: t('defaultDevice') });
          setValues((v) => ({ ...v, password: '' })); setShowPassword(false);
          if (result.state === 'enroll_mfa' || result.state === 'verify_mfa') {
            setStep(result.state); setExpiry(result.expiresAt ?? '');
            if (result.state === 'enroll_mfa') setSetup(await identityRequest(kind, 'mfa-enroll', {}));
          } else { router.replace('/workspace/account'); router.refresh(); }
        } else {
          await identityRequest(kind, 'mfa-verify', { code: values.code });
          setSetup({}); setStep('initial'); setExpiry(''); setValues((v) => ({ ...v, code: '', password: '' }));
          router.replace('/workspace/account'); router.refresh();
        }
      });
    } catch (e) { const f = e instanceof IdentityFailure ? e : new IdentityFailure('service_unavailable'); setFailure(f); setWait(f.retryAfter); setShowPassword(false); setValues((v) => ({ ...v, password: '', code: '' })); }
    finally { setBusy(false); }
  }
  const message = failure ? (t.has(failure.code as 'service_unavailable') ? t(failure.code as 'service_unavailable') : t('service_unavailable')) : '';
  return <section className={login.card} data-testid="staff-sign-in-card" aria-labelledby="staff-sign-in-title">
    <header className={login.heading}>
      <h1 id="staff-sign-in-title" data-testid="account-title">{t('staffTitle')}</h1>
      <h2>{step === 'initial' ? t('welcomeBack') : t('mfaTitle')}</h2>
      {step === 'initial' && <p>{t('signInHelp')}</p>}
    </header>
    {supported === false && <p role="status" className={styles.feedback} data-testid="account-browser-support">{t('browser_unsupported')}</p>}
    {failure && <p role="alert" className={styles.feedback} data-testid="account-error">{message}</p>}
    {wait > 0 && <p role="status" data-testid="account-retry-wait">{t('wait', { seconds: wait })}</p>}
    <form className={`${styles.form} ${login.form}`} onSubmit={(e) => { e.preventDefault(); void submit(); }}>
      {step === 'initial' && <><Field name="identifier" label={t('identifier')} autoComplete="username" required {...field('identifier')} /><div className={login.passwordField}><Field name="password" label={t('password')} type={showPassword ? 'text' : 'password'} autoComplete="current-password" required {...field('password')} /><button type="button" className={login.passwordToggle} data-testid="account-password-toggle" aria-label={showPassword ? t('hidePassword') : t('showPassword')} aria-pressed={showPassword} aria-controls="password" onClick={() => setShowPassword(!showPassword)}><span className={`${login.icon} ${showPassword ? login.eyeOff : login.eye}`} aria-hidden="true" /></button></div><div className={login.recovery}><button type="button" data-testid="account-forgot-password" aria-expanded={passwordHelp} aria-controls="account-password-help" onClick={() => setPasswordHelp(!passwordHelp)}>{t('forgotPassword')}</button></div>{passwordHelp && <p id="account-password-help" data-testid="account-password-help" role="status" className={login.guidance}>{t('passwordHelp')}</p>}</>}
      {step !== 'initial' && <>
        {step === 'enroll_mfa' && <><p>{t('mfaHelp')}</p><p className={styles.muted}>{t('mfaNotice')}</p><label className={styles.field}>{t('setupKey')}<output className={styles.secret} data-testid="account-setup-key">{setup.secret}</output></label><label className={styles.field}>{t('setupUri')}<output className={styles.secret} data-testid="account-setup-uri">{setup.otpauthUri}</output></label><Button type="button" testId="account-copy-key" secondary onClick={() => { void navigator.clipboard.writeText(setup.secret ?? '').then(() => setCopied(true)); }}>{t('copyKey')}</Button>{copied && <p role="status">{t('copied')}</p>}</>}
        <Field name="code" label={t('code')} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required {...field('code')} />
        {expiry && <p data-testid="account-code-expiry">{t('expires', { time: formatInstant(expiry, timeZone) })}</p>}
        <p className={styles.muted}>{t('codeHelp')}</p>
      </>}
      <Button type="submit" testId="account-sign-in-submit" disabled={busy || !supported || wait > 0}>{busy ? t('loading') : step !== 'initial' ? t('verify') : t('signIn')}</Button>
      {step !== 'initial' && <Button type="button" testId="account-restart-sign-in" secondary disabled={busy} onClick={() => { setStep('initial'); setSetup({}); setFailure(null); }}>{t('signIn')}</Button>}
    </form>
    <p className={login.secure} data-testid="staff-sign-in-secure"><span className={`${login.icon} ${login.lock}`} aria-hidden="true" />{t('secureTeam')}</p>
  </section>;
}
