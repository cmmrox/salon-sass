'use client';
import { useTranslations } from 'next-intl';
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
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Spinner } from '@/components/ui/spinner';

type Props = {
  unpublished: boolean;
  busy: 'publishing' | 'discarding' | null;
  disabled: boolean;
  onPublish: () => void;
  onDiscard: () => void;
};

/**
 * The publish bar below the Profile tab (F04-S01): whether there are unpublished changes, Publish to
 * everyone (which saves unsaved edits first) and Discard changes, confirmed in an Alert Dialog. With
 * everything published, Publish is disabled.
 */
export function PublishBar({ unpublished, busy, disabled, onPublish, onDiscard }: Props) {
  const t = useTranslations('settings.publishBar');
  return (
    <Card className="flex-row flex-wrap items-center justify-between gap-3" data-testid="publish-bar">
      <div className="flex min-w-0 flex-col gap-0.5" role="status" data-testid="publish-bar-status">
        <span className="font-semibold">{unpublished ? t('unpublished') : t('published')}</span>
        <span className="text-small text-muted-foreground">
          {unpublished ? t('unpublishedHelp') : t('publishedHelp')}
        </span>
      </div>
      <div className="flex flex-wrap gap-2">
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button
              variant="outline"
              size="sm"
              disabled={disabled || !unpublished || busy !== null}
              data-testid="profile-discard"
            >
              {busy === 'discarding' && <Spinner />}
              {t('discard')}
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent data-testid="profile-discard-dialog">
            <AlertDialogHeader>
              <AlertDialogTitle>{t('discardTitle')}</AlertDialogTitle>
              <AlertDialogDescription>{t('discardBody')}</AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel data-testid="profile-discard-cancel">{t('keepEditing')}</AlertDialogCancel>
              <AlertDialogAction onClick={onDiscard} data-testid="profile-discard-confirm">
                {t('discard')}
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
        <Button
          size="sm"
          disabled={disabled || !unpublished || busy !== null}
          onClick={onPublish}
          data-testid="profile-publish"
        >
          {busy === 'publishing' && <Spinner />}
          {busy === 'publishing' ? t('publishing') : unpublished ? t('publish') : t('publishedAction')}
        </Button>
      </div>
    </Card>
  );
}
