import Link from 'next/link';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import type { Salon } from '@/features/branding';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import { SidebarInset } from '@/components/ui/sidebar';
import { Navigation, NavigationProvider, NavigationTrigger, type NavigationItem } from '@/components/shared/Navigation';
import { WORKSPACE_ROUTES } from './routes';

/** An image beside the salon's name, which says nothing the name does not: no text alternative. */
const DECORATIVE = '';

/**
 * The prototype's Salon workspace frame: a sticky header with the salon's name, the shadcn/ui
 * Sidebar (a 200px rail from 900px and 236px from 1200px; below 900px its sheet, opened from the
 * header's menu button) and the main area (CR-017).
 */
export type WorkspaceView = string;

/**
 * In the support view (F03-S04) the support banner opens the main area and sticks just under the
 * sticky header, so it stays on screen at every width and scroll position without covering the rail.
 */
export function WorkspaceShell({
  salon,
  children,
  permissions = [],
  view = 'account',
  signIn = false,
  banner,
  supportBanner,
}: {
  salon: Salon;
  children: ReactNode;
  permissions?: string[];
  view?: WorkspaceView;
  signIn?: boolean;
  banner?: ReactNode;
  supportBanner?: ReactNode;
}) {
  const t = useTranslations('account');
  const items: NavigationItem[] = [
    {
      href: signIn ? '/workspace/sign-in' : '/workspace/account',
      label: signIn ? t('staffSignIn') : supportBanner ? t('supportView') : t('account'),
      icon: 'account',
      testId: 'workspace-account',
      alias: 'account-nav',
      current: signIn || view === 'account',
    },
  ];
  // Every other item is a page of the one route table that has one, in its order, for whoever holds its permission
  // (web M5): Bookings, Team, Services, Audit, Billing, Support access, and Settings last, as in the prototype.
  for (const page of [...WORKSPACE_ROUTES.pages].sort((a, b) => (a.nav?.order ?? 0) - (b.nav?.order ?? 0))) {
    if (!page.nav || !permissions.includes(page.permission)) continue;
    items.push({
      href: page.path,
      label: t(page.nav.label as 'account'),
      icon: page.nav.icon as NavigationItem['icon'],
      testId: page.nav.testId,
      current: view === page.view || Boolean(page.nav.alsoFor?.includes(view)),
    });
  }
  const header = (
    <header
      className="sticky top-0 z-40 flex min-h-header items-center gap-2 bg-background px-4 py-2 rail:border-b rail:border-border rail:bg-card rail:px-7 rail:py-0"
      data-testid="workspace-header"
    >
      <NavigationTrigger label={t('menu')} />
      {/* The published logo before the name when the salon has one (F04-S02); nothing extra otherwise. */}
      {salon.logo && (
        <Avatar className="size-8 shrink-0 border border-border" aria-hidden="true" data-testid="workspace-logo">
          <AvatarImage src={`/brand-images/${salon.logo}`} alt={DECORATIVE} />
          <AvatarFallback className="bg-secondary text-small font-semibold text-foreground">
            {Array.from(salon.name.trim())[0]}
          </AvatarFallback>
        </Avatar>
      )}
      <Link
        className="block min-h-touch min-w-0 truncate leading-(--size-touch-target) font-semibold text-foreground no-underline"
        href="/"
        data-testid="account-home"
        title={salon.name}
      >
        {salon.name}
      </Link>
    </header>
  );
  return (
    <NavigationProvider className="flex-col bg-background" data-testid="workspace-shell">
      {header}
      <div className="flex min-w-0 flex-1">
        <Navigation items={items} label={t('staffTitle')} description={t('menuHelp')} />
        <SidebarInset
          className="min-w-0 px-4 pt-4 pb-12 rail:p-6 rail:pb-12 wide:px-9 wide:pt-7 wide:pb-14"
          data-testid="workspace-main"
        >
          {supportBanner && (
            <div
              className="sticky top-header z-30 -mx-4 -mt-4 mb-4 rail:-mx-6 rail:-mt-6 rail:mb-6 wide:-mx-9 wide:-mt-7 wide:mb-7"
              data-testid="workspace-support-band"
            >
              {supportBanner}
            </div>
          )}
          <div className="mx-auto flex w-full max-w-content min-w-0 flex-col gap-6 rail:max-w-workspace">
            {banner}
            {children}
          </div>
        </SidebarInset>
      </div>
    </NavigationProvider>
  );
}
