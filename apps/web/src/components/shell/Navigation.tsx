import Link from 'next/link';

export type NavigationItem = { href: string; label: string; testId: string; icon: 'home' | 'account' | 'staff' | 'audit'; current?: boolean; alias?: string };

/** The prototype's own navigation glyphs, drawn in the current text colour. */
export function NavigationIcon({ icon }: { icon: NavigationItem['icon'] }) {
  const paths = {
    home: 'm3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z',
    account: 'M20 21v-2a7 7 0 0 0-14 0v2M17 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
    staff: 'M16 21v-2a6 6 0 0 0-12 0v2M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0ZM20 21v-2a6 6 0 0 0-3-5.2M17 3.2a4 4 0 0 1 0 7.6',
    audit: 'M8 3h8v4H8ZM8 5H5v16h14V5h-3M8 12h8M8 16h6',
  };
  return <svg className="size-icon flex-none" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[icon]} /></svg>;
}

/**
 * The permitted staff routes: below 900px a fixed bottom tab bar, from 900px the workspace's
 * sticky rail. The current route is filled with the salon's brand (its soft tint on phones).
 */
export function Navigation({ items, label }: { items: NavigationItem[]; label: string }) {
  return (
    <nav
      className="fixed bottom-0 left-0 z-30 flex w-full justify-around bg-card px-1.5 pt-2 pb-[calc(var(--spacing)*2+env(safe-area-inset-bottom,0px))] shadow-tabs rail:sticky rail:top-header rail:h-[calc(100vh-var(--size-header))] rail:flex-col rail:justify-start rail:gap-1 rail:border-r rail:border-border rail:px-3 rail:py-4 rail:shadow-none"
      aria-label={label}
      data-testid="workspace-navigation"
    >
      {items.map((item) => (
        <Link
          key={item.href}
          href={item.href}
          data-testid={item.testId}
          aria-current={item.current ? 'page' : undefined}
          className="group flex min-h-tab min-w-0 flex-1 flex-col items-center justify-center gap-0.5 p-0 text-center text-nav font-medium text-muted-foreground no-underline wrap-anywhere aria-[current=page]:font-semibold aria-[current=page]:text-foreground rail:min-h-rail-row rail:flex-none rail:flex-row rail:justify-start rail:gap-3 rail:rounded-rail-item rail:px-3.5 rail:text-left rail:text-rail rail:aria-[current=page]:bg-primary rail:aria-[current=page]:text-primary-foreground rail:not-aria-[current=page]:hover:bg-background"
        >
          <span className="flex items-center justify-center rounded-action px-4 py-1 group-aria-[current=page]:bg-accent rail:p-0 rail:group-aria-[current=page]:bg-transparent">
            <NavigationIcon icon={item.icon} />
          </span>
          <span data-testid={item.alias}>{item.label}</span>
        </Link>
      ))}
    </nav>
  );
}
