import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import type { Salon } from '@/lib/api/salon';
import { BrandStyle } from '@/components/shell/BrandStyle';
import { WorkspaceShell } from '@/components/shell/WorkspaceShell';
import styles from './Account.module.css';
import { StaffSignInFrame } from './StaffSignInFrame';
/** Every salon screen carries the salon's resolved identity and its brand accent. */
export function AccountFrame({ salon, children, permissions, view = 'account', signIn = false }: { salon: Salon;  children: ReactNode; permissions?: string[]; view?: 'account' | 'staff' | 'audit'; signIn?: boolean }) {
  const t = useTranslations('account');
  if (signIn) return <><BrandStyle colour={salon.brandColour} /><StaffSignInFrame salon={salon}>{children}</StaffSignInFrame></>;
  return <><BrandStyle colour={salon.brandColour} /><WorkspaceShell salon={salon} permissions={permissions} view={view} signIn={signIn}><div className={`${styles.page} ${styles.workspace}`}><h1 className={styles.title} data-testid="account-title">{signIn ? t('staffTitle') : view === 'staff' ? t('staff') : view === 'audit' ? t('audit') : t('account')}</h1>{children}</div></WorkspaceShell></>;
}
