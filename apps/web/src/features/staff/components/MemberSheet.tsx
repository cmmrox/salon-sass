'use client';
import { useTranslations } from 'next-intl';
import type { ReactNode } from 'react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Field, FieldContent, FieldDescription, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Sheet, SheetClose, SheetContent, SheetTitle } from '@/components/ui/sheet';
import { Spinner } from '@/components/ui/spinner';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { BIO_MAX, lengthOf, type MemberProblem, type MemberValues } from '../client/team-form';
import type { TeamCategory, TeamService, TeamSignInAccount } from '../types';
import { MemberPhotoField, type MemberPhotoUpload } from './MemberPhotoField';
import { ROW_CLASSES, ROW_ON_CLASSES, ServicePicker } from './ServicePicker';

/**
 * The prototype's sheet (CR-017, web.md "Styling" rule 11): a bottom sheet on phones capped in height, and from 900px a
 * full-height right drawer.
 */
export const SHEET_CLASSES = [
  'mx-auto max-h-(--size-sheet-max-height) w-full max-w-(--size-customer) gap-4 overflow-y-auto rounded-(--radius-sheet) p-6 pb-[calc(var(--spacing)*6+env(safe-area-inset-bottom,0px))] outline-none',
  'before:mx-auto before:mb-2 before:block before:h-1 before:w-(--size-sheet-handle) before:rounded-action before:bg-border before:content-[""]',
  'rail:top-0 rail:left-auto rail:m-0 rail:h-full rail:max-h-none rail:w-(--size-drawer) rail:max-w-full rail:rounded-none rail:px-8 rail:py-6 rail:before:hidden',
];

const PROBLEMS_ID = 'team-problems';
/** A Select item cannot have an empty value; this one is no account. */
const NO_SIGN_IN = 'none';

type Props = {
  open: boolean;
  memberId: string | null;
  values: MemberValues;
  problems: MemberProblem[];
  saving: boolean;
  photoId: string | null;
  photoUpload: MemberPhotoUpload;
  categories: TeamCategory[];
  services: TeamService[];
  /** The accounts offered: those no other person is linked to. */
  accounts: TeamSignInAccount[];
  onChange: (values: MemberValues) => void;
  onSave: () => void;
  onClose: () => void;
  /** Where focus goes when the sheet has closed: what opened it, as it has no trigger of its own. */
  onClosed: () => void;
  onRemove: () => void;
  onPhoto: (file: File) => void;
  onRemovePhoto: () => void;
  /** What the page says while the sheet is open, drawn here: the page behind its overlay cannot be seen. */
  notices?: ReactNode;
};

/**
 * The profile sheet (F06-S01, the prototype's `stPaint`): photo, Name, Title, Highlights, Short bio, Services they
 * perform, Online booking and Sign-in. The broken rules are listed above the fields as the person types, and **Add to
 * team** or **Save changes** stays disabled while they last. **Remove from team** for an existing person, and the
 * prototype's closing line about opening hours (F06-S02).
 */
export function MemberSheet(props: Props) {
  const { open, memberId, values, problems, saving, onChange } = props;
  const t = useTranslations('team.sheet');
  const isNew = memberId === null;
  const working = saving || props.photoUpload.state === 'uploading';
  const invalid = (field: MemberProblem) => problems.includes(field);
  const aria = (field: MemberProblem, help?: string) => ({
    'aria-invalid': invalid(field) || undefined,
    'aria-describedby': [help, invalid(field) ? PROBLEMS_ID : undefined].filter(Boolean).join(' ') || undefined,
  });
  return (
    <Sheet open={open} onOpenChange={(next) => !next && props.onClose()}>
      <SheetContent
        side="bottom"
        className={cn(SHEET_CLASSES)}
        data-testid="team-sheet"
        aria-describedby={undefined}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          props.onClosed();
        }}
      >
        <div className="flex items-center justify-between gap-3">
          <SheetTitle className="rail:text-workspace-title">{isNew ? t('new') : t('edit')}</SheetTitle>
          <SheetClose asChild>
            <Button variant="outline" size="sm" data-testid="team-sheet-close">
              {t('close')}
            </Button>
          </SheetClose>
        </div>
        {props.notices}
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            if (problems.length === 0 && !working) props.onSave();
          }}
        >
          <MemberPhotoField
            memberId={memberId ?? ''}
            name={values.name}
            photoId={props.photoId}
            isNew={isNew}
            upload={props.photoUpload}
            disabled={saving}
            onChoose={props.onPhoto}
            onRemove={props.onRemovePhoto}
          />
          {problems.length > 0 && (
            <Alert variant="destructive" role="alert" id={PROBLEMS_ID} data-testid="team-sheet-problems">
              <AlertTitle className="sr-only">{t('problemsTitle')}</AlertTitle>
              <AlertDescription>
                <ul className="flex flex-col gap-1">
                  {problems.map((problem) => (
                    <li key={problem} data-testid="team-sheet-problem" data-problem={problem}>
                      {t(`problems.${problem}`)}
                    </li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}
          <Field>
            <FieldLabel htmlFor="team-name">{t('name')}</FieldLabel>
            <Input
              id="team-name"
              value={values.name}
              onChange={(event) => onChange({ ...values, name: event.target.value })}
              {...aria('name')}
              data-testid="team-name"
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="team-title">{t('title')}</FieldLabel>
            <Input
              id="team-title"
              placeholder={t('titlePlaceholder')}
              value={values.title}
              onChange={(event) => onChange({ ...values, title: event.target.value })}
              {...aria('title')}
              data-testid="team-title"
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="team-highlights" className="flex-row flex-wrap gap-x-1 gap-y-0">
              {t('highlights')}
              {/* Ink, not muted: the sheet is the warm canvas, where muted small text is under 4.5:1. */}
              <span className="font-normal text-foreground">{t('highlightsHelp')}</span>
            </FieldLabel>
            <Input
              id="team-highlights"
              placeholder={t('highlightsPlaceholder')}
              value={values.highlights}
              onChange={(event) => onChange({ ...values, highlights: event.target.value })}
              {...aria('highlights')}
              data-testid="team-highlights"
            />
          </Field>
          <Field>
            <FieldLabel htmlFor="team-bio">{t('bio')}</FieldLabel>
            <Textarea
              id="team-bio"
              rows={3}
              value={values.bio}
              onChange={(event) => onChange({ ...values, bio: event.target.value })}
              {...aria('bio', 'team-bio-count')}
              data-testid="team-bio"
            />
            <FieldDescription id="team-bio-count" className="text-right" data-testid="team-bio-count">
              {t('bioCount', { count: lengthOf(values.bio), max: BIO_MAX })}
            </FieldDescription>
          </Field>
          <section className="flex flex-col gap-2" aria-labelledby="team-services-heading">
            <h2 id="team-services-heading" className="font-semibold">
              {t('services')}
            </h2>
            <p className="text-small text-foreground">{t('servicesHelp')}</p>
            <ServicePicker
              values={values}
              categories={props.categories}
              services={props.services}
              disabled={saving}
              onChange={onChange}
            />
          </section>
          <section className="flex flex-col gap-2" aria-labelledby="team-online-heading">
            <h2 id="team-online-heading" className="font-semibold">
              {t('online')}
            </h2>
            <Field orientation="horizontal" className={cn(ROW_CLASSES, values.shownOnline && ROW_ON_CLASSES)}>
              <Switch
                id="team-online"
                checked={values.shownOnline}
                disabled={saving}
                aria-describedby="team-online-help"
                onCheckedChange={(shownOnline) => onChange({ ...values, shownOnline })}
                data-testid="team-online"
              />
              <FieldContent>
                <FieldLabel htmlFor="team-online">{t('onlineLabel')}</FieldLabel>
                <FieldDescription id="team-online-help">{t('onlineHelp')}</FieldDescription>
              </FieldContent>
            </Field>
          </section>
          <section className="flex flex-col gap-2" aria-labelledby="team-sign-in-heading">
            <h2 id="team-sign-in-heading" className="font-semibold">
              {t('signIn')}
            </h2>
            <Field>
              <FieldLabel htmlFor="team-sign-in" className="sr-only">
                {t('signInLabel')}
              </FieldLabel>
              <Select
                value={values.signInAccountId ?? NO_SIGN_IN}
                disabled={saving}
                onValueChange={(next) => onChange({ ...values, signInAccountId: next === NO_SIGN_IN ? null : next })}
              >
                <SelectTrigger
                  id="team-sign-in"
                  aria-describedby="team-sign-in-help"
                  {...(invalid('signInAccountId') ? { 'aria-invalid': true } : {})}
                  data-testid="team-sign-in"
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_SIGN_IN} data-testid="team-sign-in-none">
                    {t('noSignIn')}
                  </SelectItem>
                  {props.accounts.map((account) => (
                    <SelectItem key={account.id} value={account.id} data-testid="team-sign-in-account">
                      {account.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FieldDescription id="team-sign-in-help">{t('signInHelp')}</FieldDescription>
            </Field>
          </section>
          <Button type="submit" disabled={problems.length > 0 || working} data-testid="team-sheet-save">
            {saving && <Spinner />}
            {isNew ? t('addToTeam') : t('save')}
          </Button>
          {!isNew && (
            <Button
              type="button"
              variant="destructive"
              disabled={working}
              onClick={props.onRemove}
              data-testid="team-sheet-remove"
            >
              {t('remove')}
            </Button>
          )}
          {/* Ink, not muted: the sheet is the warm canvas, where muted small text is under 4.5:1. */}
          <p className="text-small text-foreground" data-testid="team-sheet-hours-note">
            {t('startsOnSalonHours')}
          </p>
        </form>
      </SheetContent>
    </Sheet>
  );
}
