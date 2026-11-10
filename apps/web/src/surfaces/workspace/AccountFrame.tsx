import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import type { Salon } from '@/features/branding';
import { BrandStyle } from '@/components/shared/BrandStyle';
import { DataLayer } from '@/lib/api-core/data-layer';
import { WorkspaceShell, type WorkspaceView } from './WorkspaceShell';
import { SessionRenewal } from './SessionRenewal';
import { pageOf } from './routes';
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
  scope = 'signed-out',
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
  /** The frame's data scope (`dataScope`): the browser's query cache is this frame's and this session's alone. */
  scope?: string;
}) {
  const t = useTranslations('account');
  // Each view's title is its page's in the one route table (web M5).
  const title = (pageOf(view)?.title ?? 'account') as 'account';
  if (signIn)
    return (
      <>
        <BrandStyle colour={salon.brandColour} />
        <StaffSignInFrame salon={salon}>{children}</StaffSignInFrame>
      </>
    );
  return (
    <DataLayer scope={scope}>
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
            {signIn ? t('staffTitle') : title === 'account' && support ? t('supportView') : t(title)}
          </h1>
          {children}
        </div>
      </WorkspaceShell>
    </DataLayer>
  );
}
