import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import type { Salon } from '@/lib/api/salon';
import { BrandStyle } from '@/components/shell/BrandStyle';
import { WorkspaceShell, type WorkspaceView } from '@/components/shell/WorkspaceShell';
import { StaffSignInFrame } from './StaffSignInFrame';
/** Every salon screen carries the salon's resolved identity and its brand accent. */
export function AccountFrame({ salon, children, permissions, view = 'account', signIn = false, banner, supportBanner, support = false }: { salon: Salon;  children: ReactNode; permissions?: string[]; view?: WorkspaceView; signIn?: boolean; banner?: ReactNode; supportBanner?: ReactNode; support?: boolean }) {
  const t = useTranslations('account');
  if (signIn) return <><BrandStyle colour={salon.brandColour} /><StaffSignInFrame salon={salon}>{children}</StaffSignInFrame></>;
  return <><BrandStyle colour={salon.brandColour} /><WorkspaceShell salon={salon} permissions={permissions} view={view} signIn={signIn} banner={banner} supportBanner={supportBanner}><div className="flex w-full min-w-0 flex-col gap-6"><h1 className="text-title leading-heading font-semibold wrap-anywhere rail:text-workspace-title" data-testid="account-title">{signIn ? t('staffTitle') : view === 'staff' ? t('staff') : view === 'audit' ? t('audit') : view === 'billing' ? t('billing') : view === 'support-access' ? t('supportAccess') : support ? t('supportView') : t('account')}</h1>{children}</div></WorkspaceShell></>;
}
