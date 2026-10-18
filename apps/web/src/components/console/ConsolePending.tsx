'use client';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { ensurePrincipal, OperatorFailure } from '@/lib/operator/browser';
import { ConsoleFailure, ConsoleLoading } from './ConsoleControls';

/**
 * The server could not confirm the operator (access expired, or the API did not answer in time):
 * renew once under the cross-tab lock, then render the page again; a spent session goes to sign-in,
 * an outage offers a safe retry.
 */
export function ConsolePending() {
  const router = useRouter();
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const [busy, setBusy] = useState(true);
  async function attempt() {
    setBusy(true); setFailure(null);
    try { await ensurePrincipal(); router.refresh(); }
    catch (error) {
      const f = error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setBusy(false); }
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { let active = true; Promise.resolve().then(() => { if (active) void attempt(); }); return () => { active = false; }; }, []);
  return failure ? <ConsoleFailure failure={failure} busy={busy} onRetry={() => void attempt()} /> : <ConsoleLoading />;
}
