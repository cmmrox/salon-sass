'use client';
import { formatDay, formatInstant, viewerTimeZone } from '@salon/format';
import type { components } from '@salon/api-contracts/platform';
import { CalendarIcon } from 'lucide-react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Calendar } from '@/components/ui/calendar';
import { Card, CardDescription, CardTitle } from '@/components/ui/card';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Field, FieldDescription, FieldError, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Item, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Spinner } from '@/components/ui/spinner';
import { dayButtonLabel } from '@/lib/dates/calendar-labels';
import {
  operatorRead,
  operatorRequest,
  OperatorFailure,
  principalInsideLock,
  withOperatorLock,
} from '../client/operator-session';
import { ConsoleFailure, ConsoleLoading } from './ConsoleControls';
import { ConsoleSheet } from './ConsoleSheet';
import { HolidayDays } from './HolidayDays';

type YearList = components['schemas']['HolidayYearList'];
type YearDetail = components['schemas']['HolidayYearDetail'];
type DraftDay = components['schemas']['HolidayDraftDayView'];
type Kind = DraftDay['kind'];

const KINDS: Kind[] = ['poya', 'public', 'bank', 'mercantile'];

/** One console change under the operator lock, as every credential-bearing change is. */
async function change<T>(action: string, body: unknown): Promise<T> {
  return withOperatorLock(async () => {
    await principalInsideLock();
    return operatorRequest<T>(action, body);
  });
}

function StateBadge({ state, publishedAt }: { state: YearDetail['state']; publishedAt: string | null }) {
  const t = useTranslations('console.holidays');
  return (
    <Badge
      variant={state === 'published' ? 'positive' : 'secondary'}
      data-testid="console-holiday-state"
      data-state={state}
    >
      {state === 'draft'
        ? t('stateDraft')
        : state === 'changed'
          ? t('stateChanged')
          : t('statePublished', { date: formatInstant(publishedAt!, viewerTimeZone()) })}
    </Badge>
  );
}

/**
 * The years of the holiday calendar (F04-S03, the console overview's Holiday calendar card made a page): each
 * year as an Item row with its state, and for an Admin a year to start. Dates change by gazette every year,
 * so the calendar is kept here, never in code.
 */
export function HolidayYears({ canManage }: { canManage: boolean }) {
  const t = useTranslations('console.holidays');
  const router = useRouter();
  const [data, setData] = useState<YearList | null>(null);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [loading, setLoading] = useState(true);
  const [start, setStart] = useState(String(new Date().getFullYear() + 1));

  async function load() {
    setLoading(true);
    setFailure(null);
    try {
      setData(await operatorRead<YearList>('holiday-years'));
    } catch (error) {
      setFailure(error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable'));
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    let active = true;
    Promise.resolve().then(() => {
      if (active) void load();
    });
    return () => {
      active = false;
    };
  }, []);

  const validStart = /^\d{4}$/.test(start) && Number(start) >= 2000 && Number(start) <= 2100;
  return (
    <div className="flex min-w-0 flex-col gap-6" data-testid="console-holidays">
      <div className="flex flex-col gap-1">
        <h1 className="m-0 text-workspace-title leading-heading font-semibold" data-testid="console-holidays-title">
          {t('title')}
        </h1>
        <p className="m-0 text-small">{t('intro')}</p>
      </div>
      {canManage && (
        <Card className="gap-3" data-testid="console-holiday-start">
          <form
            className="flex flex-wrap items-end gap-3"
            onSubmit={(event) => {
              event.preventDefault();
              if (validStart) router.push(`/console/holidays/${start}`);
            }}
          >
            <Field className="w-auto">
              <FieldLabel htmlFor="console-holiday-start-year">{t('startYear')}</FieldLabel>
              <Input
                id="console-holiday-start-year"
                inputMode="numeric"
                maxLength={4}
                value={start}
                className="w-32"
                onChange={(event) => setStart(event.target.value.replace(/\D/g, ''))}
                aria-describedby="console-holiday-start-help"
                data-testid="console-holiday-start-year"
              />
            </Field>
            <Button type="submit" variant="outline" disabled={!validStart} data-testid="console-holiday-open">
              {t('openYear')}
            </Button>
            <FieldDescription id="console-holiday-start-help" className="basis-full">
              {t('startYearHelp')}
            </FieldDescription>
          </form>
        </Card>
      )}
      {failure && <ConsoleFailure failure={failure} busy={loading} onRetry={() => void load()} />}
      {loading && !data && <ConsoleLoading />}
      {data && data.items.length === 0 && (
        <Empty surface="card" data-testid="console-holidays-empty">
          <EmptyHeader>
            <EmptyTitle>{t('empty')}</EmptyTitle>
            <EmptyDescription>{t('emptyHelp')}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      )}
      {data && data.items.length > 0 && (
        <ItemGroup className="gap-2" data-testid="console-holiday-years">
          {data.items.map((year) => (
            <Item
              asChild
              variant="outline"
              className="bg-card"
              key={year.year}
              data-testid="console-holiday-year-row"
              data-year={year.year}
            >
              <Link href={`/console/holidays/${year.year}`} className="no-underline">
                <ItemContent>
                  <ItemTitle className="flex flex-wrap items-center gap-2">
                    {year.year}
                    <StateBadge state={year.state} publishedAt={year.publishedAt} />
                  </ItemTitle>
                  <ItemDescription className="text-foreground">
                    {t('yearSummary', { days: year.dayCount, revision: year.revision })}
                  </ItemDescription>
                </ItemContent>
              </Link>
            </Item>
          ))}
        </ItemGroup>
      )}
    </div>
  );
}

/** A day's date picked from a Popover with a Calendar inside the year (docs/engineering/web.md, "Styling"). */
function YearDatePicker({ year, value, onChange }: { year: number; value: string; onChange: (date: string) => void }) {
  const t = useTranslations('console.holidays');
  const [open, setOpen] = useState(false);
  const asDate = (text: string) => {
    const [y, m, d] = text.split('-').map(Number) as [number, number, number];
    return new Date(y, m - 1, d);
  };
  return (
    <Field>
      <FieldLabel htmlFor="console-holiday-day-date">{t('date')}</FieldLabel>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            id="console-holiday-day-date"
            variant="outline"
            className="w-full justify-between font-normal"
            data-testid="console-holiday-day-date"
          >
            {value ? formatDay(value) : t('chooseDate')}
            <CalendarIcon aria-hidden="true" />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-0" align="start" data-testid="console-holiday-day-calendar">
          <Calendar
            labels={{ labelDayButton: dayButtonLabel }}
            mode="single"
            selected={value ? asDate(value) : undefined}
            defaultMonth={value ? asDate(value) : new Date(year, 0, 1)}
            startMonth={new Date(year, 0, 1)}
            endMonth={new Date(year, 11, 1)}
            disabled={[{ before: new Date(year, 0, 1) }, { after: new Date(year, 11, 31) }]}
            onSelect={(picked) => {
              if (!picked) return;
              onChange(
                `${picked.getFullYear()}-${String(picked.getMonth() + 1).padStart(2, '0')}-${String(picked.getDate()).padStart(2, '0')}`,
              );
              setOpen(false);
            }}
          />
        </PopoverContent>
      </Popover>
    </Field>
  );
}

/** Add a day, or change one's name and kind, in the console's sheet. */
function DaySheet({
  year,
  day,
  trigger,
  onSaved,
}: {
  year: number;
  day?: DraftDay;
  trigger: ReactNode;
  onSaved: (detail: YearDetail) => void;
}) {
  const t = useTranslations('console.holidays');
  const [open, setOpen] = useState(false);
  const [date, setDate] = useState(day?.date ?? '');
  const [name, setName] = useState(day?.name ?? '');
  const [kind, setKind] = useState<Kind>(day?.kind ?? 'public');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  function toggle(next: boolean) {
    setOpen(next);
    if (next) {
      setDate(day?.date ?? '');
      setName(day?.name ?? '');
      setKind(day?.kind ?? 'public');
      setFailure(null);
    }
  }
  async function save() {
    setBusy(true);
    setFailure(null);
    try {
      const detail = await change<YearDetail>(day ? 'holiday-day-change' : 'holiday-day-add', {
        year: String(year),
        date,
        name,
        kind,
      });
      onSaved(detail);
      setOpen(false);
    } catch (error) {
      setFailure(error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable'));
    }
    setBusy(false);
  }
  const nameRefused = failure?.code === 'bad_request' && failure.fields.includes('name');
  return (
    <ConsoleSheet
      title={day ? t('changeDayTitle', { date: formatDay(day.date) }) : t('addDayTitle', { year })}
      trigger={trigger}
      open={open}
      onOpenChange={toggle}
      busy={busy}
      testId={day ? 'console-holiday-change-sheet' : 'console-holiday-add-sheet'}
    >
      {!day && <YearDatePicker year={year} value={date} onChange={setDate} />}
      <Field data-invalid={nameRefused || undefined}>
        <FieldLabel htmlFor="console-holiday-day-name">{t('name')}</FieldLabel>
        <Input
          id="console-holiday-day-name"
          value={name}
          maxLength={120}
          onChange={(event) => setName(event.target.value)}
          aria-invalid={nameRefused || undefined}
          aria-describedby={nameRefused ? 'console-holiday-day-name-error' : undefined}
          data-testid="console-holiday-day-name"
        />
        {nameRefused && (
          <FieldError id="console-holiday-day-name-error" role={undefined}>
            {t('nameRule')}
          </FieldError>
        )}
      </Field>
      <Field>
        <FieldLabel htmlFor="console-holiday-day-kind">{t('kind')}</FieldLabel>
        <Select value={kind} onValueChange={(value) => setKind(value as Kind)}>
          <SelectTrigger id="console-holiday-day-kind" data-testid="console-holiday-day-kind">
            <SelectValue />
          </SelectTrigger>
          <SelectContent position="popper" sideOffset={4}>
            {KINDS.map((each) => (
              <SelectItem key={each} value={each} data-testid={`console-holiday-day-kind-${each}`}>
                {t(`kinds.${each}`)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Field>
      {failure && (
        <Alert variant="notice" role="alert" data-testid="console-holiday-day-error">
          {failure.code === 'conflict'
            ? t('dateTaken')
            : failure.code === 'bad_request'
              ? t('dayRefused')
              : t('failed')}
        </Alert>
      )}
      <Button
        className="w-full"
        disabled={busy || (!day && date === '') || name.trim() === ''}
        onClick={() => void save()}
        data-testid="console-holiday-day-save"
      >
        {busy && <Spinner />}
        {day ? t('saveDay') : t('addDay')}
      </Button>
    </ConsoleSheet>
  );
}

/**
 * One year of the holiday calendar (F04-S03): its days in a Table, what publishing would add, change and
 * withdraw, and for an Admin the CSV import, single-day edits and Publish to salons, confirmed in the
 * prototype's words. A refused import lists each bad line and why; nothing of it is kept.
 */
export function HolidayYearScreen({ year, canManage }: { year: number; canManage: boolean }) {
  const t = useTranslations('console.holidays');
  const [detail, setDetail] = useState<YearDetail | null>(null);
  const [missing, setMissing] = useState(false);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [refusedFile, setRefusedFile] = useState<OperatorFailure | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<'import' | 'publish' | 'remove' | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const file = useRef<HTMLInputElement>(null);

  async function load() {
    setLoading(true);
    setFailure(null);
    try {
      setDetail(await operatorRead<YearDetail>(`holiday-year?year=${year}`));
      setMissing(false);
    } catch (error) {
      const refused = error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
      if (refused.code === 'not_found') setMissing(true);
      else setFailure(refused);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    let active = true;
    Promise.resolve().then(() => {
      if (active) void load();
    });
    return () => {
      active = false;
    };
    // Loaded once for the year; a change answers the year as it now is.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [year]);

  function show(next: YearDetail, message: string) {
    setDetail(next);
    setMissing(false);
    setFailure(null);
    setDone(message);
  }

  async function importFile(chosen: File) {
    setBusy('import');
    setRefusedFile(null);
    setDone(null);
    const form = new FormData();
    form.append('year', String(year));
    form.append('file', chosen);
    try {
      const next = await change<YearDetail>('holiday-import', form);
      show(next, t('imported', { days: next.days.length }));
    } catch (error) {
      const refused = error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
      if (refused.code === 'bad_request' || refused.code === 'request_rejected') setRefusedFile(refused);
      else setFailure(refused);
    }
    if (file.current) file.current.value = '';
    setBusy(null);
  }

  async function remove(day: DraftDay) {
    setBusy('remove');
    setDone(null);
    try {
      show(await change<YearDetail>('holiday-day-remove', { year: String(year), date: day.date }), t('removed'));
    } catch (error) {
      setFailure(error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable'));
    }
    setBusy(null);
  }

  async function publish() {
    if (!detail) return;
    setBusy('publish');
    setDone(null);
    try {
      show(
        await change<YearDetail>('holiday-publish', { year: String(year), version: detail.version }),
        t('published'),
      );
    } catch (error) {
      setFailure(error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable'));
    }
    setBusy(null);
  }

  const changes = detail?.changes;
  const nothingToPublish = !changes || changes.added + changes.changed + changes.withdrawn === 0;
  return (
    <div className="flex min-w-0 flex-col gap-6" data-testid="console-holiday-year" data-year={year}>
      <div className="flex flex-col gap-2">
        <Link href="/console/holidays" className="text-small" data-testid="console-holiday-back">
          {t('back')}
        </Link>
        <div className="flex flex-wrap items-center gap-2">
          <h1
            className="m-0 text-workspace-title leading-heading font-semibold"
            data-testid="console-holiday-year-title"
          >
            {t('yearTitle', { year })}
          </h1>
          {detail && <StateBadge state={detail.state} publishedAt={detail.publishedAt} />}
        </div>
      </div>
      {failure && <ConsoleFailure failure={failure} busy={loading} onRetry={() => void load()} />}
      {done && (
        <Alert variant="success" role="status" data-testid="console-holiday-done">
          {done}
        </Alert>
      )}
      {loading && !detail && !missing && <ConsoleLoading />}
      {canManage && (detail || missing) && (
        <Card className="gap-3" data-testid="console-holiday-import">
          <CardTitle className="text-large">{t('importTitle')}</CardTitle>
          <Field>
            <FieldLabel htmlFor="console-holiday-file" className="sr-only">
              {t('importLabel')}
            </FieldLabel>

            {/* The hidden input sits in this row, not as the Field's own child, whose rules would widen it. */}
            <div className="relative flex flex-wrap items-center gap-2">
              <Input
                ref={file}
                id="console-holiday-file"
                type="file"
                accept=".csv,text/csv"
                className="sr-only top-0 left-0 size-px min-h-0 min-w-0 p-0"
                tabIndex={-1}
                aria-describedby="console-holiday-file-help"
                data-testid="console-holiday-file"
                onChange={(event) => {
                  const chosen = event.target.files?.[0];
                  if (chosen) void importFile(chosen);
                }}
              />
              <Button
                variant="outline"
                disabled={busy !== null}
                onClick={() => file.current?.click()}
                data-testid="console-holiday-choose-file"
              >
                {busy === 'import' && <Spinner />}
                {t('chooseFile')}
              </Button>
              <DaySheet
                year={year}
                trigger={
                  <Button variant="outline" disabled={busy !== null} data-testid="console-holiday-add">
                    {t('addDay')}
                  </Button>
                }
                onSaved={(next) => show(next, t('saved'))}
              />
            </div>
            <FieldDescription id="console-holiday-file-help">{t('importHelp', { year })}</FieldDescription>
          </Field>
          {refusedFile && (
            <Alert variant="destructive" role="alert" data-testid="console-holiday-import-refused">
              <AlertTitle>{t('importRefused')}</AlertTitle>
              <AlertDescription>
                {refusedFile.lines.length > 0 ? (
                  <ul className="m-0 flex list-none flex-col gap-1 p-0" data-testid="console-holiday-bad-lines">
                    {refusedFile.lines.map((bad) => (
                      <li key={`${bad.line}-${bad.detail}`} data-testid="console-holiday-bad-line" data-line={bad.line}>
                        {t('badLine', { line: bad.line, reason: bad.detail })}
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="m-0">
                    {refusedFile.code === 'request_rejected' ? t('fileTooLarge') : t('fileMissing')}
                  </p>
                )}
              </AlertDescription>
            </Alert>
          )}
        </Card>
      )}
      {missing && (
        <Empty surface="card" data-testid="console-holiday-year-empty">
          <EmptyHeader>
            <EmptyTitle>{t('yearEmpty', { year })}</EmptyTitle>
            <EmptyDescription>{canManage ? t('yearEmptyHelp') : t('yearEmptyRead')}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      )}
      {detail && (
        <Card className="gap-3" data-testid="console-holiday-days-card">
          <CardTitle className="text-large">{t('daysTitle', { count: detail.days.length })}</CardTitle>
          {detail.days.length === 0 ? (
            <CardDescription>{t('noDays')}</CardDescription>
          ) : (
            <HolidayDays
              days={detail.days}
              actions={
                canManage
                  ? (day) => (
                      <>
                        <DaySheet
                          year={year}
                          day={day}
                          trigger={
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={busy !== null}
                              aria-label={t('changeDay', { date: formatDay(day.date) })}
                              data-testid="console-holiday-day-edit"
                            >
                              {t('edit')}
                            </Button>
                          }
                          onSaved={(next) => show(next, t('saved'))}
                        />
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy !== null}
                          onClick={() => void remove(day)}
                          aria-label={t('removeDay', { date: formatDay(day.date) })}
                          data-testid="console-holiday-day-remove"
                        >
                          {t('remove')}
                        </Button>
                      </>
                    )
                  : null
              }
            />
          )}
          {detail.withdrawn.length > 0 && (
            <div className="flex flex-col gap-1" data-testid="console-holiday-withdrawn">
              <p className="m-0 text-small font-medium">{t('withdrawnTitle')}</p>
              <ul className="m-0 flex list-none flex-col gap-1 p-0 text-small">
                {detail.withdrawn.map((day) => (
                  <li key={day.date}>{t('withdrawnDay', { date: formatDay(day.date), name: day.name })}</li>
                ))}
              </ul>
            </div>
          )}
        </Card>
      )}
      {detail && (
        <Card className="gap-3" data-testid="console-holiday-publication">
          <CardTitle className="text-large">{t('publicationTitle')}</CardTitle>
          <p className="m-0 text-small" data-testid="console-holiday-counts">
            {nothingToPublish
              ? t('nothingToPublish')
              : t('counts', { added: changes!.added, changed: changes!.changed, withdrawn: changes!.withdrawn })}
          </p>
          {canManage && (
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button
                  className="w-fit"
                  disabled={busy !== null || nothingToPublish}
                  data-testid="console-holiday-publish"
                >
                  {busy === 'publish' && <Spinner />}
                  {t('publish', { year })}
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent data-testid="console-holiday-publish-dialog">
                <AlertDialogHeader>
                  <AlertDialogTitle>{t('publishTitle', { year })}</AlertDialogTitle>
                  <AlertDialogDescription>{t('publishBody')}</AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel data-testid="console-holiday-publish-cancel">{t('cancel')}</AlertDialogCancel>
                  <AlertDialogAction onClick={() => void publish()} data-testid="console-holiday-publish-confirm">
                    {t('publishConfirm')}
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          )}
        </Card>
      )}
    </div>
  );
}
