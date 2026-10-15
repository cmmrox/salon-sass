'use client';
import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { useRouter } from 'next/navigation';
import type { Kind } from '@/lib/identity/server';
import { IdentityFailure, identityRequest, supportsLocks, withIdentityLock } from '@/lib/identity/browser';
import { formatInstant } from '@/lib/format';
import { Button, Field, Section } from './Controls';
import styles from './Account.module.css';

type Step = 'initial' | 'enroll_mfa' | 'verify_mfa';
export function SignIn({ kind, timeZone }: { kind: Kind; timeZone: string }) {
  const t = useTranslations('account'); const router = useRouter();
  const [values, setValues] = useState({ identifier: '', password: '', code: '' });
  const [step, setStep] = useState<Step>('initial'); const [expiry, setExpiry] = useState('');
  const [setup, setSetup] = useState<{ secret?: string; otpauthUri?: string }>({});
  const [busy, setBusy] = useState(false); const [failure, setFailure] = useState<IdentityFailure | null>(null);
  const [supported, setSupported] = useState(false); const [wait, setWait] = useState(0); const [copied, setCopied] = useState(false);
  useEffect(() => { Promise.resolve().then(() => setSupported(supportsLocks())); }, []);
  useEffect(() => { if (!wait) return; const id = setTimeout(() => setWait(wait - 1), 1000); return () => clearTimeout(id); }, [wait]);
  const field = (name: keyof typeof values) => ({ value: values[name], onChange: (e: React.ChangeEvent<HTMLInputElement>) => setValues({ ...values, [name]: e.target.value }), error: failure?.fields.includes(name) });
  async function submit() {
    setBusy(true); setFailure(null);
    try {
      await withIdentityLock(kind, async () => {
        if (step === 'initial') {
          const result = await identityRequest<{ state: string; expiresAt?: string }>(kind, 'sign-in', { identifier: values.identifier, password: values.password, deviceLabel: t('defaultDevice') });
          setValues((v) => ({ ...v, password: '' }));
          if (result.state === 'enroll_mfa' || result.state === 'verify_mfa') {
            setStep(result.state); setExpiry(result.expiresAt ?? '');
            if (result.state === 'enroll_mfa') setSetup(await identityRequest(kind, 'mfa-enroll', {}));
          } else { router.replace('/workspace/account'); router.refresh(); }
        } else {
          await identityRequest(kind, 'mfa-verify', { code: values.code });
          setSetup({}); setValues((v) => ({ ...v, code: '', password: '' }));
          router.replace('/workspace/account'); router.refresh();
        }
      });
    } catch (e) { const f = e instanceof IdentityFailure ? e : new IdentityFailure('service_unavailable'); setFailure(f); setWait(f.retryAfter); setValues((v) => ({ ...v, password: '', code: '' })); }
    finally { setBusy(false); }
  }
  const message = failure ? (t.has(failure.code as 'service_unavailable') ? t(failure.code as 'service_unavailable') : t('service_unavailable')) : '';
  return <Section signIn title={step === 'enroll_mfa' || step === 'verify_mfa' ? t('mfaTitle') : t('signIn')}>
    {!supported && <p role="status" className={styles.feedback} data-testid="account-browser-support">{t('browser_unsupported')}</p>}
    {failure && <p role="alert" className={styles.feedback} data-testid="account-error">{message}</p>}
    {wait > 0 && <p role="status" data-testid="account-retry-wait">{t('wait', { seconds: wait })}</p>}
    <form className={styles.form} onSubmit={(e) => { e.preventDefault(); void submit(); }}>
      {step === 'initial' && <><Field name="identifier" label={t('identifier')} autoComplete="username" required {...field('identifier')} /><Field name="password" label={t('password')} type="password" autoComplete="current-password" required {...field('password')} /></>}
      {step !== 'initial' && <>
        {step === 'enroll_mfa' && <><p>{t('mfaHelp')}</p><p className={styles.muted}>{t('mfaNotice')}</p><label className={styles.field}>{t('setupKey')}<output className={styles.secret} data-testid="account-setup-key">{setup.secret}</output></label><label className={styles.field}>{t('setupUri')}<output className={styles.secret} data-testid="account-setup-uri">{setup.otpauthUri}</output></label><Button type="button" testId="account-copy-key" secondary onClick={() => { void navigator.clipboard.writeText(setup.secret ?? '').then(() => setCopied(true)); }}>{t('copyKey')}</Button>{copied && <p role="status">{t('copied')}</p>}</>}
        <Field name="code" label={t('code')} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required {...field('code')} />
        {expiry && <p data-testid="account-code-expiry">{t('expires', { time: formatInstant(expiry, timeZone) })}</p>}
        <p className={styles.muted}>{t('codeHelp')}</p>
      </>}
      <Button type="submit" testId="account-sign-in-submit" disabled={busy || !supported || wait > 0}>{busy ? t('loading') : step !== 'initial' ? t('verify') : t('signIn')}</Button>
      {step !== 'initial' && <Button type="button" testId="account-restart-sign-in" secondary disabled={busy} onClick={() => { setStep('initial'); setSetup({}); setFailure(null); }}>{t('signIn')}</Button>}
    </form>
  </Section>;
}
