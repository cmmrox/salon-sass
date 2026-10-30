import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';

/** The frame every page of this stage sits in: the product's name above the page's content. */
export function AppShell({ children }: { children: ReactNode }) {
  const t = useTranslations('app');
  return (
    <div className="flex min-h-screen flex-col items-center gap-6 px-4 py-8">
      <header className="w-full max-w-content">
        <p className="text-large leading-heading font-semibold wrap-anywhere text-foreground" data-testid="app-name">
          {t('name')}
        </p>
      </header>
      <main className="w-full max-w-content">{children}</main>
    </div>
  );
}
