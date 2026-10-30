'use client';
import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Download } from 'lucide-react';
import { Alert, AlertTitle } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Card, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Spinner } from '@/components/ui/spinner';
import { formatBytes, formatDate, formatInstant, viewerTimeZone } from '@salon/format';

type Info = { readyAt: string; deleteAt: string; sizeBytes: number; sha256: string };
type State =
  | { kind: 'loading' }
  | { kind: 'no-link' }
  | { kind: 'unknown' }
  | { kind: 'unavailable' }
  | { kind: 'ready'; info: Info };

const LOCALE = 'en-LK';

/** Reads the token from the fragment once and takes it out of the address and the history entry. */
function takeToken(): string | null {
  const token = window.location.hash.replace(/^#/, '');
  if (window.location.hash) window.history.replaceState(null, '', window.location.pathname);
  return token || null;
}

async function call(action: 'export-info' | 'export-download', token: string): Promise<Response> {
  return fetch(`/platform-web/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }), cache: 'no-store' });
}

/**
 * The Owner's download page: what the archive is, when it was prepared, its size and checksum and the
 * date it is deleted, and Download. An unknown, altered or expired link says so without revealing
 * anything about a salon.
 */
export function ExportDownload() {
  const t = useTranslations('exports');
  const [token, setToken] = useState<string | null>(null);
  const [state, setState] = useState<State>({ kind: 'loading' });
  const [busy, setBusy] = useState(false);
  const [downloaded, setDownloaded] = useState(false);
  const [failed, setFailed] = useState<'unknown' | 'unavailable' | null>(null);

  async function load(value: string) {
    setState({ kind: 'loading' });
    try {
      const response = await call('export-info', value);
      if (response.status === 404) { setState({ kind: 'unknown' }); return; }
      if (!response.ok) { setState({ kind: 'unavailable' }); return; }
      setState({ kind: 'ready', info: (await response.json()) as Info });
    } catch { setState({ kind: 'unavailable' }); }
  }

  useEffect(() => {
    let active = true;
    // Read now, and again when another link is opened in the same tab (only the fragment changes).
    const read = () => {
      const value = takeToken();
      Promise.resolve().then(() => {
        if (!active) return;
        setToken(value); setDownloaded(false); setFailed(null);
        if (value) void load(value); else setState({ kind: 'no-link' });
      });
    };
    read();
    window.addEventListener('hashchange', read);
    return () => { active = false; window.removeEventListener('hashchange', read); };
  }, []);

  async function download() {
    if (!token) return;
    setBusy(true); setDownloaded(false); setFailed(null);
    try {
      const response = await call('export-download', token);
      if (!response.ok) { setFailed(response.status === 404 ? 'unknown' : 'unavailable'); return; }
      const blob = await response.blob();
      const name = /filename="?([A-Za-z0-9._-]+)"?/.exec(response.headers.get('content-disposition') ?? '')?.[1] ?? 'salon-export.zip';
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url; link.download = name; document.body.append(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      setDownloaded(true);
    } catch { setFailed('unavailable'); }
    finally { setBusy(false); }
  }

  const zone = viewerTimeZone();
  return (
    <Card asChild className="gap-5 p-4 rail:p-6">
      <section aria-labelledby="exports-heading" data-testid="exports-card" aria-busy={state.kind === 'loading' || undefined}>
        <CardTitle asChild className="text-workspace-title leading-heading"><h1 id="exports-heading" data-testid="exports-heading">{t('heading')}</h1></CardTitle>
        <p className="m-0">{t('intro')}</p>
        {state.kind === 'loading' && (
          <div className="flex flex-col gap-3" role="status" data-testid="exports-loading"><span className="sr-only">{t('loading')}</span>{[0, 1, 2].map((row) => <Skeleton key={row} className="h-field" aria-hidden="true" />)}</div>
        )}
        {state.kind === 'no-link' && <Alert variant="notice" role="alert" data-testid="exports-no-link"><p className="m-0">{t('noLink')}</p></Alert>}
        {(state.kind === 'unknown' || failed === 'unknown') && (
          <Alert variant="notice" role="alert" data-testid="exports-unknown">
            <AlertTitle>{t('unknown')}</AlertTitle>
            <p className="m-0">{t('unknownHelp')}</p>
          </Alert>
        )}
        {(state.kind === 'unavailable' || failed === 'unavailable') && (
          <Alert variant="notice" role="alert" data-testid="exports-unavailable">
            <p className="m-0">{t('unavailable')}</p>
            {state.kind === 'unavailable' && token && <Button variant="outline" size="sm" className="mt-2" data-testid="exports-retry" onClick={() => void load(token)}>{t('retry')}</Button>}
          </Alert>
        )}
        {state.kind === 'ready' && (
          <>
            <Alert variant="caution" role="note" data-testid="exports-delete-notice"><p className="m-0">{t('deleteNotice', { date: formatDate(state.info.deleteAt, zone, LOCALE) })}</p></Alert>
            <dl className="m-0 grid gap-x-6 gap-y-3 rail:grid-cols-2">
              <div><dt className="text-small">{t('readyAt')}</dt><dd className="m-0" data-testid="exports-ready-at">{formatInstant(state.info.readyAt, zone, LOCALE)}</dd></div>
              <div><dt className="text-small">{t('deleteAt')}</dt><dd className="m-0" data-testid="exports-delete-at">{formatDate(state.info.deleteAt, zone, LOCALE)}</dd></div>
              <div><dt className="text-small">{t('size')}</dt><dd className="m-0" data-testid="exports-size">{formatBytes(state.info.sizeBytes, LOCALE)}</dd></div>
              <div className="rail:col-span-2"><dt className="text-small">{t('checksum')}</dt><dd className="m-0 font-mono text-small wrap-anywhere" data-testid="exports-sha256">{state.info.sha256}</dd></div>
            </dl>
            {downloaded && <Alert variant="success" role="status" data-testid="exports-downloaded">{t('downloaded')}</Alert>}
            <div className="flex flex-wrap gap-2">
              <Button data-testid="exports-download" disabled={busy} aria-busy={busy || undefined} onClick={() => void download()}>{busy ? <><Spinner />{t('downloading')}</> : <><Download aria-hidden="true" />{t('download')}</>}</Button>
            </div>
          </>
        )}
      </section>
    </Card>
  );
}
