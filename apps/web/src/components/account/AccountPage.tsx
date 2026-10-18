import { cookies, headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { createServerApiClient, requestSignal } from '@/lib/api/client';
import { decodeSalon, SALON_HEADER } from '@/lib/tenancy/salon-header';
import { workspaceEntry } from '@/lib/identity/entry';
import { cookieName, requestHost, type Kind } from '@/lib/identity/server';
import { AccountFrame } from './AccountFrame';
import { AccountScreen } from './AccountScreen';
import { BillingBanner, type Entitlements } from './BillingBanner';
import { BillingScreen } from './BillingScreen';
import { Denied } from './Controls';
import { SignIn } from './SignIn';

type View = 'account' | 'staff' | 'audit' | 'billing';
type PageProps = { kind: Kind; view?: View; signIn?: boolean };

const REQUIRED: Record<View, string> = { account: 'session.manage', staff: 'staff.list', audit: 'audit.list', billing: 'invoice.read' };

/**
 * A salon staff page, decided on the server before anything is sent (CR-013): no Suspense fallback
 * or generic frame is painted first. Without a usable session the response is a redirect to sign-in;
 * otherwise the first frame is the salon's own workspace, with the billing banner the API decides
 * (F03-S02) and, in a read-only salon, its mutating actions disabled with the reason.
 */
export async function AccountPage({ kind, view, signIn }: PageProps) {
  const h = await headers(); const salon = decodeSalon(h.get(SALON_HEADER)); const host = requestHost(h.get('host'));
  if (!salon || !host) notFound();
  if (signIn) return <AccountFrame salon={salon} signIn><SignIn kind={kind} timeZone={salon.timeZone} /></AccountFrame>;
  const store = await cookies();
  const access = store.get(cookieName(kind, 'access'))?.value;
  const client = createServerApiClient({ hostname: host.hostname });
  const entry = await workspaceEntry(kind, { access, refresh: store.get(cookieName(kind, 'refresh'))?.value, expiry: store.get(cookieName(kind, 'expires'))?.value }, client);
  if (entry.kind === 'signed-out') redirect('/workspace/sign-in');
  const principal = entry.kind === 'signed-in' ? entry.principal : null;
  const entitlements = principal && access && client ? await readEntitlements(client, access) : null;
  const banner = <BillingBanner entitlements={entitlements} timeZone={salon.timeZone} />;
  if (principal && view && !principal.permissions.includes(REQUIRED[view])) {
    return <AccountFrame salon={salon} permissions={principal.permissions} view={view} banner={banner}><Denied /></AccountFrame>;
  }
  const readOnly = entitlements?.subscription?.state === 'read_only';
  return (
    <AccountFrame salon={salon} permissions={principal?.permissions} view={view} banner={banner}>
      {view === 'billing'
        ? <BillingScreen kind={kind} timeZone={salon.timeZone} />
        : <AccountScreen kind={kind} initialPrincipal={principal} timeZone={salon.timeZone} view={view} readOnly={readOnly} />}
    </AccountFrame>
  );
}

/** The salon's entitlements and subscription state for the banner; nothing when the API cannot say. */
async function readEntitlements(client: NonNullable<ReturnType<typeof createServerApiClient>>, access: string): Promise<Entitlements | null> {
  try {
    const answer = await client.GET('/api/v1/salon/entitlements', { headers: { Authorization: `Bearer ${access}` }, signal: requestSignal() });
    return answer.data ?? null;
  } catch {
    return null;
  }
}
