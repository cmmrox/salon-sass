import Link from 'next/link';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import type { Salon } from '@/lib/api/salon';
import { SidebarInset } from '@/components/ui/sidebar';
import { Navigation, NavigationProvider, NavigationTrigger, type NavigationItem } from './Navigation';

/**
 * The prototype's Salon workspace frame: a sticky header with the salon's name, the shadcn/ui
 * Sidebar (a 200px rail from 900px and 236px from 1200px; below 900px its sheet, opened from the
 * header's menu button) and the main area (CR-017).
 */
export function WorkspaceShell({ salon, children, permissions = [], view = 'account', signIn = false, banner }: { salon: Salon; children: ReactNode; permissions?: string[]; view?: 'account' | 'staff' | 'audit' | 'billing'; signIn?: boolean; banner?: ReactNode }) {
  const t = useTranslations('account');
  const items: NavigationItem[] = [{ href: signIn ? '/workspace/sign-in' : '/workspace/account', label: signIn ? t('staffSignIn') : t('account'), icon: 'account', testId: 'workspace-account', alias: 'account-nav', current: signIn || view === 'account' }];
  if (permissions.includes('staff.list')) items.push({ href: '/workspace/staff', label: t('staff'), icon: 'staff', testId: 'workspace-staff', current: view === 'staff' });
  if (permissions.includes('audit.list')) items.push({ href: '/workspace/audit', label: t('audit'), icon: 'audit', testId: 'workspace-audit', current: view === 'audit' });
  if (permissions.includes('invoice.read')) items.push({ href: '/workspace/billing', label: t('billing'), icon: 'billing', testId: 'workspace-billing', current: view === 'billing' });
  return (
    <NavigationProvider className="flex-col bg-background" data-testid="workspace-shell">
      <header className="sticky top-0 z-40 flex min-h-header items-center gap-2 bg-background px-4 py-2 rail:border-b rail:border-border rail:bg-card rail:px-7 rail:py-0" data-testid="workspace-header">
        <NavigationTrigger label={t('menu')} />
        <Link className="block min-h-touch min-w-0 truncate leading-(--size-touch-target) font-semibold text-foreground no-underline" href="/" data-testid="account-home" title={salon.name}>
          {salon.name}
        </Link>
      </header>
      <div className="flex min-w-0 flex-1">
        <Navigation items={items} label={t('staffTitle')} description={t('menuHelp')} />
        <SidebarInset className="min-w-0 px-4 pt-4 pb-12 rail:p-6 rail:pb-12 wide:px-9 wide:pt-7 wide:pb-14" data-testid="workspace-main">
          <div className="mx-auto flex w-full min-w-0 max-w-content flex-col gap-6 rail:max-w-workspace">{banner}{children}</div>
        </SidebarInset>
      </div>
    </NavigationProvider>
  );
}
