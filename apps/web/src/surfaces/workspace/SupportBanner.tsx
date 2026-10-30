'use client';
import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Spinner } from '@/components/ui/spinner';
import { IdentityFailure, identityRequest } from '@/features/identity';
import { formatTime } from '@salon/format';

/** Where the browser goes when its support view ends: the entry page, told why. */
export const SUPPORT_ENDED = '/workspace/support#ended';

/**
 * The support view's banner (F03-S04) on every workspace page: "Support view, read only, ends at
 * HH:MM" in the salon's time zone, with End session. It cannot be dismissed, it is announced, and it
 * sits in the sticky band above the header so it is never scrolled away. At the session's expiry the
 * browser leaves the support view by itself.
 */
export function SupportBanner({ expiresAt, timeZone }: { expiresAt: string | null; timeZone: string }) {
  const t = useTranslations('support');
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!expiresAt) return;
    const wait = Date.parse(expiresAt) - Date.now();
    // The server decides; this only stops showing a view that has ended. A long wait is capped by the
    // browser's timer limit, far above a session's two hours.
    const id = setTimeout(
      () => window.location.replace(SUPPORT_ENDED),
      Math.max(0, Math.min(wait + 1000, 2 ** 31 - 1)),
    );
    return () => clearTimeout(id);
  }, [expiresAt]);
  async function end() {
    setBusy(true);
    setFailed(false);
    try {
      await identityRequest('support', 'end', {});
      window.location.replace(SUPPORT_ENDED);
    } catch (failure) {
      if (failure instanceof IdentityFailure && failure.code === 'invalid_session') {
        window.location.replace(SUPPORT_ENDED);
        return;
      }
      setFailed(true);
      setBusy(false);
    }
  }
  return (
    <Alert
      variant="caution"
      role="alert"
      className="rounded-none px-4 py-3 rail:flex-row rail:items-center rail:px-7"
      data-testid="support-banner"
      data-expires-at={expiresAt ?? undefined}
    >
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <AlertTitle data-testid="support-banner-title">{t('bannerTitle')}</AlertTitle>
        <AlertDescription data-testid="support-banner-ends">
          {expiresAt ? t('bannerEnds', { time: formatTime(expiresAt, timeZone) }) : t('bannerReadOnly')}
        </AlertDescription>
        {failed && (
          <p className="m-0" role="status" data-testid="support-banner-error">
            {t('endFailed')}
          </p>
        )}
      </div>
      <Button
        variant="outline"
        className="self-start rail:self-center"
        data-testid="support-banner-end"
        disabled={busy}
        aria-busy={busy || undefined}
        onClick={() => void end()}
      >
        {busy ? (
          <>
            <Spinner />
            {t('ending')}
          </>
        ) : (
          t('endSession')
        )}
      </Button>
    </Alert>
  );
}
