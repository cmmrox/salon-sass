'use client';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { SHEET_CLASSES } from './MemberSheet';
import { MemberAvatar } from './TeamCards';

/** The sheet's sections, in the prototype's order (`schTabs`). */
export type ScheduleTab = 'week' | 'changes' | 'leave' | 'blocked';
const TABS: ScheduleTab[] = ['week', 'changes', 'leave', 'blocked'];

type Props = {
  open: boolean;
  member: { id: string; name: string; photoId: string | null };
  tab: ScheduleTab;
  loading: boolean;
  onTab: (tab: ScheduleTab) => void;
  onClose: () => void;
  /** Where focus goes when the sheet has closed: what opened it, as it has no trigger of its own. */
  onClosed: () => void;
  notices: ReactNode;
  week: ReactNode;
  changes: ReactNode;
  leave: ReactNode;
  blocked: ReactNode;
};

/**
 * The schedule sheet (F06-S02, the prototype's `schPaint`), over the Team page: headed with the person's avatar and name,
 * with a tab for each section, whose row scrolls sideways on a phone. Focus stays inside it and goes back to what opened it.
 */
export function ScheduleSheet({
  open,
  member,
  tab,
  loading,
  onTab,
  onClose,
  onClosed,
  notices,
  week,
  changes,
  leave,
  blocked,
}: Props) {
  const t = useTranslations('team.schedule');
  return (
    <Sheet open={open} onOpenChange={(next) => !next && onClose()}>
      <SheetContent
        side="bottom"
        className={cn(SHEET_CLASSES)}
        data-testid="schedule-sheet"
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          onClosed();
        }}
      >
        <div className="flex items-center justify-between gap-3">
          <div className="flex min-w-0 items-center gap-2.5">
            <MemberAvatar id={member.id} name={member.name} photoId={member.photoId} />
            <div className="min-w-0">
              <SheetTitle className="rail:text-workspace-title">{t('title')}</SheetTitle>
              <SheetDescription className="text-small wrap-anywhere text-foreground" data-testid="schedule-name">
                {member.name}
              </SheetDescription>
            </div>
          </div>
          <SheetClose asChild>
            <Button variant="outline" size="sm" data-testid="schedule-close">
              {t('close')}
            </Button>
          </SheetClose>
        </div>
        {notices}
        {loading ? (
          <div
            className="flex flex-col gap-3"
            role="status"
            aria-busy="true"
            aria-label={t('loading')}
            data-testid="schedule-loading"
          >
            <Skeleton className="h-12 w-full rounded-card" />
            <Skeleton className="h-64 w-full rounded-card" />
          </div>
        ) : (
          <Tabs value={tab} onValueChange={(next) => onTab(next as ScheduleTab)}>
            <TabsList aria-label={t('tabsLabel')} data-testid="schedule-tabs">
              {TABS.map((each) => (
                <TabsTrigger key={each} value={each} data-testid={`schedule-tab-${each}`}>
                  {t(`tabs.${each}`)}
                </TabsTrigger>
              ))}
            </TabsList>
            <TabsContent value="week">{week}</TabsContent>
            <TabsContent value="changes">{changes}</TabsContent>
            <TabsContent value="leave">{leave}</TabsContent>
            <TabsContent value="blocked">{blocked}</TabsContent>
          </Tabs>
        )}
      </SheetContent>
    </Sheet>
  );
}
