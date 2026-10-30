import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { SidebarInset } from '@/components/ui/sidebar';
import { Navigation, NavigationProvider, NavigationTrigger, type NavigationItem } from '@/components/shared/Navigation';
import { ConsoleSignOut } from './ConsoleSignOut';

export type ConsoleView = 'salons' | 'plans' | 'support' | 'none';

/**
 * The operator console frame: the final Salon-mode workspace grammar (sticky white header from
 * 900px, the shadcn/ui Sidebar as a rail from 900px and its sheet below), in the platform's neutral
 * theme. Only the sections the operator may use appear: Salons, Plans and Support sessions for an
 * Admin; Salons and Support sessions for Support (F03-S04). Sign out is always in the header.
 */
export function ConsoleShell({ children, permissions = [], view = 'none', signedIn = true }: { children: ReactNode; permissions?: string[]; view?: ConsoleView; signedIn?: boolean }) {
  const t = useTranslations('console');
  const items: NavigationItem[] = [];
  if (permissions.includes('operator.salon.read')) items.push({ href: '/console/salons', label: t('salons'), icon: 'salons', testId: 'console-nav-salons', current: view === 'salons' });
  if (permissions.includes('operator.plan.read')) items.push({ href: '/console/plans', label: t('plans'), icon: 'plans', testId: 'console-nav-plans', current: view === 'plans' });
  if (permissions.includes('operator.support.start') || permissions.includes('operator.support.read')) items.push({ href: '/console/support', label: t('supportSessions'), icon: 'support', testId: 'console-nav-support', current: view === 'support' });
  return (
    <NavigationProvider className="flex-col bg-background" data-testid="console-shell">
      <header className="sticky top-0 z-40 flex min-h-header items-center gap-2 bg-background px-4 py-2 rail:border-b rail:border-border rail:bg-card rail:px-7 rail:py-0" data-testid="console-header">
        {items.length > 0 && <NavigationTrigger label={t('menu')} />}
        <p className="m-0 min-w-0 flex-1 truncate font-semibold text-foreground" data-testid="console-title" title={t('title')}>{t('title')}</p>
        {signedIn && <ConsoleSignOut />}
      </header>
      <div className="flex min-w-0 flex-1">
        {items.length > 0 && <Navigation items={items} label={t('title')} description={t('menuHelp')} />}
        <SidebarInset className="min-w-0 px-4 pt-4 pb-12 rail:p-6 rail:pb-12 wide:px-9 wide:pt-7 wide:pb-14" data-testid="console-main">
          <div className="mx-auto w-full min-w-0 max-w-content rail:max-w-workspace">{children}</div>
        </SidebarInset>
      </div>
    </NavigationProvider>
  );
}
