'use client';
import { useRouter } from 'next/navigation';
import { useEffect } from 'react';
import { ensurePrincipal, IdentityFailure, leaveEndedSession, type Kind } from '@/features/identity';

/**
 * A page rendered from an expired access credential (docs/engineering/web.md, "Branding": the server never renews)
 * has no principal, so its navigation, permissions and scope are not yet the caller's. The frame renews in the browser,
 * under the session's cross-tab lock, and renders the page again with them (BUG-F05-002, BUG-F06-001); a session that
 * cannot be renewed goes to sign-in. Renders nothing.
 */
export function SessionRenewal({ kind }: { kind: Kind }) {
  const router = useRouter();
  useEffect(() => {
    let active = true;
    ensurePrincipal(kind)
      .then(() => active && router.refresh())
      .catch(
        (error) =>
          active &&
          error instanceof IdentityFailure &&
          error.code === 'invalid_session' &&
          leaveEndedSession(kind, router),
      );
    return () => {
      active = false;
    };
  }, [kind, router]);
  return null;
}
