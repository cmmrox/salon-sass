'use client';
import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Search } from 'lucide-react';
import { Field, FieldDescription, FieldLabel } from '@/components/ui/field';
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/ui/input-group';
import type { OperatorFailure } from '../client/operator-session';
import { useConsolePages } from '../client/console-data';
import { ConsoleChoiceField, consoleTestId } from './ConsoleControls';
import { DirectoryPager } from './DirectoryControls';
import type { Plan } from './PlanScreens';

/** Plans per page of the picker: the Select lists one page, newest first, as the plan list does. */
const PLAN_PICKER_SIZE = 100;

/**
 * The plan to assign (Create salon) or move to (Move plan), from every plan there is (BUG-F03-006):
 * one page of the plan list in a Select, a literal search by name or key, and previous and next pages
 * when there are more plans than one page holds. The chosen plan stays chosen across searches and
 * pages. Rendered inside a sheet, so it loads when the sheet opens.
 */
export function PlanPicker({
  name,
  label,
  placeholder,
  value,
  error,
  exclude,
  optionLabel,
  emptyText,
  onChange,
  onFailure,
  onAvailable,
}: {
  name: 'planId' | 'movePlanId';
  label: string;
  placeholder: string;
  value: string;
  error: boolean;
  /** A plan not to offer: the salon's current plan when moving. */
  exclude?: string;
  optionLabel: (plan: Plan) => string;
  /** What to say when there is no plan to offer at all. */
  emptyText: string;
  onChange: (plan: Plan) => void;
  onFailure: (failure: OperatorFailure) => void;
  /** Whether any plan can be offered at all, once the unfiltered first page has loaded. */
  onAvailable?: (available: boolean) => void;
}) {
  const t = useTranslations('console');
  const id = consoleTestId(name);
  const [text, setText] = useState('');
  const [chosen, setChosen] = useState<Plan | null>(null);
  const picker = useConsolePages(
    'plans',
    (page, search) => ({ page, size: PLAN_PICKER_SIZE, ...(search ? { q: search } : {}) }),
    { onFailure },
  );
  const { data, page, search: query, loading, load } = picker;
  // Whether any plan can be offered at all, said once the unfiltered first page has arrived.
  useEffect(() => {
    if (data && !query && page === 0) onAvailable?.(data.items.some((plan) => plan.id !== exclude));
  }, [data, query, page, onAvailable, exclude]);

  const listed = (data?.items ?? []).filter((plan) => plan.id !== exclude);
  const offered =
    chosen && chosen.id === value && !listed.some((plan) => plan.id === chosen.id) ? [chosen, ...listed] : listed;
  const description = data && offered.length === 0 ? (query ? t('plansNoMatch') : emptyText) : undefined;
  const search = () => load(0, text.trim());
  const searchId = `${id}-search`;
  return (
    <div className="flex min-w-0 flex-col gap-3" aria-busy={loading || undefined}>
      <Field className="min-w-0">
        <FieldLabel htmlFor={searchId}>{t('planSearch')}</FieldLabel>
        <InputGroup>
          <InputGroupAddon>
            <Search aria-hidden="true" />
          </InputGroupAddon>
          <InputGroupInput
            id={searchId}
            type="search"
            maxLength={120}
            data-testid={searchId}
            aria-describedby={`${searchId}-help`}
            value={text}
            onChange={(event) => setText(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault();
                search();
              }
            }}
          />
          <InputGroupAddon align="inline-end">
            <InputGroupButton
              variant="outline"
              className="rounded-action"
              data-testid={`${searchId}-submit`}
              disabled={loading}
              onClick={search}
            >
              {t('searchAction')}
            </InputGroupButton>
          </InputGroupAddon>
        </InputGroup>
        <FieldDescription id={`${searchId}-help`}>{t('planSearchHelp')}</FieldDescription>
      </Field>
      <ConsoleChoiceField
        name={name}
        label={label}
        placeholder={placeholder}
        value={value}
        error={error}
        description={description}
        disabled={!data || offered.length === 0}
        options={offered.map((plan) => ({ value: plan.id, label: optionLabel(plan) }))}
        onChange={(planId) => {
          const plan = offered.find((candidate) => candidate.id === planId);
          if (plan) {
            setChosen(plan);
            onChange(plan);
          }
        }}
      />
      {data && data.total > PLAN_PICKER_SIZE && (
        <DirectoryPager
          area={id.slice('console-'.length) as 'plan-id' | 'move-plan-id'}
          page={page}
          size={PLAN_PICKER_SIZE}
          total={data.total}
          busy={loading}
          onPage={(next) => void load(next, query)}
        />
      )}
    </div>
  );
}
