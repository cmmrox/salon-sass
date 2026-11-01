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

/**
 * The confirmation before leaving a settings tab with unsaved edits (F04-S01 AC-11). Its test ids start with
 * `area`: `profile` on the Profile and Branding tabs (as since F04-S01), `rules` on Booking rules (F04-S04).
 */
export function LeaveDialog({
  open,
  onStay,
  onLeave,
  area = 'profile',
}: {
  open: boolean;
  onStay: () => void;
  onLeave: () => void;
  area?: 'profile' | 'rules';
}) {
  const t = useTranslations('settings.leave');
  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && onStay()}>
      <AlertDialogContent data-testid={`${area}-leave-dialog`}>
        <AlertDialogHeader>
          <AlertDialogTitle>{t('title')}</AlertDialogTitle>
          <AlertDialogDescription>{t('body')}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel data-testid={`${area}-leave-stay`}>{t('stay')}</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onLeave} data-testid={`${area}-leave-confirm`}>
            {t('leave')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
