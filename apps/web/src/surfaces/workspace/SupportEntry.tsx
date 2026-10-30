'use client';
import { useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Card } from '@/components/ui/card';
import { Spinner } from '@/components/ui/spinner';
import { IdentityFailure, identityRequest } from '@/features/identity';

type State = 'loading' | 'success' | 'refused' | 'unavailable' | 'ended' | 'none';
const CODE = /^[A-Za-z0-9_-]{43}$/;

/**
 * The support entry page (F03-S04): the console opens `/workspace/support#<code>`. The code is read
 * from the fragment, which never reaches a server or a log, removed from the address at once, and
 * posted once to this host's BFF, which exchanges it for the support credential (HttpOnly cookies).
 * A refused, expired or used link issues nothing and says so; `#ended` is where an ended view lands.
 */
export function SupportEntry() {
  const t = useTranslations('support');
  const [state, setState] = useState<State>('loading');
  useEffect(() => {
    let active = true;
    async function read() {
      const fragment = window.location.hash.slice(1);
      // The code leaves the address bar and the history entry before anything else happens.
      window.history.replaceState(null, '', window.location.pathname);
      if (!CODE.test(fragment)) {
        await identityRequest('support', 'clear', {}).catch(() => undefined);
        if (active) setState(fragment === 'ended' ? 'ended' : 'none');
        return;
      }
      if (active) setState('loading');
      try {
        await identityRequest('support', 'entry', { code: fragment });
        if (!active) return;
        setState('success');
        window.location.replace('/workspace/account');
      } catch (failure) {
        const code = failure instanceof IdentityFailure ? failure.code : 'service_unavailable';
        if (active) setState(code === 'invalid_code' || code === 'request_rejected' || code === 'bad_request' ? 'refused' : 'unavailable');
      }
    }
    void read();
    // A new link opened in this same tab changes only the fragment: read it too.
    const again = () => { void read(); };
    window.addEventListener('hashchange', again);
    return () => { active = false; window.removeEventListener('hashchange', again); };
  }, []);
  const message: Record<Exclude<State, 'loading' | 'success'>, { title: string; body: string; variant: 'notice' | 'default' }> = {
    refused: { title: t('refusedTitle'), body: t('refused'), variant: 'notice' },
    unavailable: { title: t('unavailableTitle'), body: t('unavailable'), variant: 'notice' },
    ended: { title: t('endedTitle'), body: t('ended'), variant: 'default' },
    none: { title: t('noneTitle'), body: t('none'), variant: 'default' },
  };
  return (
    <Card asChild className="block w-full max-w-(--size-login-card) gap-0 rounded-(--radius-login-card) border border-border bg-surface-translucent px-(--space-login-card-inline) py-(--space-login-card-block) shadow-(--shadow-login-card) max-login:px-6 max-login:py-8">
      <section data-testid="support-entry" data-state={state} aria-labelledby="support-entry-title" aria-busy={state === 'loading' || undefined}>
        <h1 id="support-entry-title" className="mb-6 text-center text-login-welcome leading-heading font-normal wrap-anywhere max-login:text-title" data-testid="support-entry-title">{t('entryTitle')}</h1>
        {(state === 'loading' || state === 'success') && (
          <p className="flex items-center justify-center gap-3 text-center" role="status" data-testid={state === 'loading' ? 'support-entry-loading' : 'support-entry-success'}>
            <Spinner />{state === 'loading' ? t('opening') : t('opened')}
          </p>
        )}
        {state !== 'loading' && state !== 'success' && (
          <Alert variant={message[state].variant} role={state === 'refused' || state === 'unavailable' ? 'alert' : 'status'} data-testid={`support-entry-${state}`}>
            <AlertTitle>{message[state].title}</AlertTitle>
            <AlertDescription>{message[state].body}</AlertDescription>
          </Alert>
        )}
      </section>
    </Card>
  );
}
