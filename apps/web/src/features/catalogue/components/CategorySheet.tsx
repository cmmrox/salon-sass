'use client';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field, FieldDescription, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Sheet, SheetClose, SheetContent, SheetTitle } from '@/components/ui/sheet';
import { Spinner } from '@/components/ui/spinner';
import { cn } from '@/lib/utils';
import type { CategoryProblem } from '../client/category-form';
import { SHEET_CLASSES } from './sheet-classes';

type Props = {
  open: boolean;
  renaming: boolean;
  name: string;
  /** The problem shown after an attempt to save. */
  problem: CategoryProblem | null;
  saving: boolean;
  onName: (name: string) => void;
  onSave: () => void;
  onClose: () => void;
  /** Where focus goes when the sheet has closed: what opened it, as it has no trigger of its own. */
  onClosed: () => void;
  /**
   * What the page says while the sheet is open (a conflict with its Reload, a refused rule, an unreachable API),
   * drawn inside the sheet: the page behind its overlay can be neither seen nor used.
   */
  notices?: ReactNode;
};

/**
 * The prototype's category sheet (`ceOpen`, F05-S01 AC-2): "New category" or "Rename category", the name of 2 to 30
 * characters, and Add category or Save. A broken rule is said above the field when the person saves, and the page's
 * notices are shown in the sheet while it is open.
 */
export function CategorySheet(props: Props) {
  const { open, renaming, name, problem, saving, onName, onSave, onClose, onClosed, notices } = props;
  const t = useTranslations('services.categorySheet');
  return (
    <Sheet open={open} onOpenChange={(next) => !next && onClose()}>
      <SheetContent
        side="bottom"
        className={cn(SHEET_CLASSES)}
        data-testid="category-sheet"
        aria-describedby={undefined}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          onClosed();
        }}
      >
        <div className="flex items-center justify-between gap-3">
          <SheetTitle className="rail:text-workspace-title">{renaming ? t('rename') : t('new')}</SheetTitle>
          <SheetClose asChild>
            <Button variant="outline" size="sm" data-testid="category-sheet-close">
              {t('close')}
            </Button>
          </SheetClose>
        </div>
        {notices}
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            onSave();
          }}
        >
          {problem && (
            <Alert variant="destructive" role="alert" id="category-name-error" data-testid="category-sheet-error">
              {t(problem)}
            </Alert>
          )}
          <Field data-invalid={problem !== null || undefined}>
            <FieldLabel htmlFor="category-name">{t('name')}</FieldLabel>
            <Input
              id="category-name"
              value={name}
              maxLength={30}
              placeholder={t('placeholder')}
              aria-invalid={problem !== null || undefined}
              aria-describedby={problem ? 'category-name-help category-name-error' : 'category-name-help'}
              onChange={(event) => onName(event.target.value)}
              data-testid="category-sheet-name"
            />
            <FieldDescription id="category-name-help">{t('help')}</FieldDescription>
          </Field>
          <Button type="submit" className="w-full" disabled={saving} data-testid="category-sheet-save">
            {saving && <Spinner />}
            {saving ? t('saving') : renaming ? t('save') : t('add')}
          </Button>
        </form>
      </SheetContent>
    </Sheet>
  );
}
