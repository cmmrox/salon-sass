import { cookies, headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { decodeSalon, SALON_HEADER } from '@/features/branding/server';
import { AccountScreen, Denied, SignIn } from '@/features/identity';
import { workspaceEntry, cookieName, requestHost, type Kind } from '@/features/identity/server';
import { BillingBanner } from '@/features/platform';
import { readEntitlements } from '@/features/platform/server';
import { AccountFrame } from './AccountFrame';
import { BillingScreen } from './BillingScreen';
import { BrandingSettingsScreen } from './BrandingSettingsScreen';
import { ProfileSettingsScreen } from './ProfileSettingsScreen';
import { SettingsScreen, type SettingsTab } from './SettingsScreen';
import { SupportAccessScreen } from './SupportAccessScreen';
import { SupportBanner } from './SupportBanner';

type View = 'account' | 'staff' | 'audit' | 'billing' | 'support-access' | 'settings';
type PageProps = { kind: Kind; view?: View; signIn?: boolean; tab?: SettingsTab };

const REQUIRED: Record<View, string> = {
  account: 'session.manage',
  staff: 'staff.list',
  audit: 'audit.list',
  billing: 'invoice.read',
  'support-access': 'support.manage',
  settings: 'settings.read',
};

/**
 * A salon staff page, decided on the server before anything is sent (CR-013): no Suspense fallback
 * or generic frame is painted first. Without a usable session the response is a redirect to sign-in;
 * otherwise the first frame is the salon's own workspace, with the billing banner the API decides
 * (F03-S02) and, in a read-only salon, its mutating actions disabled with the reason.
 */
export async function AccountPage({ kind: requested, view, signIn, tab }: PageProps) {
  const h = await headers();
  const salon = decodeSalon(h.get(SALON_HEADER));
  const host = requestHost(h.get('host'));
  if (!salon || !host) notFound();
  if (signIn)
    return (
      <AccountFrame salon={salon} signIn>
        <SignIn kind={requested} timeZone={salon.timeZone} />
      </AccountFrame>
    );
  const store = await cookies();
  // A support session (F03-S04) takes precedence over a staff session in the same browser: its pages
  // are the support view, read-only, under the support banner.
  const supportAccess = store.get(cookieName('support', 'access'))?.value;
  const kind: Kind = supportAccess ? 'support' : requested;
  const support = kind === 'support';
  const access = support ? supportAccess : store.get(cookieName(kind, 'access'))?.value;
  const entry = await workspaceEntry(
    kind,
    support
      ? { access }
      : {
          access,
          refresh: store.get(cookieName(kind, 'refresh'))?.value,
          expiry: store.get(cookieName(kind, 'expires'))?.value,
        },
    host.hostname,
  );
  if (entry.kind === 'signed-out') redirect(support ? '/workspace/support#ended' : '/workspace/sign-in');
  const principal = entry.kind === 'signed-in' ? entry.principal : null;
  const entitlements = principal && access ? await readEntitlements(host.hostname, access) : null;
  const banner = <BillingBanner entitlements={entitlements} timeZone={salon.timeZone} />;
  const supportBanner = support ? (
    <SupportBanner expiresAt={principal?.expiresAt ?? null} timeZone={salon.timeZone} />
  ) : undefined;
  // The support view has no account of its own: its account page shows what it may read.
  const required = view && !(support && view === 'account') ? REQUIRED[view] : null;
  if (principal && required && !principal.permissions.includes(required)) {
    return (
      <AccountFrame
        salon={salon}
        permissions={principal.permissions}
        view={view}
        banner={banner}
        supportBanner={supportBanner}
        support={support}
      >
        <Denied />
      </AccountFrame>
    );
  }
  // Settings open their first tab the person may see (F04-S01); only Profile is delivered.
  if (principal && view === 'settings' && !tab) redirect('/workspace/settings/profile');
  const readOnly = support || entitlements?.subscription?.state === 'read_only';
  return (
    <AccountFrame
      salon={salon}
      permissions={principal?.permissions}
      view={view}
      banner={banner}
      supportBanner={supportBanner}
      support={support}
    >
      {view === 'billing' ? (
        <BillingScreen kind={kind} timeZone={salon.timeZone} />
      ) : view === 'support-access' ? (
        <SupportAccessScreen timeZone={salon.timeZone} />
      ) : view === 'settings' ? (
        <SettingsScreen tab={tab ?? 'profile'}>
          {tab === 'branding' ? (
            <BrandingSettingsScreen kind={kind} readOnly={support ? 'support' : readOnly ? 'salon' : null} />
          ) : (
            <ProfileSettingsScreen
              kind={kind}
              salonName={salon.name}
              readOnly={support ? 'support' : readOnly ? 'salon' : null}
            />
          )}
        </SettingsScreen>
      ) : (
        <AccountScreen
          kind={kind}
          initialPrincipal={principal}
          timeZone={salon.timeZone}
          view={view}
          readOnly={readOnly}
        />
      )}
    </AccountFrame>
  );
}
