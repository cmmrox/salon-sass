import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import type { Salon } from '@/lib/api/salon';
import { WorkspaceShell } from '@/components/shell/WorkspaceShell';
import { SalonShell } from '@/components/salon/SalonShell';
import styles from './Account.module.css';
export function AccountFrame({ salon, staff, children, permissions, view = 'account', signIn = false }: { salon: Salon; staff: boolean; children: ReactNode; permissions?: string[]; view?: 'account' | 'staff' | 'audit'; signIn?: boolean }) {
  const t = useTranslations('account');
  const content = <div className={`${styles.page} ${staff ? styles.workspace : styles.branded}`}><>{staff ? <h1 className={styles.title} data-testid="account-title">{signIn ? t('staffTitle') : view === 'staff' ? t('staff') : view === 'audit' ? t('audit') : t('account')}</h1> : <h2 className={styles.title} data-testid="account-title">{t('customerTitle')}</h2>}</>{children}</div>;
  return staff ? <WorkspaceShell salon={salon} permissions={permissions} view={view} signIn={signIn}>{content}</WorkspaceShell> : <SalonShell salon={salon} account>{content}</SalonShell>;
}
