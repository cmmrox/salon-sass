'use client';
import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { components } from '@salon/api-contracts';
import type { Kind, Principal } from '@/lib/identity/server';
import { ensurePrincipal, identityRequest, IdentityFailure, principalInsideLock, supportsLocks, withIdentityLock } from '@/lib/identity/browser';
import { formatInstant } from '@/lib/format';
import { AccountLoading, Button, Check, Field, Section } from './Controls';
import { Alert } from '@/components/ui/alert';
import { Card } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import { NativeSelect, NativeSelectOption } from '@/components/ui/native-select';
import { cn } from '@/lib/utils';
import { Confirmation } from './Confirmation';
type Staff = components['schemas']['StaffView'];
type Device = components['schemas']['SessionView'];
type Audit = components['schemas']['AuditView'];
type Page<T> = { items?: T[]; page?: number; size?: number; total?: number };
type View = 'account' | 'staff' | 'audit';
// The workspace's recurring arrangements: a row of actions, a form, a listed item (device, staff
// member, audit entry) that becomes two columns from 900px, a secret shown once, a quiet note.
const actions = 'flex flex-wrap items-center gap-2';
const form = 'flex min-w-0 flex-col gap-4';
const row = 'mb-2 min-w-0 gap-3 border border-border bg-transparent p-4 wrap-anywhere rail:grid rail:grid-cols-2 rail:items-center';
const secret = 'rounded-field bg-background p-3 font-mono text-small whitespace-pre-wrap wrap-anywhere';
const muted = 'text-small text-muted-foreground';
export function AccountScreen({ kind, initialPrincipal, timeZone, view = 'account' }: { kind: Kind; initialPrincipal: Principal | null; timeZone: string; view?: View }) {
  const t = useTranslations('account'); const router = useRouter();
  const [principal, setPrincipal] = useState(initialPrincipal); const [busy, setBusy] = useState(false); const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<IdentityFailure | null>(null); const [success, setSuccess] = useState(false); const [supported, setSupported] = useState<boolean | null>(null);
  const [devices, setDevices] = useState<Page<Device>>({}); const [staff, setStaff] = useState<Page<Staff>>({}); const [audit, setAudit] = useState<Page<Audit>>({}); const [page, setPage] = useState(0);
  const [password, setPassword] = useState({ oldPassword: '', newPassword: '' }); const [setup, setSetup] = useState<{ secret?: string; otpauthUri?: string }>({}); const [code, setCode] = useState('');
  const [confirm, setConfirm] = useState<{ action: string; body: unknown } | null>(null);
  const [newStaff, setNewStaff] = useState({ email: '', mobile: '', password: '', role: 'RECEPTIONIST', professionalGrant: false });
  const observedPermissions = useRef(initialPrincipal?.permissions);
  const updatePrincipal = useCallback((next: Principal) => {
    const previous = observedPermissions.current;
    observedPermissions.current = next.permissions;
    setPrincipal(next);
    // The server frame owns permission-filtered navigation. Refresh it once per live change,
    // including renewal from an initially missing principal, without discarding account drafts.
    if ((!previous || previous.length !== next.permissions.length || next.permissions.some((permission) => !previous.includes(permission)))) router.refresh();
  }, [router]);
  async function load(nextPage = page) {
    const p = await ensurePrincipal(kind); updatePrincipal(p);
    if (view === 'account') {
      setDevices(await identityRequest(kind, `sessions?page=${nextPage}`));
    } else if (view === 'staff') setStaff(await identityRequest(kind, `staff?page=${nextPage}`));
    else setAudit(await identityRequest(kind, `audit?page=${nextPage}`));
    setLoaded(true);
  }
  async function perform(work: () => Promise<void>) {
    setBusy(true); setError(null); setSuccess(false);
    try { await work(); setSuccess(true); }
    catch (failure) {
      // A native modal makes the page feedback inert. Dismiss it before announcing the
      // refusal; recovery reloads current state and never replays a destructive mutation.
      setConfirm(null);
      const f = failure instanceof IdentityFailure ? failure : new IdentityFailure('service_unavailable'); setError(f);
      if (f.code === 'invalid_session') { router.replace('/workspace/sign-in'); router.refresh(); }
    } finally { setBusy(false); }
  }
  useEffect(() => { let active = true; Promise.resolve().then(async () => { if (!active) return; setSupported(supportsLocks()); try { await load(0); } catch (failure) { if (active) { const f = failure instanceof IdentityFailure ? failure : new IdentityFailure('service_unavailable'); setError(f); if (f.code === 'invalid_session') { router.replace('/workspace/sign-in'); router.refresh(); } } } }); return () => { active = false; }; /* First render loads live state; drafts are never overwritten by a tab event. */
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, view]);
  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return;
    const channel = new BroadcastChannel('salon:identity');
    channel.onmessage = (event) => { if (event.data?.kind === kind) void identityRequest<Principal>(kind, 'principal').then(updatePrincipal).catch(() => { router.refresh(); }); };
    return () => channel.close();
  }, [kind, router, updatePrincipal]);
  async function mutate(action: string, body: unknown = {}) {
    await withIdentityLock(kind, async () => { await principalInsideLock(kind); await identityRequest(kind, action, body); });
    if (['logout', 'revoke-all'].includes(action) || (action === 'revoke' && devices.items?.some((d) => d.current && d.id === (body as { id?: string }).id))) { router.replace('/workspace/sign-in'); router.refresh(); return; }
    setConfirm(null); await load();
  }
  const feedback = <>{supported === false && <Alert variant="notice" role="status" className="rail:col-span-full" data-testid="account-browser-support">{t('browser_unsupported')}</Alert>}{error && <Alert variant="notice" className="rail:col-span-full" data-testid="account-error"><p>{t.has(error.code as 'service_unavailable') ? t(error.code as 'service_unavailable') : t('service_unavailable')}</p>{error.retryAfter > 0 && <p>{t('wait', { seconds: error.retryAfter })}</p>}<Button testId="account-retry" secondary disabled={busy} onClick={() => void perform(() => load())}>{t('retry')}</Button>{error.code === 'invalid_session' && <Link href={'/workspace/sign-in'} data-testid="account-sign-in-link" className="inline-flex min-h-touch items-center px-3 py-2 text-foreground wrap-anywhere">{t('signIn')}</Link>}</Alert>}{success && <Alert variant="success" role="status" className="rail:col-span-full" data-testid="account-success">{t('success')}</Alert>}</>;
  // The principal is known from the server; only the live list is pending after the page is shown.
  if (!principal) return <>{feedback}<AccountLoading /></>;
  const required = view === 'staff' ? 'staff.list' : view === 'audit' ? 'audit.list' : 'session.manage';
  if (!principal.permissions.includes(required)) return <>{feedback}<p role="alert" data-testid="account-denied">{t('denied')}</p></>;
  const list = view === 'staff' ? staff : view === 'audit' ? audit : devices;
  const pager = <div className={actions}><Button testId="account-page-previous" secondary disabled={busy || page === 0} onClick={() => void perform(async () => { await load(page - 1); setPage(page - 1); })}>{t('previous')}</Button><p data-testid="account-page">{t('page', { page: page + 1 })}</p><Button testId="account-page-next" secondary disabled={busy || (page + 1) * 10 >= (list.total ?? 0)} onClick={() => void perform(async () => { await load(page + 1); setPage(page + 1); })}>{t('next')}</Button></div>;
  return <div className="flex min-w-0 flex-col gap-6 rail:grid rail:grid-cols-2 rail:items-start">{feedback}
    <div className={cn(actions, 'rail:col-span-full')}><Button testId="account-logout" secondary disabled={busy || !supported} onClick={() => void perform(() => mutate('logout'))}>{t('logout')}</Button></div>
    {confirm && <Confirmation busy={busy} workspace title={t('confirmation')} onCancel={() => { if (!busy) setConfirm(null); }}><p>{confirm.action === 'staff-remove' ? t('confirmRemoval') : t('confirmRevoke')}</p><div className={actions}><Button testId="account-confirm-action" danger disabled={busy || !supported} onClick={() => void perform(() => mutate(confirm.action, confirm.body))}>{t('confirmRevoke')}</Button><Button testId="account-cancel-action" secondary disabled={busy} onClick={() => setConfirm(null)}>{t('cancel')}</Button></div></Confirmation>}
    {view === 'account' && <>
      <Section title={t('permissions')}><p data-testid="account-scope">{t('scope', { scope: t(principal.scope === 'own' ? 'own' : 'salon') })}</p><p data-testid="account-role">{t(principal.role as 'OWNER')}</p><ul className="m-0 list-disc pl-6 *:mb-2" data-testid="account-permissions">{principal.permissions.map((permission) => <li key={permission}>{t(('permissionLabels.' + permission.replaceAll('.', '_')) as 'permissionLabels.session_manage')}</li>)}</ul></Section>
      <>
        <Section title={t('passwordChange')}><form className={form} onSubmit={(e) => { e.preventDefault(); void perform(async () => { try { await mutate('password', password); } finally { setPassword({ oldPassword: '', newPassword: '' }); } }); }}><Field name="oldPassword" type="password" autoComplete="current-password" label={t('oldPassword')} required value={password.oldPassword} error={error?.fields.includes('oldPassword')} onChange={(e) => setPassword({ ...password, oldPassword: e.target.value })} /><Field name="newPassword" type="password" autoComplete="new-password" label={t('newPassword')} required value={password.newPassword} error={error?.fields.includes('newPassword')} onChange={(e) => setPassword({ ...password, newPassword: e.target.value })} /><Button testId="account-password-save" disabled={busy || !supported}>{t('passwordChange')}</Button></form></Section>
        <Section title={t('mfaTitle')}>{principal.mfaEnabled ? <p data-testid="account-mfa-enabled">{t('mfaEnabled')}</p> : <Button testId="account-mfa-start" disabled={busy || !supported} onClick={() => void perform(async () => { await withIdentityLock(kind, async () => { await principalInsideLock(kind); await identityRequest(kind, 'mfa-start', {}); setSetup(await identityRequest(kind, 'mfa-enroll', {})); }); })}>{t('enroll')}</Button>}{setup.secret && <><p>{t('mfaHelp')}</p><output className={secret} data-testid="account-setup-key">{setup.secret}</output><output className={secret} data-testid="account-setup-uri">{setup.otpauthUri}</output><form className={form} onSubmit={(e) => { e.preventDefault(); void perform(async () => { await withIdentityLock(kind, async () => { await identityRequest(kind, 'mfa-verify', { code }); }); setSetup({}); setCode(''); await load(); }); }}><Field name="code" label={t('code')} value={code} inputMode="numeric" pattern="[0-9]{6}" maxLength={6} required onChange={(e) => setCode(e.target.value)} /><Button testId="account-mfa-verify" disabled={busy || !supported}>{t('verify')}</Button></form></>}</Section>
      </>
      <Section title={t('devices')}>{!loaded ? <AccountLoading inline /> : <>{!(devices.items?.length) && <p data-testid="account-devices-empty">{t('empty')}</p>}{devices.items?.map((device) => <Card key={device.id} className={row} data-testid="account-device" data-id={device.id}><p>{device.deviceLabel}</p>{device.current && <p data-testid="account-device-current">{t('currentDevice')}</p>}{device.createdAt && <p className={muted}>{t('created', { time: formatInstant(device.createdAt, timeZone) })}</p>}<Button testId="account-device-revoke" danger disabled={busy || !supported} className="self-start" onClick={() => setConfirm({ action: 'revoke', body: { id: device.id } })}>{t('revoke')}</Button></Card>)}{pager}<Button testId="account-revoke-all" danger disabled={busy || !supported} onClick={() => setConfirm({ action: 'revoke-all', body: {} })}>{t('revokeAll')}</Button></>}</Section>
    </>}
    {view === 'staff' && <Section title={t('staff')}>{!loaded ? <AccountLoading inline /> : <>{!(staff.items?.length) && <p data-testid="account-staff-empty">{t('empty')}</p>}{staff.items?.map((person) => <Card className={row} key={person.id} data-testid="account-staff-row" data-id={person.id}><p>{person.email ?? person.mobile}</p><p>{t(person.role as 'OWNER')}</p><p>{person.active ? t('active') : t('inactive')}</p>{principal.permissions.includes('staff.manage') && person.active && <><Label>{t('role')}<NativeSelect data-testid="account-staff-role" value={person.role} disabled={busy || !supported} onChange={(e) => void perform(() => mutate('staff-change', { id: person.id, version: person.version, role: e.target.value, professionalGrant: e.target.value === 'PROFESSIONAL' && Boolean(person.professionalGrant) }))}>{['OWNER', 'MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'].map((role) => <NativeSelectOption key={role} value={role}>{t(role as 'OWNER')}</NativeSelectOption>)}</NativeSelect></Label>{person.role === 'PROFESSIONAL' && <Check className="rail:col-span-full" name="professional-grant" disabled={busy || !supported} label={t('professionalGrant')} checked={Boolean(person.professionalGrant)} onChange={(checked) => { if (supported) void perform(() => mutate('staff-change', { id: person.id, version: person.version, role: person.role, professionalGrant: checked })); }} />}<Button testId="account-staff-remove" danger disabled={busy || !supported} className="self-start" onClick={() => setConfirm({ action: 'staff-remove', body: { id: person.id } })}>{t('remove')}</Button></>}</Card>)}{pager}</>}
      {principal.permissions.includes('staff.manage') && <form className={form} onSubmit={(e) => { e.preventDefault(); void perform(async () => { try { await mutate('staff-create', newStaff); } finally { setNewStaff((v) => ({ ...v, password: '' })); } }); }}><h3 className="my-4">{t('createStaff')}</h3>{(['email', 'mobile', 'password'] as const).map((name) => <Field key={name} name={`staff-${name}`} label={t(name === 'password' ? 'initialPassword' : name)} type={name === 'password' ? 'password' : name === 'email' ? 'email' : 'tel'} value={newStaff[name]} required={name === 'password'} error={error?.fields.includes(name)} onChange={(e) => setNewStaff({ ...newStaff, [name]: e.target.value })} />)}<Label>{t('role')}<NativeSelect data-testid="account-staff-new-role" value={newStaff.role} onChange={(e) => setNewStaff({ ...newStaff, role: e.target.value })}>{['OWNER', 'MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'].map((role) => <NativeSelectOption key={role} value={role}>{t(role as 'OWNER')}</NativeSelectOption>)}</NativeSelect></Label><Button testId="account-staff-create" disabled={busy || !supported}>{t('createStaff')}</Button></form>}
    </Section>}
    {view === 'audit' && <Section title={t('audit')}>{!loaded ? <AccountLoading inline /> : <>{!(audit.items?.length) && <p data-testid="account-audit-empty">{t('empty')}</p>}{audit.items?.map((entry) => <Card className={row} key={entry.id} data-testid="account-audit-row" data-id={entry.id}><p>{t('auditEntry', { action: entry.action ?? '', time: entry.occurredAt ? formatInstant(entry.occurredAt, timeZone) : '' })}</p><p>{entry.actorKind}</p><pre className={cn(secret, 'my-3.5 rail:col-span-full')}>{JSON.stringify({ before: entry.before, after: entry.after }, null, 2)}</pre></Card>)}{pager}</>}</Section>}
  </div>;
}
