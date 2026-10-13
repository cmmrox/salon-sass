'use client';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { components } from '@salon/api-contracts';
import type { Kind, Principal } from '@/lib/identity/server';
import { ensurePrincipal, identityRequest, IdentityFailure, principalInsideLock, supportsLocks, withIdentityLock } from '@/lib/identity/browser';
import { formatInstant, formatPhone } from '@/lib/format';
import { AccountLoading, Button, Check, Field, Section } from './Controls';
import styles from './Account.module.css';
type Profile = components['schemas']['Profile'];
type Staff = components['schemas']['StaffView'];
type Device = components['schemas']['SessionView'];
type Audit = components['schemas']['AuditView'];
type Page<T> = { items?: T[]; page?: number; size?: number; total?: number };
type View = 'account' | 'staff' | 'audit';
export function AccountScreen({ kind, initialPrincipal, timeZone, view = 'account' }: { kind: Kind; initialPrincipal: Principal | null; timeZone: string; view?: View }) {
  const t = useTranslations('account'); const router = useRouter();
  const [principal, setPrincipal] = useState(initialPrincipal); const [busy, setBusy] = useState(false); const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<IdentityFailure | null>(null); const [success, setSuccess] = useState(false); const [supported, setSupported] = useState(false);
  const [profile, setProfile] = useState<Profile | null>(null); const [devices, setDevices] = useState<Page<Device>>({}); const [staff, setStaff] = useState<Page<Staff>>({}); const [audit, setAudit] = useState<Page<Audit>>({}); const [page, setPage] = useState(0);
  const [password, setPassword] = useState({ oldPassword: '', newPassword: '' }); const [setup, setSetup] = useState<{ secret?: string; otpauthUri?: string }>({}); const [code, setCode] = useState('');
  const [confirm, setConfirm] = useState<{ action: string; body: unknown } | null>(null);
  const [newStaff, setNewStaff] = useState({ email: '', mobile: '', password: '', role: 'RECEPTIONIST', professionalGrant: false });
  async function load(nextPage = page) {
    const p = await ensurePrincipal(kind); setPrincipal(p);
    if (view === 'account') {
      if (kind === 'customer' && !profile) setProfile(await identityRequest(kind, 'profile'));
      setDevices(await identityRequest(kind, `sessions?page=${nextPage}`));
    } else if (view === 'staff') setStaff(await identityRequest(kind, `staff?page=${nextPage}`));
    else setAudit(await identityRequest(kind, `audit?page=${nextPage}`));
    setLoaded(true);
  }
  async function perform(work: () => Promise<void>) {
    setBusy(true); setError(null); setSuccess(false);
    try { await work(); setSuccess(true); }
    catch (failure) {
      const f = failure instanceof IdentityFailure ? failure : new IdentityFailure('service_unavailable'); setError(f);
      if (f.code === 'invalid_session') { router.replace(kind === 'staff' ? '/workspace/sign-in' : '/sign-in'); router.refresh(); }
    } finally { setBusy(false); }
  }
  useEffect(() => { let active = true; Promise.resolve().then(async () => { if (!active) return; setSupported(supportsLocks()); try { await load(0); } catch (failure) { if (active) { const f = failure instanceof IdentityFailure ? failure : new IdentityFailure('service_unavailable'); setError(f); if (f.code === 'invalid_session') { router.replace(kind === 'staff' ? '/workspace/sign-in' : '/sign-in'); router.refresh(); } } } }); return () => { active = false; }; /* First render loads live state; drafts are never overwritten by a tab event. */
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, view]);
  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return;
    const channel = new BroadcastChannel('salon:identity');
    channel.onmessage = (event) => { if (event.data?.kind === kind) void identityRequest<Principal>(kind, 'principal').then(setPrincipal).catch(() => { router.refresh(); }); };
    return () => channel.close();
  }, [kind, router]);
  async function mutate(action: string, body: unknown = {}) {
    await withIdentityLock(kind, async () => { await principalInsideLock(kind); await identityRequest(kind, action, body); });
    if (['logout', 'revoke-all', 'delete'].includes(action) || (action === 'revoke' && devices.items?.some((d) => d.current && d.id === (body as { id?: string }).id))) { router.replace(kind === 'staff' ? '/workspace/sign-in' : '/sign-in'); router.refresh(); return; }
    setConfirm(null); await load();
  }
  const feedback = <>{!supported && <p className={styles.feedback} role="status" data-testid="account-browser-support">{t('browser_unsupported')}</p>}{error && <div className={styles.feedback} role="alert" data-testid="account-error"><p>{t.has(error.code as 'service_unavailable') ? t(error.code as 'service_unavailable') : t('service_unavailable')}</p>{error.retryAfter > 0 && <p>{t('wait', { seconds: error.retryAfter })}</p>}<Button testId="account-retry" secondary disabled={busy} onClick={() => void perform(() => load())}>{t('retry')}</Button>{error.code === 'invalid_session' && <Link href={kind === 'staff' ? '/workspace/sign-in' : '/sign-in'} data-testid="account-sign-in-link" className={styles.link}>{t('signIn')}</Link>}</div>}{success && <p className={`${styles.feedback} ${styles.success}`} role="status" data-testid="account-success">{t('success')}</p>}</>;
  if (!principal || !loaded) return <>{feedback}<AccountLoading /></>;
  const required = view === 'staff' ? 'staff.list' : view === 'audit' ? 'audit.list' : 'session.manage';
  if (!principal.permissions.includes(required)) return <>{feedback}<p role="alert" data-testid="account-denied">{t('denied')}</p></>;
  const list = view === 'staff' ? staff : view === 'audit' ? audit : devices;
  const pager = <div className={styles.actions}><Button testId="account-page-previous" secondary disabled={busy || page === 0} onClick={() => void perform(async () => { await load(page - 1); setPage(page - 1); })}>{t('previous')}</Button><p data-testid="account-page">{t('page', { page: page + 1 })}</p><Button testId="account-page-next" secondary disabled={busy || (page + 1) * 10 >= (list.total ?? 0)} onClick={() => void perform(async () => { await load(page + 1); setPage(page + 1); })}>{t('next')}</Button></div>;
  return <>{feedback}
    <nav className={styles.nav} aria-label={t('staffTitle')}>{kind === 'staff' && <><Link href="/workspace/account" data-testid="workspace-account" className={styles.link}>{t('account')}</Link>{principal.permissions.includes('staff.list') && <Link href="/workspace/staff" className={styles.link} data-testid="workspace-staff">{t('staff')}</Link>}{principal.permissions.includes('audit.list') && <Link href="/workspace/audit" className={styles.link} data-testid="workspace-audit">{t('audit')}</Link>}</>}<Button testId="account-logout" disabled={busy || !supported} onClick={() => void perform(() => mutate('logout'))}>{t('logout')}</Button></nav>
    {confirm && <Section title={t('confirmation')}><p>{confirm.action === 'delete' ? t('deleteHelp') : confirm.action === 'staff-remove' ? t('confirmRemoval') : t('confirmRevoke')}</p><div className={styles.actions}><Button testId="account-confirm-action" danger disabled={busy || !supported} onClick={() => void perform(() => mutate(confirm.action, confirm.body))}>{confirm.action === 'delete' ? t('confirmDelete') : t('confirmRevoke')}</Button><Button testId="account-cancel-action" secondary disabled={busy} onClick={() => setConfirm(null)}>{t('cancel')}</Button></div></Section>}
    {view === 'account' && <>
      <Section title={t('permissions')}><p data-testid="account-scope">{t('scope', { scope: t(principal.scope === 'own' ? 'own' : 'salon') })}</p>{kind === 'staff' && <p data-testid="account-role">{t(principal.role as 'OWNER')}</p>}<ul data-testid="account-permissions">{principal.permissions.map((permission) => <li key={permission}>{t(('permissionLabels.' + permission.replaceAll('.', '_')) as 'permissionLabels.session_manage')}</li>)}</ul></Section>
      {kind === 'staff' && <>
        <Section title={t('passwordChange')}><form className={styles.form} onSubmit={(e) => { e.preventDefault(); void perform(async () => { try { await mutate('password', password); } finally { setPassword({ oldPassword: '', newPassword: '' }); } }); }}><Field name="oldPassword" type="password" autoComplete="current-password" label={t('oldPassword')} required value={password.oldPassword} error={error?.fields.includes('oldPassword')} onChange={(e) => setPassword({ ...password, oldPassword: e.target.value })} /><Field name="newPassword" type="password" autoComplete="new-password" label={t('newPassword')} required value={password.newPassword} error={error?.fields.includes('newPassword')} onChange={(e) => setPassword({ ...password, newPassword: e.target.value })} /><Button testId="account-password-save" disabled={busy || !supported}>{t('passwordChange')}</Button></form></Section>
        <Section title={t('mfaTitle')}>{principal.mfaEnabled ? <p data-testid="account-mfa-enabled">{t('mfaEnabled')}</p> : <Button testId="account-mfa-start" disabled={busy || !supported} onClick={() => void perform(async () => { await withIdentityLock(kind, async () => { await principalInsideLock(kind); await identityRequest(kind, 'mfa-start', {}); setSetup(await identityRequest(kind, 'mfa-enroll', {})); }); })}>{t('enroll')}</Button>}{setup.secret && <><p>{t('mfaHelp')}</p><output className={styles.secret} data-testid="account-setup-key">{setup.secret}</output><output className={styles.secret} data-testid="account-setup-uri">{setup.otpauthUri}</output><form className={styles.form} onSubmit={(e) => { e.preventDefault(); void perform(async () => { await withIdentityLock(kind, async () => { await identityRequest(kind, 'mfa-verify', { code }); }); setSetup({}); setCode(''); await load(); }); }}><Field name="code" label={t('code')} value={code} inputMode="numeric" pattern="[0-9]{6}" maxLength={6} required onChange={(e) => setCode(e.target.value)} /><Button testId="account-mfa-verify" disabled={busy || !supported}>{t('verify')}</Button></form></>}</Section>
      </>}
      {profile && <Section title={t('profile')}><form className={styles.form} onSubmit={(e) => { e.preventDefault(); void perform(async () => { await ensurePrincipal(kind); const mutable = { version: profile.version, name: profile.name, email: profile.email, birthday: profile.birthday, preferredLanguage: profile.preferredLanguage, notificationPreferences: profile.notificationPreferences, marketingConsent: profile.marketingConsent }; setProfile(await identityRequest(kind, 'profile', mutable)); }); }}>
        <Field name="name" label={t('name')} required maxLength={120} value={profile.name} error={error?.fields.includes('name')} onChange={(e) => setProfile({ ...profile, name: e.target.value })} />
        <Field name="mobile" label={t('mobile')} readOnly value={formatPhone(profile.mobile)} />
        <Field name="email" label={t('email')} type="email" value={profile.email ?? ''} error={error?.fields.includes('email')} onChange={(e) => setProfile({ ...profile, email: e.target.value || null })} />
        <Field name="birthday" label={t('birthday')} type="date" value={profile.birthday ?? ''} error={error?.fields.includes('birthday')} onChange={(e) => setProfile({ ...profile, birthday: e.target.value || null })} />
        <label className={styles.field}>{t('language')}<select className={styles.input} data-testid="account-language" value={profile.preferredLanguage} onChange={(e) => setProfile({ ...profile, preferredLanguage: e.target.value })}>{['en', 'si', 'ta'].map((language) => <option key={language} value={language}>{t(language as 'en')}</option>)}</select></label>
        <fieldset className={styles.card}><legend>{t('notifications')}</legend>{(['sms', 'whatsapp', 'email'] as const).map((channel) => <Check key={channel} name={`notifications-${channel}`} label={t(channel)} checked={profile.notificationPreferences[channel]} onChange={(checked) => setProfile({ ...profile, notificationPreferences: { ...profile.notificationPreferences, [channel]: checked } })} />)}</fieldset>
        <Check name="marketingConsent" label={t('marketingConsent')} checked={profile.marketingConsent} onChange={(checked) => setProfile({ ...profile, marketingConsent: checked })} />
        {error?.code === 'conflict' && <><p role="alert" data-testid="account-profile-conflict">{t('conflict')}</p><Button testId="account-profile-reload" type="button" secondary onClick={() => void perform(async () => setProfile(await identityRequest(kind, 'profile')))}>{t('reload')}</Button></>}
        <Button testId="account-profile-save" disabled={busy}>{t('save')}</Button>
      </form></Section>}
      <Section title={t('devices')}>{!(devices.items?.length) && <p data-testid="account-devices-empty">{t('empty')}</p>}{devices.items?.map((device) => <div key={device.id} className={styles.row} data-testid="account-device" data-id={device.id}><p>{device.deviceLabel}</p>{device.current && <p data-testid="account-device-current">{t('currentDevice')}</p>}{device.createdAt && <p className={styles.muted}>{t('created', { time: formatInstant(device.createdAt, timeZone) })}</p>}<Button testId="account-device-revoke" danger disabled={busy || !supported} onClick={() => setConfirm({ action: 'revoke', body: { id: device.id } })}>{t('revoke')}</Button></div>)}{pager}<Button testId="account-revoke-all" danger disabled={busy || !supported} onClick={() => setConfirm({ action: 'revoke-all', body: {} })}>{t('revokeAll')}</Button></Section>
      {kind === 'customer' && <Section title={t('privacy')}><Button testId="account-export" disabled={busy} onClick={() => void perform(async () => { await ensurePrincipal(kind); const data = await identityRequest(kind, 'export', {}); const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })); const link = document.createElement('a'); link.href = url; link.download = 'customer-data.json'; link.click(); URL.revokeObjectURL(url); })}>{t('export')}</Button><p>{t('deleteHelp')}</p><Button testId="account-delete" danger disabled={busy || !supported} onClick={() => setConfirm({ action: 'delete', body: { confirm: true } })}>{t('delete')}</Button></Section>}
    </>}
    {view === 'staff' && <Section title={t('staff')}>{!(staff.items?.length) && <p data-testid="account-staff-empty">{t('empty')}</p>}{staff.items?.map((person) => <div className={styles.row} key={person.id} data-testid="account-staff-row" data-id={person.id}><p>{person.email ?? person.mobile}</p><p>{t(person.role as 'OWNER')}</p><p>{person.active ? t('active') : t('inactive')}</p>{principal.permissions.includes('staff.manage') && person.active && <><label className={styles.field}>{t('role')}<select data-testid="account-staff-role" className={styles.input} value={person.role} disabled={busy || !supported} onChange={(e) => void perform(() => mutate('staff-change', { id: person.id, version: person.version, role: e.target.value, professionalGrant: person.professionalGrant }))}>{['OWNER', 'MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'].map((role) => <option key={role} value={role}>{t(role as 'OWNER')}</option>)}</select></label>{person.role === 'PROFESSIONAL' && <Check name="professional-grant" disabled={busy || !supported} label={t('professionalGrant')} checked={Boolean(person.professionalGrant)} onChange={(checked) => { if (supported) void perform(() => mutate('staff-change', { id: person.id, version: person.version, role: person.role, professionalGrant: checked })); }} />}<Button testId="account-staff-remove" danger disabled={busy || !supported} onClick={() => setConfirm({ action: 'staff-remove', body: { id: person.id } })}>{t('remove')}</Button></>}</div>)}{pager}
      {principal.permissions.includes('staff.manage') && <form className={styles.form} onSubmit={(e) => { e.preventDefault(); void perform(async () => { try { await mutate('staff-create', newStaff); } finally { setNewStaff((v) => ({ ...v, password: '' })); } }); }}><h3>{t('createStaff')}</h3>{(['email', 'mobile', 'password'] as const).map((name) => <Field key={name} name={`staff-${name}`} label={t(name === 'password' ? 'initialPassword' : name)} type={name === 'password' ? 'password' : name === 'email' ? 'email' : 'tel'} value={newStaff[name]} required={name === 'password'} error={error?.fields.includes(name)} onChange={(e) => setNewStaff({ ...newStaff, [name]: e.target.value })} />)}<label className={styles.field}>{t('role')}<select className={styles.input} data-testid="account-staff-new-role" value={newStaff.role} onChange={(e) => setNewStaff({ ...newStaff, role: e.target.value })}>{['OWNER', 'MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'].map((role) => <option key={role} value={role}>{t(role as 'OWNER')}</option>)}</select></label><Button testId="account-staff-create" disabled={busy || !supported}>{t('createStaff')}</Button></form>}
    </Section>}
    {view === 'audit' && <Section title={t('audit')}>{!(audit.items?.length) && <p data-testid="account-audit-empty">{t('empty')}</p>}{audit.items?.map((entry) => <div className={styles.row} key={entry.id} data-testid="account-audit-row" data-id={entry.id}><p>{t('auditEntry', { action: entry.action ?? '', time: entry.occurredAt ? formatInstant(entry.occurredAt, timeZone) : '' })}</p><p>{entry.actorKind}</p><pre className={styles.secret}>{JSON.stringify({ before: entry.before, after: entry.after }, null, 2)}</pre></div>)}{pager}</Section>}
  </>;
}
