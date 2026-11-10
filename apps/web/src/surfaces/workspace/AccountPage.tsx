import { cookies, headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { decodeSalon, SALON_HEADER } from '@/features/branding/server';
import { AccountScreen, Denied, SignIn } from '@/features/identity';
import { workspaceEntry, cookieName, requestHost, type Kind } from '@/features/identity/server';
import { dataScope } from '@/lib/api-core/data-scope';
import { BillingBanner } from '@/features/platform';
import { readEntitlements } from '@/features/platform/server';
import { AccountFrame } from './AccountFrame';
import { BillingScreen } from './BillingScreen';
import { BookingRulesSettingsScreen } from './BookingRulesSettingsScreen';
import { BookingsScreen } from './BookingsScreen';
import { BrandingSettingsScreen } from './BrandingSettingsScreen';
import { HoursSettingsScreen } from './HoursSettingsScreen';
import { ProfileSettingsScreen } from './ProfileSettingsScreen';
import { ServicesScreen, type ServicesTab } from './ServicesScreen';
import { SettingsScreen, type SettingsTab } from './SettingsScreen';
import { SupportAccessScreen } from './SupportAccessScreen';
import { SupportBanner } from './SupportBanner';
import { TaxSettingsScreen } from './TaxSettingsScreen';
import { TeamFrame } from './TeamFrame';
import { pageOf } from './routes';
import { TeamScreen } from './TeamScreen';

type PageProps = { kind: Kind; view?: string; signIn?: boolean; tab?: SettingsTab; servicesTab?: ServicesTab };

/**
 * The region a typed mobile number is checked against (decision 084): the booking module's setting, else the branding
 * one, else `LK`, as the API reads them (docs/operations/environments-and-releases.md). Not a secret.
 */
function phoneRegion(): string {
  const region = process.env.SALON_BOOKING_PHONE_REGION || process.env.SALON_BRANDING_PHONE_REGION || 'LK';
  return /^[A-Za-z]{2}$/.test(region) ? region.toUpperCase() : 'LK';
}

/**
 * A salon staff page, decided on the server before anything is sent (CR-013): no Suspense fallback
 * or generic frame is painted first. Without a usable session the response is a redirect to sign-in;
 * otherwise the first frame is the salon's own workspace, with the billing banner the API decides
 * (F03-S02) and, in a read-only salon, its mutating actions disabled with the reason.
 */
export async function AccountPage({ kind: requested, view, signIn, tab, servicesTab }: PageProps) {
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
  // Each view's permission is its page's in the one route table (web M5).
  const required = view && !(support && view === 'account') ? (pageOf(view)?.permission ?? null) : null;
  if (principal && required && !principal.permissions.includes(required)) {
    return (
      <AccountFrame
        salon={salon}
        permissions={principal.permissions}
        view={view}
        banner={banner}
        supportBanner={supportBanner}
        support={support}
        scope={dataScope(host.hostname, kind, store.get(cookieName(kind, 'session'))?.value)}
      >
        <Denied />
      </AccountFrame>
    );
  }
  // Settings open their first tab the person may see (F04-S01); only Profile is delivered.
  if (principal && view === 'settings' && !tab) redirect('/workspace/settings/profile');
  const readOnly = support || entitlements?.subscription?.state === 'read_only';
  // The account pages read and renew the principal themselves (AccountScreen); every other page is rendered again by
  // the frame once the browser has renewed.
  const screenRenews = !view || view === 'account' || view === 'staff' || view === 'audit';
  return (
    <AccountFrame
      salon={salon}
      permissions={principal?.permissions}
      view={view}
      banner={banner}
      supportBanner={supportBanner}
      support={support}
      renew={principal === null && !screenRenews ? kind : undefined}
      scope={dataScope(host.hostname, kind, store.get(cookieName(kind, 'session'))?.value)}
    >
      {view === 'bookings' ? (
        <BookingsScreen
          kind={kind}
          timeZone={salon.timeZone}
          scope={principal?.scope ?? 'own'}
          canBook={Boolean(principal?.permissions.includes('booking.manage')) && !readOnly}
          canAct={!readOnly}
          canSetRules={Boolean(principal?.permissions.includes('settings.manage')) && !support}
          readOnly={support ? 'support' : readOnly ? 'salon' : null}
          phoneRegion={phoneRegion()}
        />
      ) : view === 'team' ? (
        <TeamScreen
          kind={kind}
          timeZone={salon.timeZone}
          canManage={Boolean(principal?.permissions.includes('team.manage')) && !readOnly}
          canBlock={Boolean(principal?.permissions.includes('team.block')) && !readOnly}
          canSeeAccounts={Boolean(principal?.permissions.includes('staff.list'))}
          readOnly={support ? 'support' : readOnly ? 'salon' : null}
        />
      ) : view === 'staff' ? (
        // F02's screen, its behaviour unchanged, under the Team page's tabs (F06-S01 AC-11).
        <TeamFrame tab="accounts" showTabs>
          <AccountScreen
            kind={kind}
            initialPrincipal={principal}
            timeZone={salon.timeZone}
            view={view}
            readOnly={readOnly}
          />
        </TeamFrame>
      ) : view === 'billing' ? (
        <BillingScreen kind={kind} timeZone={salon.timeZone} />
      ) : view === 'support-access' ? (
        <SupportAccessScreen timeZone={salon.timeZone} />
      ) : view === 'services' ? (
        <ServicesScreen
          kind={kind}
          tab={servicesTab ?? 'services'}
          canChange={Boolean(principal?.permissions.includes('catalogue.price.change')) && !readOnly}
          readOnly={support ? 'support' : readOnly ? 'salon' : null}
        />
      ) : view === 'settings' ? (
        <SettingsScreen tab={tab ?? 'profile'}>
          {tab === 'branding' ? (
            <BrandingSettingsScreen kind={kind} readOnly={support ? 'support' : readOnly ? 'salon' : null} />
          ) : tab === 'rules' ? (
            <BookingRulesSettingsScreen
              kind={kind}
              timeZone={salon.timeZone}
              readOnly={support ? 'support' : readOnly ? 'salon' : null}
            />
          ) : tab === 'tax' ? (
            <TaxSettingsScreen
              kind={kind}
              timeZone={salon.timeZone}
              readOnly={support ? 'support' : readOnly ? 'salon' : null}
            />
          ) : tab === 'hours' ? (
            <HoursSettingsScreen
              kind={kind}
              timeZone={salon.timeZone}
              readOnly={support ? 'support' : readOnly ? 'salon' : null}
            />
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
          view={view === 'audit' ? 'audit' : view === 'account' ? 'account' : undefined}
          readOnly={readOnly}
        />
      )}
    </AccountFrame>
  );
}
