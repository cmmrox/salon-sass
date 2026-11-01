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
} from '@/components/ui/alert-dialog';

/** The confirmation before leaving the Profile tab with unsaved edits (F04-S01 AC-11). */
export function LeaveDialog({ open, onStay, onLeave }: { open: boolean; onStay: () => void; onLeave: () => void }) {
  const t = useTranslations('settings.leave');
  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && onStay()}>
      <AlertDialogContent data-testid="profile-leave-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>{t('title')}</AlertDialogTitle>
          <AlertDialogDescription>{t('body')}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel data-testid="profile-leave-stay">{t('stay')}</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onLeave} data-testid="profile-leave-confirm">
            {t('leave')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
