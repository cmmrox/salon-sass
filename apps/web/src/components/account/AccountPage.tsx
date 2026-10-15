import { cookies, headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { getTranslations } from 'next-intl/server';
import { Suspense } from 'react';
import type { Salon } from '@/lib/api/salon';
import { AppShell } from '@/components/shell/AppShell';
import { createServerApiClient, requestSignal } from '@/lib/api/client';
import { decodeSalon, SALON_HEADER } from '@/lib/tenancy/salon-header';
import { cookieName, requestHost, type Kind, type Principal } from '@/lib/identity/server';
import { AccountFrame } from './AccountFrame';
import { AccountScreen } from './AccountScreen';
import { SignIn } from './SignIn';
import { AccountLoading } from './Controls';

type PageProps = { kind: Kind; view?: 'account' | 'staff' | 'audit'; signIn?: boolean };
async function Content({ kind, view, signIn, salon, host }: PageProps & { salon: Salon; host: { hostname: string } }) {
  if (signIn) return <AccountFrame salon={salon} signIn><SignIn kind={kind} timeZone={salon.timeZone} /></AccountFrame>;
  const store = await cookies(); const access = store.get(cookieName(kind, 'access'))?.value; const refresh = store.get(cookieName(kind, 'refresh'))?.value;
  if (!access && !refresh) redirect('/workspace/sign-in');
  let principal: Principal | null = null;
  try { const client = createServerApiClient({ hostname: host.hostname }); const result = await client?.GET('/api/v1/identity/me', { headers: access ? { Authorization: `Bearer ${access}` } : {}, signal: requestSignal() }); if (result?.data?.kind === kind) principal = result.data; } catch { /* Render the recoverable account state. */ }
  if (principal && ((view === 'staff' && !principal.permissions.includes('staff.list')) || (view === 'audit' && !principal.permissions.includes('audit.list')))) {
    const t = await getTranslations('account'); return <AccountFrame salon={salon} permissions={principal.permissions} view={view}><p role="alert" data-testid="account-denied">{t('denied')}</p></AccountFrame>;
  }
  return <AccountFrame salon={salon} permissions={principal?.permissions} view={view}><AccountScreen kind={kind} initialPrincipal={principal} timeZone={salon.timeZone} view={view} /></AccountFrame>;
}
async function ResolvedPage(props: PageProps) {
  const h = await headers(); const salon = decodeSalon(h.get(SALON_HEADER)); const host = requestHost(h.get('host'));
  if (!salon || !host) notFound();
  return <Suspense fallback={<AccountFrame salon={salon} view={props.view} signIn={props.signIn}><AccountLoading /></AccountFrame>}><Content {...props} salon={salon} host={host} /></Suspense>;
}
export function AccountPage(props: PageProps) { return <Suspense fallback={<AppShell><AccountLoading /></AppShell>}><ResolvedPage {...props} /></Suspense>; }
