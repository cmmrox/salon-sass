'use client';
import { useState } from 'react';
import { useTranslations } from 'next-intl';
import { Search } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Field, FieldDescription, FieldLabel } from '@/components/ui/field';
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/ui/input-group';
import { Pagination, PaginationContent, PaginationCurrent, PaginationItem, PaginationNext, PaginationPrevious } from '@/components/ui/pagination';

/** A literal search of the directory, submitted on Enter or the button. */
export function DirectorySearch({ area, help, busy, onSearch }: { area: 'salons' | 'plans'; help: string; busy: boolean; onSearch: (query: string) => void }) {
  const t = useTranslations('console');
  const [value, setValue] = useState('');
  const id = `console-${area}-search`;
  return (
    <form role="search" className="flex min-w-0 flex-col gap-2 rail:flex-row rail:items-end" onSubmit={(event) => { event.preventDefault(); onSearch(value); }}>
      <Field className="min-w-0 flex-1">
        <FieldLabel htmlFor={id}>{t('search')}</FieldLabel>
        <InputGroup>
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          <InputGroupInput id={id} type="search" maxLength={120} data-testid={id} aria-describedby={`${id}-help`} value={value} onChange={(event) => setValue(event.target.value)} />
        </InputGroup>
        <FieldDescription id={`${id}-help`}>{help}</FieldDescription>
      </Field>
      <Button type="submit" variant="outline" className="self-start rail:mb-7" data-testid={`${id}-submit`} disabled={busy}>{t('searchAction')}</Button>
    </form>
  );
}

/** Previous and next pages, in place, with the position and total. */
export function DirectoryPager({ area, page, size, total, busy, onPage }: { area: 'salons' | 'plans'; page: number; size: number; total: number; busy: boolean; onPage: (page: number) => void }) {
  const t = useTranslations('console');
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <p className="m-0 text-small" data-testid={`console-${area}-total`}>{t('total', { total })}</p>
      <Pagination aria-label={t('pages')} data-testid={`console-${area}-pagination`}>
        <PaginationContent>
          <PaginationItem><PaginationPrevious data-testid={`console-${area}-previous`} disabled={busy || page === 0} onClick={() => onPage(page - 1)}>{t('previous')}</PaginationPrevious></PaginationItem>
          <PaginationItem><PaginationCurrent data-testid={`console-${area}-page`}>{t('page', { page: page + 1, pages })}</PaginationCurrent></PaginationItem>
          <PaginationItem><PaginationNext data-testid={`console-${area}-next`} disabled={busy || (page + 1) * size >= total} onClick={() => onPage(page + 1)}>{t('next')}</PaginationNext></PaginationItem>
        </PaginationContent>
      </Pagination>
    </div>
  );
}
