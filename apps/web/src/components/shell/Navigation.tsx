import Link from 'next/link';
import styles from './Navigation.module.css';
export type NavigationItem = { href: string; label: string; testId: string; icon: 'home' | 'account' | 'staff' | 'audit'; current?: boolean; alias?: string };
export function NavigationIcon({ icon }: { icon: NavigationItem['icon'] }) {
  const paths = {
    home: 'm3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z',
    account: 'M20 21v-2a7 7 0 0 0-14 0v2M17 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
    staff: 'M16 21v-2a6 6 0 0 0-12 0v2M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM20 21v-2a6 6 0 0 0-3-5.2M17 3.2a4 4 0 0 1 0 7.6',
    audit: 'M8 3h8v4H8ZM8 5H5v16h14V5h-3M8 12h8M8 16h6',
  };
  return <svg className={styles.icon} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[icon]} /></svg>;
}
/** One real route inventory renders as customer tabs or the responsive workspace rail. */
export function Navigation({ items, label, workspace = false }: { items: NavigationItem[]; label: string; workspace?: boolean }) {
  return <nav className={`${styles.navigation} ${workspace ? styles.workspace : ''}`} aria-label={label} data-testid={workspace ? 'workspace-navigation' : 'salon-navigation'}>{items.map((item) => <Link key={item.href} href={item.href} data-testid={item.testId} className={styles.item} aria-current={item.current ? 'page' : undefined}><span className={styles.indicator}><NavigationIcon icon={item.icon} /></span><span data-testid={item.alias}>{item.label}</span></Link>)}</nav>;
}
