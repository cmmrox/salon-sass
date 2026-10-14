import { cookies, headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';
import { Suspense } from 'react';
import { createServerApiClient, requestSignal } from '@/lib/api/client';
import { decodeSalon, SALON_HEADER } from '@/lib/tenancy/salon-header';
import { cookieName, requestHost, type Kind, type Principal } from '@/lib/identity/server';
import { AccountFrame } from './AccountFrame';
import { AccountScreen } from './AccountScreen';
import { SignIn } from './SignIn';
import { AccountLoading } from './Controls';

async function Content({ kind, view, signIn }: { kind: Kind; view?: 'account' | 'staff' | 'audit'; signIn?: boolean }) {
  const h = await headers(); const salon = decodeSalon(h.get(SALON_HEADER)); const host = requestHost(h.get('host'));
  if (!salon || !host) notFound();
  if (signIn) return <AccountFrame salon={salon} staff={kind === 'staff'} signIn><SignIn kind={kind} timeZone={salon.timeZone} /></AccountFrame>;
  const store = await cookies(); const access = store.get(cookieName(kind, 'access'))?.value; const refresh = store.get(cookieName(kind, 'refresh'))?.value;
  if (!access && !refresh) redirect(kind === 'staff' ? '/workspace/sign-in' : '/sign-in');
  let principal: Principal | null = null;
  try { const client = createServerApiClient({ hostname: host.hostname }); const result = await client?.GET('/api/v1/identity/me', { headers: access ? { Authorization: `Bearer ${access}` } : {}, signal: requestSignal() }); if (result?.data?.kind === kind) principal = result.data; } catch { /* Render the recoverable account state. */ }
  if (principal && ((view === 'staff' && !principal.permissions.includes('staff.list')) || (view === 'audit' && !principal.permissions.includes('audit.list')))) {
    const t = await getTranslations('account'); return <AccountFrame salon={salon} staff={kind === 'staff'} permissions={principal.permissions} view={view}><p role="alert" data-testid="account-denied">{t('denied')}</p></AccountFrame>;
  }
  return <AccountFrame salon={salon} staff={kind === 'staff'} permissions={principal?.permissions} view={view}><AccountScreen kind={kind} initialPrincipal={principal} timeZone={salon.timeZone} view={view} /></AccountFrame>;
}
export function AccountPage(props: { kind: Kind; view?: 'account' | 'staff' | 'audit'; signIn?: boolean }) { return <Suspense fallback={<AccountLoading />}><Content {...props} /></Suspense>; }
