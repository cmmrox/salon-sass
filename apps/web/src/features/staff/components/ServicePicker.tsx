'use client';
import { useTranslations } from 'next-intl';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Field, FieldContent, FieldDescription, FieldLabel } from '@/components/ui/field';
import { Toggle } from '@/components/ui/toggle';
import { categoryRows, toggleCategory, toggleService, type MemberValues } from '../client/team-form';
import type { TeamCategory, TeamService } from '../types';

type Props = {
  values: MemberValues;
  categories: TeamCategory[];
  services: TeamService[];
  disabled: boolean;
  onChange: (values: MemberValues) => void;
};

/**
 * **Services they perform** (F06-S01 AC-3, the prototype's `stCatRow`): the salon's categories that have services, in
 * its order. Ticking one means every service in it and any added later; **Choose** narrows it to single services.
 */
export function ServicePicker({ values, categories, services, disabled, onChange }: Props) {
  const t = useTranslations('team.sheet.picker');
  const [open, setOpen] = useState<string[]>([]);
  const rows = categoryRows(values, categories, services);
  if (rows.length === 0)
    return (
      <p className="text-small text-foreground" data-testid="team-picker-empty">
        {t('none')}
      </p>
    );
  return (
    <div className="flex flex-col gap-3" data-testid="team-picker">
      {rows.map((row) => {
        const id = `team-category-${row.category.id}`;
        const ticked = row.state !== 'none';
        const opened = ticked && open.includes(row.category.id);
        return (
          <div key={row.category.id} className="flex flex-col gap-2" data-testid="team-picker-row" data-id={row.category.id}>
            <div className="flex items-start justify-between gap-3">
              <Field orientation="horizontal" className="min-w-0 flex-1">
                <Checkbox
                  id={id}
                  checked={ticked}
                  disabled={disabled}
                  aria-describedby={`${id}-summary`}
                  onCheckedChange={() => onChange(toggleCategory(values, row))}
                  data-testid="team-picker-category"
                />
                <FieldContent>
                  <FieldLabel htmlFor={id} className="wrap-anywhere">
                    {row.category.name}
                  </FieldLabel>
                  <FieldDescription id={`${id}-summary`} data-testid="team-picker-summary">
                    {row.state === 'none'
                      ? t('notPerformed')
                      : row.state === 'whole'
                        ? t('whole', { count: row.services.length })
                        : t('some', { chosen: row.chosen.length, count: row.services.length })}
                  </FieldDescription>
                </FieldContent>
              </Field>
              {ticked && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={disabled}
                  aria-expanded={opened}
                  aria-label={
                    opened
                      ? t('hideLabel', { category: row.category.name })
                      : t('chooseLabel', { category: row.category.name })
                  }
                  onClick={() =>
                    setOpen((current) =>
                      opened ? current.filter((each) => each !== row.category.id) : [...current, row.category.id],
                    )
                  }
                  data-testid="team-picker-choose"
                >
                  {opened ? t('hide') : t('choose')}
                </Button>
              )}
            </div>
            {opened && (
              <div
                className="ml-3 flex flex-wrap gap-2"
                role="group"
                aria-label={t('servicesLabel', { category: row.category.name })}
                data-testid="team-picker-services"
              >
                {row.services.map((service) => (
                  <Toggle
                    key={service.id}
                    variant="chip"
                    disabled={disabled}
                    pressed={row.state === 'whole' || row.chosen.includes(service.id)}
                    onPressedChange={() => onChange(toggleService(values, row, service.id))}
                    className="h-auto whitespace-normal wrap-anywhere"
                    data-testid="team-picker-service"
                    data-id={service.id}
                  >
                    {service.name}
                  </Toggle>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
