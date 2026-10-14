import Link from 'next/link';
import type { ReactNode } from 'react';
import type { Salon } from '@/lib/api/salon';
import { useTranslations } from 'next-intl';
import { brandRootRule } from '@/styles/brand';
import { Navigation } from '@/components/shell/Navigation';
import styles from './SalonShell.module.css';

/** Customer composition from the supplied reference, with a real branded initials fallback. */
export function SalonShell({ salon, children, account = false }: { salon: Salon; children?: ReactNode; account?: boolean }) {
  const t = useTranslations('account');
  const initials = salon.name.trim().split(/\s+/).slice(0, 2).map((word) => Array.from(word)[0]).join('');
  return <><style data-testid="salon-brand">{brandRootRule(salon.brandColour)}</style><div className={styles.shell} data-testid="salon-shell"><header className={styles.banner} data-testid="salon-header"><span className={styles.identity} aria-hidden="true" data-testid="salon-identity">{initials}</span><h1 className={styles.name} data-testid="salon-name" title={salon.name}>{salon.name}</h1></header><main className={styles.main} data-testid="salon-main">{children ?? <section className={styles.card}><h2 className={styles.title}>{t('customerTitle')}</h2><p className={styles.help}>{t('accountHomeHelp')}</p><Link className={styles.action} href="/account" data-testid="salon-account-open">{t('customerTitle')}</Link></section>}</main><Navigation label={t('customerTitle')} items={[
    { href: '/', label: t('home'), icon: 'home', testId: account ? 'account-home' : 'salon-home', current: !account },
    { href: '/account', label: t('account'), icon: 'account', testId: account ? 'account-nav' : 'salon-account', current: account },
    { href: '/workspace/sign-in', label: t('staffSignIn'), icon: 'staff', testId: account ? 'account-other-kind' : 'salon-staff-sign-in' },
  ]} /></div></>;
}
export function SalonShellLoading() {
  const t = useTranslations('salon');
  return <div className={styles.shell} role="status" data-testid="salon-loading"><span className={styles.visuallyHidden}>{t('loading')}</span><div className={styles.bannerLoading} aria-hidden="true"><span className={styles.identityLoading} /><span className={styles.nameLoading} /></div><div className={styles.main} aria-hidden="true"><span className={styles.contentLoading} /></div></div>;
}
