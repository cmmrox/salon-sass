'use client';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

/** The Settings tabs delivered so far (F04-S01): Profile. Later stages add theirs here. */
export type SettingsTab = 'profile';
const TABS: SettingsTab[] = ['profile'];

/**
 * The Settings page (F04-S01): the prototype's intro and its section tabs, showing only the tabs that
 * have been delivered, then the open tab. Each tab is its own address (`/workspace/settings/<tab>`), so
 * choosing another tab goes there, through the unsaved-edits guard like any other link.
 */
export function SettingsScreen({ tab, children }: { tab: SettingsTab; children: ReactNode }) {
  const t = useTranslations('settings');
  const router = useRouter();
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <p className="m-0 text-foreground">{t('intro')}</p>
      <Tabs
        value={tab}
        onValueChange={(next) => {
          if (next !== tab) router.push(`/workspace/settings/${next}`);
        }}
      >
        <TabsList aria-label={t('tabsLabel')} data-testid="settings-tabs">
          {TABS.map((each) => (
            <TabsTrigger key={each} value={each} data-testid={`settings-tab-${each}`}>
              {t(`tabs.${each}`)}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value={tab} data-testid={`settings-panel-${tab}`}>
          {children}
        </TabsContent>
      </Tabs>
    </div>
  );
}
