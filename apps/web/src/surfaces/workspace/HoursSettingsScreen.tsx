'use client';
import { useTranslations } from 'next-intl';
import { toast } from 'sonner';
import { Skeleton } from '@/components/ui/skeleton';
import { Toaster } from '@/components/ui/sonner';
import type { Kind } from '@/features/identity';
import { AddClosureSheet, ClosuresCard, HolidaysCard, OpeningHoursCard } from '@/features/staff';
import { SettingsNotices } from './SettingsNotices';
import { useHoursSettings } from './use-hours-settings';

type ReadOnly = 'support' | 'salon' | null;

/**
 * The Hours & closures tab (F04-S03, the prototype's `setHours`): Opening hours and Public holidays side by
 * side from 900px, Special closures below, one column under 900px. Every change saves at once; a toast says
 * what changed, and the same notices as the other settings tabs say what went wrong.
 */
export function HoursSettingsScreen({
  kind,
  timeZone,
  readOnly,
}: {
  kind: Kind;
  timeZone: string;
  readOnly: ReadOnly;
}) {
  const t = useTranslations('settings.hoursTab');
  const state = useHoursSettings(kind, timeZone, (event) => toast.success(t(`toast.${event}`)));
  const locked = readOnly !== null || state.busy !== null;

  if (state.loading && !state.loaded)
    return (
      <div
        className="flex flex-col gap-4 rail:grid rail:grid-cols-2"
        data-testid="hours-loading"
        role="status"
        aria-busy="true"
        aria-label={t('loading')}
      >
        <Skeleton className="h-96 w-full rounded-card" />
        <Skeleton className="h-96 w-full rounded-card" />
      </div>
    );

  const notices = (
    <SettingsNotices
      subject="hours"
      readOnly={readOnly}
      notice={state.notice}
      refusal={state.refusal}
      hasDraft={state.loaded}
      onReload={() => void state.reload()}
    />
  );
  if (!state.loaded || !state.hours) return <div className="flex flex-col gap-4">{notices}</div>;

  return (
    <div className="flex flex-col gap-4" data-testid="hours-tab">
      {notices}
      <div className="flex min-w-0 flex-col gap-4 rail:grid rail:grid-cols-2 rail:items-start">
        <OpeningHoursCard
          week={state.week}
          saved={state.hours.weekly !== null}
          bad={state.bad}
          outside={state.hours.outsideHours}
          disabled={locked}
          saving={state.busy === 'hours'}
          dirty={state.dirty}
          onChange={state.changeDay}
          onSave={() => void state.saveHours()}
        />
        <HolidaysCard
          year={state.year}
          timeZone={timeZone}
          choices={state.choices}
          disabled={locked}
          busy={state.busy === 'holidays'}
          onChoose={(date, change) => void state.choose(date, change)}
          onAccept={() => void state.accept()}
        />
        <ClosuresCard
          closures={state.closures}
          disabled={locked}
          reopening={state.reopening}
          onReopen={(closure) => void state.reopen(closure)}
          addClosure={
            <AddClosureSheet
              open={state.sheetOpen}
              onOpenChange={state.setSheetOpen}
              today={state.today}
              disabled={readOnly !== null}
              busy={state.busy === 'closure'}
              impact={state.impact}
              refused={state.closureRefused}
              onDate={(date) => void state.impactOn(date)}
              onClose={(date, reason) => void state.close(date, reason)}
            />
          }
        />
      </div>
      <Toaster position="bottom-center" />
    </div>
  );
}
