import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import type { Salon } from '@/features/branding';
import { BrandStyle } from '@/components/shared/BrandStyle';
import { WorkspaceShell, type WorkspaceView } from './WorkspaceShell';
import { SessionRenewal } from './SessionRenewal';
import { StaffSignInFrame } from './StaffSignInFrame';
import type { Kind } from '@/features/identity';
/** Every salon screen carries the salon's resolved identity and its brand accent. */
export function AccountFrame({
  salon,
  children,
  permissions,
  view = 'account',
  signIn = false,
  banner,
  supportBanner,
  support = false,
  renew,
}: {
  salon: Salon;
  children: ReactNode;
  permissions?: string[];
  view?: WorkspaceView;
  signIn?: boolean;
  banner?: ReactNode;
  supportBanner?: ReactNode;
  support?: boolean;
  /** Rendered without a principal: the session the browser renews before rendering the page again. */
  renew?: Kind;
}) {
  const t = useTranslations('account');
  if (signIn)
    return (
      <>
        <BrandStyle colour={salon.brandColour} />
        <StaffSignInFrame salon={salon}>{children}</StaffSignInFrame>
      </>
    );
  return (
    <>
      <BrandStyle colour={salon.brandColour} />
      {renew && <SessionRenewal kind={renew} />}
      <WorkspaceShell
        salon={salon}
        permissions={permissions}
        view={view}
        signIn={signIn}
        banner={banner}
        supportBanner={supportBanner}
      >
        <div className="flex w-full min-w-0 flex-col gap-6">
          <h1
            className="text-title leading-heading font-semibold wrap-anywhere rail:text-workspace-title"
            data-testid="account-title"
          >
            {signIn
              ? t('staffTitle')
              : view === 'bookings'
                ? t('bookings')
                : view === 'team' || view === 'staff'
                  ? t('team')
                  : view === 'audit'
                    ? t('audit')
                    : view === 'billing'
                      ? t('billing')
                      : view === 'support-access'
                        ? t('supportAccess')
                        : view === 'settings'
                          ? t('settings')
                          : view === 'services'
                            ? t('services')
                            : support
                              ? t('supportView')
                              : t('account')}
          </h1>
          {children}
        </div>
      </WorkspaceShell>
    </>
  );
}
