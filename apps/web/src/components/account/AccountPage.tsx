import { cookies, headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { createServerApiClient } from '@/lib/api/client';
import { decodeSalon, SALON_HEADER } from '@/lib/tenancy/salon-header';
import { workspaceEntry } from '@/lib/identity/entry';
import { cookieName, requestHost, type Kind } from '@/lib/identity/server';
import { AccountFrame } from './AccountFrame';
import { AccountScreen } from './AccountScreen';
import { Denied } from './Controls';
import { SignIn } from './SignIn';

type PageProps = { kind: Kind; view?: 'account' | 'staff' | 'audit'; signIn?: boolean };
/**
 * A salon staff page, decided on the server before anything is sent (CR-013): no Suspense fallback
 * or generic frame is painted first. Without a usable session the response is a redirect to sign-in;
 * otherwise the first frame is the salon's own workspace.
 */
export async function AccountPage({ kind, view, signIn }: PageProps) {
  const h = await headers(); const salon = decodeSalon(h.get(SALON_HEADER)); const host = requestHost(h.get('host'));
  if (!salon || !host) notFound();
  if (signIn) return <AccountFrame salon={salon} signIn><SignIn kind={kind} timeZone={salon.timeZone} /></AccountFrame>;
  const store = await cookies();
  const entry = await workspaceEntry(kind, { access: store.get(cookieName(kind, 'access'))?.value, refresh: store.get(cookieName(kind, 'refresh'))?.value, expiry: store.get(cookieName(kind, 'expires'))?.value }, createServerApiClient({ hostname: host.hostname }));
  if (entry.kind === 'signed-out') redirect('/workspace/sign-in');
  const principal = entry.kind === 'signed-in' ? entry.principal : null;
  if (principal && ((view === 'staff' && !principal.permissions.includes('staff.list')) || (view === 'audit' && !principal.permissions.includes('audit.list')))) {
    return <AccountFrame salon={salon} permissions={principal.permissions} view={view}><Denied /></AccountFrame>;
  }
  return <AccountFrame salon={salon} permissions={principal?.permissions} view={view}><AccountScreen kind={kind} initialPrincipal={principal} timeZone={salon.timeZone} view={view} /></AccountFrame>;
}
