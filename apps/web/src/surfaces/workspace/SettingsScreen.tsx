'use client';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

/** The Settings tabs delivered so far, in the prototype's order (F04-S01 Profile, F04-S02 Branding). */
export type SettingsTab = 'profile' | 'branding';
const TABS: SettingsTab[] = ['profile', 'branding'];

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
      {/* Ink, not muted: small muted text on the warm canvas is under 4.5:1 (ui-ux-standards.md). */}
      <p className="-mt-5 mb-1 text-small text-foreground">{t('intro')}</p>
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
