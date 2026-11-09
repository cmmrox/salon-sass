'use client';
import { formatDay } from '@salon/format';
import { CalendarIcon, SearchIcon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Calendar } from '@/components/ui/calendar';
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/ui/input-group';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { dayButtonLabel } from '@/lib/dates/calendar-labels';
import { dateOfText, textOfDate } from '../client/booking-time';
import { SEARCH_MAX, STATUS_FILTERS, type StatusFilter } from '../client/bookings-query';

/** A Select item cannot have an empty value; this one is everyone. */
const EVERYONE = 'all';

type Props = {
  /** The day shown and the salon's today, both `YYYY-MM-DD`. */
  day: string;
  today: string;
  search: string;
  status: StatusFilter;
  professional: string | null;
  /** The team for the professional Select; null for a caller who sees only their own bookings (AC-10). */
  members: { id: string; name: string }[] | null;
  onSearch: (search: string) => void;
  onDay: (day: string) => void;
  onStatus: (status: StatusFilter) => void;
  onProfessional: (professional: string | null) => void;
};

/**
 * The Bookings page's tools (the prototype's `f7Tools`, AC-3): the search as typed, the day chosen from a Calendar with
 * a Today shortcut, the status, and the professional for those who see the salon's bookings.
 */
export function BookingsTools(props: Props) {
  const t = useTranslations('bookings.tools');
  const states = useTranslations('bookings.states');
  const [picking, setPicking] = useState(false);
  return (
    <div
      className="flex flex-wrap items-center gap-2"
      role="search"
      aria-label={t('label')}
      data-testid="bookings-tools"
    >
      <InputGroup className="w-full rail:w-auto rail:min-w-72 rail:flex-1">
        <InputGroupAddon>
          <SearchIcon aria-hidden="true" />
        </InputGroupAddon>
        <InputGroupInput
          type="search"
          aria-label={t('search')}
          placeholder={t('searchPlaceholder')}
          maxLength={SEARCH_MAX}
          value={props.search}
          onChange={(event) => props.onSearch(event.target.value)}
          data-testid="bookings-search"
        />
      </InputGroup>
      <Popover open={picking} onOpenChange={setPicking}>
        <PopoverTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            className="font-normal"
            aria-label={t('dayChosen', { day: formatDay(props.day) })}
            data-testid="bookings-day"
          >
            <CalendarIcon aria-hidden="true" />
            {formatDay(props.day)}
          </Button>
        </PopoverTrigger>
        <PopoverContent
          className="w-auto p-0"
          align="start"
          aria-label={t('dayCalendar')}
          data-testid="bookings-calendar"
        >
          <Calendar
            labels={{ labelDayButton: dayButtonLabel }}
            mode="single"
            required
            today={dateOfText(props.today)}
            selected={dateOfText(props.day)}
            defaultMonth={dateOfText(props.day)}
            onSelect={(picked) => {
              if (!picked) return;
              props.onDay(textOfDate(picked));
              setPicking(false);
            }}
          />
        </PopoverContent>
      </Popover>
      {props.day !== props.today && (
        <Button variant="outline" size="sm" onClick={() => props.onDay(props.today)} data-testid="bookings-day-today">
          {t('today')}
        </Button>
      )}
      <Select value={props.status} onValueChange={(next) => props.onStatus(next as StatusFilter)}>
        <SelectTrigger className="w-auto" aria-label={t('status')} data-testid="bookings-status">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="all" data-testid="bookings-status-option-all">
            {t('allStatuses')}
          </SelectItem>
          {STATUS_FILTERS.map((status) => (
            <SelectItem key={status} value={status} data-testid={`bookings-status-option-${status}`}>
              {states(status)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {props.members && (
        <Select
          value={props.professional ?? EVERYONE}
          onValueChange={(next) => props.onProfessional(next === EVERYONE ? null : next)}
        >
          <SelectTrigger
            className="w-auto max-w-full"
            aria-label={t('professional')}
            data-testid="bookings-professional"
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={EVERYONE} data-testid="bookings-professional-option-all">
              {t('everyone')}
            </SelectItem>
            {props.members.map((member) => (
              <SelectItem
                key={member.id}
                value={member.id}
                data-testid="bookings-professional-option"
                data-id={member.id}
              >
                {member.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}
    </div>
  );
}
