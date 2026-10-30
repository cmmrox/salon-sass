'use client';
import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Spinner } from '@/components/ui/spinner';
import { operatorRequest, OperatorFailure, withOperatorLock } from '@/features/platform';

/** Signs the operator out under the cross-tab lock; the server revokes the session and clears the cookies. */
export function ConsoleSignOut() {
  const t = useTranslations('console');
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  async function signOut() {
    setBusy(true);
    try { await withOperatorLock(() => operatorRequest('sign-out', {})); }
    catch (failure) {
      // Even when the server could not be reached, this browser forgets its credentials.
      if (!(failure instanceof OperatorFailure) || failure.code !== 'browser_unsupported') await operatorRequest('clear', {}).catch(() => undefined);
    }
    finally { setBusy(false); router.replace('/console/sign-in'); router.refresh(); }
  }
  return <Button variant="outline" size="sm" data-testid="console-sign-out" disabled={busy} aria-busy={busy || undefined} onClick={() => void signOut()}>{busy && <Spinner />}{t('signOut')}</Button>;
}
