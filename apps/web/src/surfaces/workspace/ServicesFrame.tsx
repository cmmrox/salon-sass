'use client';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

/** The Services & pricing page's tabs, in the prototype's order (`svTabs`): each is its own address. */
export type ServicesTab = 'services' | 'categories' | 'products';
const TABS: ServicesTab[] = ['services', 'categories', 'products'];
const PATHS: Record<ServicesTab, string> = {
  services: '/workspace/services',
  categories: '/workspace/services/categories',
  products: '/workspace/services/products',
};

/**
 * What every tab of Services & pricing shows (F05-S01, F05-S03): the summary and the tab's New action above the tab
 * bar, then the open tab.
 */
export function ServicesFrame({
  tab,
  summary,
  action,
  children,
}: {
  tab: ServicesTab;
  summary: string;
  action: ReactNode;
  children: ReactNode;
}) {
  const t = useTranslations('services');
  const router = useRouter();
  return (
    <>
      <div className="-mt-3 flex flex-wrap items-center justify-between gap-3">
        <p className="text-small text-foreground" data-testid="services-summary">
          {summary}
        </p>
        {action}
      </div>
      <Tabs value={tab} onValueChange={(next) => next !== tab && router.push(PATHS[next as ServicesTab])}>
        <TabsList aria-label={t('tabsLabel')} data-testid="services-tabs">
          {TABS.map((each) => (
            <TabsTrigger key={each} value={each} data-testid={`services-tab-${each}`}>
              {t(`tabs.${each}`)}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value={tab} className="flex flex-col gap-4" data-testid={`services-panel-${tab}`}>
          {children}
        </TabsContent>
      </Tabs>
    </>
  );
}
