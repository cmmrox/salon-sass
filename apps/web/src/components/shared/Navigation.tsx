'use client';

import Link from 'next/link';
import { MenuIcon } from 'lucide-react';
import { useEffect, type ComponentProps } from 'react';

import {
  Sidebar,
  SidebarContent,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarTrigger,
  useSidebar,
} from '@/components/ui/sidebar';

export type NavigationItem = {
  href: string;
  label: string;
  testId: string;
  icon: 'home' | 'account' | 'staff' | 'audit' | 'salons' | 'plans' | 'billing' | 'support' | 'settings' | 'holidays';
  current?: boolean;
  alias?: string;
};

/** The prototype's own navigation glyphs, drawn in the current text colour. */
function NavigationIcon({ icon }: { icon: NavigationItem['icon'] }) {
  const paths = {
    home: 'm3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z',
    account: 'M20 21v-2a7 7 0 0 0-14 0v2M17 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
    staff:
      'M16 21v-2a6 6 0 0 0-12 0v2M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM20 21v-2a6 6 0 0 0-3-5.2M17 3.2a4 4 0 0 1 0 7.6',
    audit: 'M8 3h8v4H8ZM8 5H5v16h14V5h-3M8 12h8M8 16h6',
    // The prototype's own list and bars glyphs, for the operator console's salons and plans.
    salons: 'M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01',
    plans: 'M5 20V10M12 20V4M19 20v-7',
    // The prototype's card glyph (its Payments item), for the workspace's Plan and billing.
    billing: 'M6 6h12a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3ZM3 11h18',
    // The prototype's clock glyph, for time-limited support sessions (F03-S04).
    support: 'M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0ZM12 7v5l3 2',
    // The prototype's sliders glyph (its Settings item), its two circles written as arcs (F04-S01).
    settings: 'M4 7h9M17 7h3M4 17h3M11 17h9M13 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0',
    // The prototype's calendar glyph, its rounded box written as a path, for the holiday calendar (F04-S03).
    holidays:
      'M6 5h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3ZM3 10h18M8 3v4M16 3v4M12 13v5M9.5 15.5h5',
  };
  return (
    <svg
      className="size-icon flex-none"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[icon]} />
    </svg>
  );
}

/**
 * A route chosen in the phone sheet replaces the page, and with it this menu button. The new
 * page's button takes the focus back, as the sheet gives it back when it closes on the same page.
 */
let focusTriggerOnMount = false;

const keepRailOpen = () => {};

/**
 * The workspace's Sidebar state. The rail is always shown from 900px (the prototype's rail does not
 * collapse, and the menu button exists only below 900px), so only the phone sheet opens and closes.
 */
export function NavigationProvider(
  props: Omit<ComponentProps<typeof SidebarProvider>, 'open' | 'onOpenChange' | 'defaultOpen'>,
) {
  return <SidebarProvider open onOpenChange={keepRailOpen} {...props} />;
}

/** The sticky header's menu button below 900px: it opens the navigation's sheet. */
export function NavigationTrigger({ label }: { label: string }) {
  const { triggerRef } = useSidebar();
  useEffect(() => {
    if (focusTriggerOnMount) {
      focusTriggerOnMount = false;
      triggerRef.current?.focus();
    }
  }, [triggerRef]);
  return (
    <SidebarTrigger
      className="-ml-2.5 flex-none text-foreground rail:hidden"
      aria-label={label}
      data-testid="workspace-menu"
    >
      <MenuIcon aria-hidden="true" />
    </SidebarTrigger>
  );
}

/**
 * The permitted staff routes in the shadcn/ui Sidebar (CR-017): from 900px the workspace's fixed
 * rail below the sticky header, below 900px its sheet. The current route is filled with the
 * salon's brand. Choosing a route closes the sheet.
 */
export function Navigation({
  items,
  label,
  description,
}: {
  items: NavigationItem[];
  label: string;
  description: string;
}) {
  const { isMobile, setOpenMobile } = useSidebar();
  return (
    <Sidebar sheetTitle={label} sheetDescription={description} className="top-header">
      <SidebarContent>
        <nav aria-label={label} data-testid="workspace-navigation">
          <SidebarMenu>
            {items.map((item) => (
              <SidebarMenuItem key={item.href}>
                <SidebarMenuButton asChild isActive={Boolean(item.current)}>
                  <Link
                    href={item.href}
                    data-testid={item.testId}
                    aria-current={item.current ? 'page' : undefined}
                    onClick={() => {
                      if (!isMobile) return;
                      focusTriggerOnMount = !item.current;
                      setOpenMobile(false);
                    }}
                  >
                    <NavigationIcon icon={item.icon} />
                    <span data-testid={item.alias}>{item.label}</span>
                  </Link>
                </SidebarMenuButton>
              </SidebarMenuItem>
            ))}
          </SidebarMenu>
        </nav>
      </SidebarContent>
    </Sidebar>
  );
}
