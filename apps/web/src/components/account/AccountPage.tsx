import { cookies, headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { createServerApiClient, requestSignal } from '@/lib/api-core/client';
import { decodeSalon, SALON_HEADER } from '@/features/branding/server/salon-header';
import { workspaceEntry } from '@/features/identity/server/entry';
import { cookieName, requestHost, type Kind } from '@/features/identity/server/identity-web';
import { AccountFrame } from './AccountFrame';
import { AccountScreen } from '@/features/identity/components/AccountScreen';
import { BillingBanner, type Entitlements } from './BillingBanner';
import { BillingScreen } from './BillingScreen';
import { Denied } from '@/features/identity/components/Controls';
import { SignIn } from '@/features/identity/components/SignIn';
import { SupportAccessScreen } from '@/components/support/SupportAccessScreen';
import { SupportBanner } from '@/components/support/SupportBanner';

type View = 'account' | 'staff' | 'audit' | 'billing' | 'support-access';
type PageProps = { kind: Kind; view?: View; signIn?: boolean };

const REQUIRED: Record<View, string> = { account: 'session.manage', staff: 'staff.list', audit: 'audit.list', billing: 'invoice.read', 'support-access': 'support.manage' };

/**
 * A salon staff page, decided on the server before anything is sent (CR-013): no Suspense fallback
 * or generic frame is painted first. Without a usable session the response is a redirect to sign-in;
 * otherwise the first frame is the salon's own workspace, with the billing banner the API decides
 * (F03-S02) and, in a read-only salon, its mutating actions disabled with the reason.
 */
export async function AccountPage({ kind: requested, view, signIn }: PageProps) {
  const h = await headers(); const salon = decodeSalon(h.get(SALON_HEADER)); const host = requestHost(h.get('host'));
  if (!salon || !host) notFound();
  if (signIn) return <AccountFrame salon={salon} signIn><SignIn kind={requested} timeZone={salon.timeZone} /></AccountFrame>;
  const store = await cookies();
  // A support session (F03-S04) takes precedence over a staff session in the same browser: its pages
  // are the support view, read-only, under the support banner.
  const supportAccess = store.get(cookieName('support', 'access'))?.value;
  const kind: Kind = supportAccess ? 'support' : requested;
  const support = kind === 'support';
  const access = support ? supportAccess : store.get(cookieName(kind, 'access'))?.value;
  const client = createServerApiClient({ hostname: host.hostname });
  const entry = await workspaceEntry(kind, support ? { access } : { access, refresh: store.get(cookieName(kind, 'refresh'))?.value, expiry: store.get(cookieName(kind, 'expires'))?.value }, client);
  if (entry.kind === 'signed-out') redirect(support ? '/workspace/support#ended' : '/workspace/sign-in');
  const principal = entry.kind === 'signed-in' ? entry.principal : null;
  const entitlements = principal && access && client ? await readEntitlements(client, access) : null;
  const banner = <BillingBanner entitlements={entitlements} timeZone={salon.timeZone} />;
  const supportBanner = support ? <SupportBanner expiresAt={principal?.expiresAt ?? null} timeZone={salon.timeZone} /> : undefined;
  // The support view has no account of its own: its account page shows what it may read.
  const required = view && !(support && view === 'account') ? REQUIRED[view] : null;
  if (principal && required && !principal.permissions.includes(required)) {
    return <AccountFrame salon={salon} permissions={principal.permissions} view={view} banner={banner} supportBanner={supportBanner} support={support}><Denied /></AccountFrame>;
  }
  const readOnly = support || entitlements?.subscription?.state === 'read_only';
  return (
    <AccountFrame salon={salon} permissions={principal?.permissions} view={view} banner={banner} supportBanner={supportBanner} support={support}>
      {view === 'billing'
        ? <BillingScreen kind={kind} timeZone={salon.timeZone} />
        : view === 'support-access'
          ? <SupportAccessScreen timeZone={salon.timeZone} />
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
