import Link from 'next/link';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import type { Salon } from '@/lib/api/salon';
import { Navigation, type NavigationItem } from './Navigation';
import styles from './WorkspaceShell.module.css';
export function WorkspaceShell({ salon, children, permissions = [], view = 'account', signIn = false }: { salon: Salon; children: ReactNode; permissions?: string[]; view?: 'account' | 'staff' | 'audit'; signIn?: boolean }) {
  const t = useTranslations('account');
  const items: NavigationItem[] = [{ href: signIn ? '/workspace/sign-in' : '/workspace/account', label: signIn ? t('staffSignIn') : t('account'), icon: 'account', testId: 'workspace-account', alias: 'account-nav', current: signIn || view === 'account' }];
  if (permissions.includes('staff.list')) items.push({ href: '/workspace/staff', label: t('staff'), icon: 'staff', testId: 'workspace-staff', current: view === 'staff' });
  if (permissions.includes('audit.list')) items.push({ href: '/workspace/audit', label: t('audit'), icon: 'audit', testId: 'workspace-audit', current: view === 'audit' });
  return <div className={styles.shell} data-testid="workspace-shell"><header className={styles.header} data-testid="workspace-header"><Link className={styles.identity} href="/" data-testid="account-home" title={salon.name}>{salon.name}</Link></header><div className={styles.layout}><Navigation items={items} label={t('staffTitle')} /><main className={styles.main} data-testid="workspace-main"><div className={styles.content}>{children}</div></main></div></div>;
}
