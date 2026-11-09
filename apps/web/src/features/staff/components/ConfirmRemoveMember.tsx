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

type Props = { open: boolean; name: string; onCancel: () => void; onConfirm: () => void };

/** The prototype's confirmation before a person leaves the team (`stDel`, F06-S01 AC-7). */
export function ConfirmRemoveMember({ open, name, onCancel, onConfirm }: Props) {
  const t = useTranslations('team.removal');
  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && onCancel()}>
      <AlertDialogContent data-testid="team-remove-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle className="wrap-anywhere">{t('title', { name })}</AlertDialogTitle>
          <AlertDialogDescription>{t('body')}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel data-testid="team-remove-cancel">{t('cancel')}</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onConfirm} data-testid="team-remove-confirm">
            {t('confirm')}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
