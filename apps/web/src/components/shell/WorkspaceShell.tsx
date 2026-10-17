import Link from 'next/link';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import type { Salon } from '@/lib/api/salon';
import { Navigation, type NavigationItem } from './Navigation';

/**
 * The prototype's Salon workspace frame: a sticky header with the salon's name, the navigation
 * (bottom tabs below 900px, a 200px rail from 900px and 236px from 1200px) and the main area.
 */
export function WorkspaceShell({ salon, children, permissions = [], view = 'account', signIn = false }: { salon: Salon; children: ReactNode; permissions?: string[]; view?: 'account' | 'staff' | 'audit'; signIn?: boolean }) {
  const t = useTranslations('account');
  const items: NavigationItem[] = [{ href: signIn ? '/workspace/sign-in' : '/workspace/account', label: signIn ? t('staffSignIn') : t('account'), icon: 'account', testId: 'workspace-account', alias: 'account-nav', current: signIn || view === 'account' }];
  if (permissions.includes('staff.list')) items.push({ href: '/workspace/staff', label: t('staff'), icon: 'staff', testId: 'workspace-staff', current: view === 'staff' });
  if (permissions.includes('audit.list')) items.push({ href: '/workspace/audit', label: t('audit'), icon: 'audit', testId: 'workspace-audit', current: view === 'audit' });
  return (
    <div className="min-h-screen bg-background" data-testid="workspace-shell">
      <header className="sticky top-0 z-40 flex min-h-header items-center justify-between gap-4 bg-background px-4 py-2 rail:border-b rail:border-border rail:bg-card rail:px-7" data-testid="workspace-header">
        <Link className="block min-h-touch min-w-0 truncate leading-(--size-touch-target) font-semibold text-foreground no-underline" href="/" data-testid="account-home" title={salon.name}>
          {salon.name}
        </Link>
      </header>
      <div className="min-w-0 rail:grid rail:grid-cols-[var(--size-sidebar-compact)_minmax(0,1fr)] rail:items-start wide:grid-cols-[var(--size-sidebar)_minmax(0,1fr)]">
        <Navigation items={items} label={t('staffTitle')} />
        <main className="min-w-0 px-4 pt-4 pb-[calc(var(--size-nav)+var(--spacing)*8+env(safe-area-inset-bottom,0px))] rail:p-6 rail:pb-12 wide:px-9 wide:pt-7 wide:pb-14" data-testid="workspace-main">
          <div className="mx-auto min-w-0 max-w-content rail:max-w-workspace">{children}</div>
        </main>
      </div>
    </div>
  );
}
