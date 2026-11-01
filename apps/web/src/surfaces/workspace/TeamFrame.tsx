'use client';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Alert } from '@/components/ui/alert';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

/** The Team page's tabs, in the prototype's order (`tmTabs`): each is its own address. */
export type TeamTab = 'team' | 'accounts';
const TABS: TeamTab[] = ['team', 'accounts'];
const PATHS: Record<TeamTab, string> = { team: '/workspace/team', accounts: '/workspace/staff' };

/**
 * What both tabs of the Team page show (F06-S01 AC-11): the summary and the tab's action above the tab bar, then the
 * open tab. The tab bar shows only for those with `staff.list`; the others have the Team tab alone.
 */
export function TeamFrame({
  tab,
  showTabs,
  summary,
  action,
  children,
}: {
  tab: TeamTab;
  showTabs: boolean;
  summary?: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  const t = useTranslations('team');
  const router = useRouter();
  const line = tab === 'accounts' ? t('accountsSummary') : summary;
  const panel = (
    <>
      {tab === 'accounts' && (
        <Alert variant="info" className="text-small" data-testid="team-accounts-banner">
          {t('accountsBanner')}
        </Alert>
      )}
      {children}
    </>
  );
  return (
    <>
      <div className="-mt-3 flex flex-wrap items-center justify-between gap-3">
        <p className="text-small text-foreground" data-testid="team-summary">
          {line}
        </p>
        {action}
      </div>
      {showTabs ? (
        <Tabs value={tab} onValueChange={(next) => next !== tab && router.push(PATHS[next as TeamTab])}>
          <TabsList aria-label={t('tabsLabel')} data-testid="team-tabs">
            {TABS.map((each) => (
              <TabsTrigger key={each} value={each} data-testid={`team-tab-${each}`}>
                {t(`tabs.${each}`)}
              </TabsTrigger>
            ))}
          </TabsList>
          <TabsContent value={tab} className="flex flex-col gap-4" data-testid={`team-panel-${tab}`}>
            {panel}
          </TabsContent>
        </Tabs>
      ) : (
        <div className="flex flex-col gap-4" data-testid={`team-panel-${tab}`}>
          {panel}
        </div>
      )}
    </>
  );
}
