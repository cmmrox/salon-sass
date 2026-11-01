'use client';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { SettingsLeaveProvider, useSettingsLeaveCheck } from './settings-leave';

/**
 * The Settings tabs delivered so far, in the prototype's order (F04-S01 Profile, F04-S03 Hours & closures,
 * F04-S04 Booking rules, F05-S03 Tax & charges, F04-S02 Branding).
 */
export type SettingsTab = 'profile' | 'hours' | 'rules' | 'tax' | 'branding';
const TABS: SettingsTab[] = ['profile', 'hours', 'rules', 'tax', 'branding'];
/** The intro each tab shows: Profile and Branding are drafts to publish; the other tabs save at once. */
const INTRO: Record<SettingsTab, 'intro' | 'introHours' | 'introRules' | 'introTax'> = {
  profile: 'intro',
  hours: 'introHours',
  rules: 'introRules',
  tax: 'introTax',
  branding: 'intro',
};

/**
 * The Settings page (F04-S01): the prototype's intro and its section tabs, showing only the tabs that
 * have been delivered, then the open tab. Each tab is its own address (`/workspace/settings/<tab>`), so
 * choosing another tab goes there, unless the tab on screen holds the move to ask about unsaved edits first.
 */
export function SettingsScreen({ tab, children }: { tab: SettingsTab; children: ReactNode }) {
  return (
    <SettingsLeaveProvider>
      <SettingsTabs tab={tab}>{children}</SettingsTabs>
    </SettingsLeaveProvider>
  );
}

function SettingsTabs({ tab, children }: { tab: SettingsTab; children: ReactNode }) {
  const t = useTranslations('settings');
  const router = useRouter();
  const held = useSettingsLeaveCheck();
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {/* Ink, not muted: small muted text on the warm canvas is under 4.5:1 (ui-ux-standards.md). */}
      {/* Hours, holidays, closures (F04-S03), booking rules (F04-S04) and tax (F05-S03) save at once; Profile and Branding are drafts. */}
      <p className="-mt-5 mb-1 text-small text-foreground" data-testid="settings-intro">
        {t(INTRO[tab])}
      </p>
      <Tabs
        value={tab}
        onValueChange={(next) => {
          const href = `/workspace/settings/${next}`;
          if (next !== tab && !held(href)) router.push(href);
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
