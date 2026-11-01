'use client';
import { formatDay } from '@salon/format';
import type { components } from '@salon/api-contracts/platform';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Badge } from '@/components/ui/badge';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useIsMobile } from '@/hooks/use-mobile';

type DraftDay = components['schemas']['HolidayDraftDayView'];

/**
 * A holiday year's days in the console (F04-S03): a Table to compare across columns from 900px, and below
 * 900px, where five columns cannot fit a phone, one Item row per day with its date, name, kind, change and
 * actions stacked (QA's F04-S03 dry run: the table was 505px wide at 360px). Only one of the two is rendered,
 * so each day has one row and its test ids stay unique. Names wrap between words, never inside one.
 */
export function HolidayDays({ days, actions }: { days: DraftDay[]; actions: ((day: DraftDay) => ReactNode) | null }) {
  const t = useTranslations('console.holidays');
  const narrow = useIsMobile();
  const change = (day: DraftDay) =>
    day.change !== 'unchanged' && (
      <Badge variant="secondary" data-testid="console-holiday-day-change">
        {t(`changes.${day.change}`)}
      </Badge>
    );
  if (narrow)
    return (
      <ItemGroup className="gap-2" data-testid="console-holiday-days">
        {days.map((day) => (
          <Item
            variant="outline"
            role="listitem"
            className="flex-wrap"
            key={day.date}
            data-testid="console-holiday-day"
            data-date={day.date}
          >
            <ItemContent className="min-w-0 basis-full">
              <ItemTitle className="flex flex-wrap items-center gap-2">
                {formatDay(day.date)}
                {change(day)}
              </ItemTitle>
              <ItemDescription className="text-foreground">
                <span className="break-words" data-testid="console-holiday-day-name">
                  {day.name}
                </span>
                {t('kindAfterName', { kind: t(`kinds.${day.kind}`) })}
              </ItemDescription>
            </ItemContent>
            {actions && <ItemActions className="flex-wrap">{actions(day)}</ItemActions>}
          </Item>
        ))}
      </ItemGroup>
    );
  return (
    <Table data-testid="console-holiday-days">
      <TableHeader>
        <TableRow>
          <TableHead>{t('date')}</TableHead>
          <TableHead>{t('name')}</TableHead>
          <TableHead>{t('kind')}</TableHead>
          <TableHead>{t('change')}</TableHead>
          {actions && <TableHead className="sr-only">{t('actions')}</TableHead>}
        </TableRow>
      </TableHeader>
      <TableBody>
        {days.map((day) => (
          <TableRow key={day.date} data-testid="console-holiday-day" data-date={day.date}>
            <TableCell className="whitespace-nowrap">{formatDay(day.date)}</TableCell>
            <TableCell className="break-words whitespace-normal" data-testid="console-holiday-day-name">
              {day.name}
            </TableCell>
            <TableCell>{t(`kinds.${day.kind}`)}</TableCell>
            <TableCell>{change(day)}</TableCell>
            {actions && (
              <TableCell className="whitespace-normal">
                <div className="flex flex-wrap justify-end gap-2">{actions(day)}</div>
              </TableCell>
            )}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
