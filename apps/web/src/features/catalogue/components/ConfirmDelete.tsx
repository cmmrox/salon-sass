'use client';
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

type Props = {
  open: boolean;
  title: string;
  body: string;
  confirm: string;
  cancel: string;
  /** The test ids' stem: `service-delete`, `category-delete` or `product-delete`. */
  area: 'service-delete' | 'category-delete' | 'product-delete';
  onCancel: () => void;
  onConfirm: () => void;
};

/** The prototype's confirmation before a delete (`openConf`): its question, what it means, and the delete action. */
export function ConfirmDelete({ open, title, body, confirm, cancel, area, onCancel, onConfirm }: Props) {
  return (
    <AlertDialog open={open} onOpenChange={(next) => !next && onCancel()}>
      <AlertDialogContent data-testid={`${area}-dialog`}>
        <AlertDialogHeader>
          <AlertDialogTitle className="wrap-anywhere">{title}</AlertDialogTitle>
          <AlertDialogDescription>{body}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel data-testid={`${area}-cancel`}>{cancel}</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onConfirm} data-testid={`${area}-confirm`}>
            {confirm}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
