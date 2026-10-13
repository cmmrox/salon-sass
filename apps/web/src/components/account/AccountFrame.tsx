import Link from 'next/link';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import type { Salon } from '@/lib/api/salon';
import { AppShell } from '@/components/shell/AppShell';
import { SalonShell } from '@/components/salon/SalonShell';
import styles from './Account.module.css';
export function AccountFrame({ salon, staff, children }: { salon: Salon; staff: boolean; children: ReactNode }) {
  const t = useTranslations('account');
  const content = <div className={`${styles.page} ${staff ? '' : styles.branded}`}><nav className={styles.nav} aria-label={t('account')}><Link href="/" className={styles.link} data-testid="account-home">{t('home')}</Link><Link href={staff ? '/workspace/account' : '/account'} className={styles.link} data-testid="account-nav">{t('account')}</Link><Link href={staff ? '/sign-in' : '/workspace/sign-in'} className={styles.link} data-testid="account-other-kind">{staff ? t('customerTitle') : t('staffSignIn')}</Link></nav><>{staff ? <h1 className={styles.title} data-testid="account-title">{t('staffTitle')}</h1> : <h2 className={styles.title} data-testid="account-title">{t('customerTitle')}</h2>}</>{children}</div>;
  return staff ? <AppShell>{content}</AppShell> : <SalonShell salon={salon}>{content}</SalonShell>;
}
