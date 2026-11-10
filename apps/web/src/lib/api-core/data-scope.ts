import 'server-only';
import { createHash } from 'node:crypto';

/**
 * The data scope of a workspace or console frame (decision 121): the salon's hostname, the session's kind and the session itself,
 * so that the browser's query cache starts empty for every other salon, person or session. The session's id stays the
 * same across renewals, so renewing keeps what is on screen; it is hashed, since it lives in an HttpOnly cookie that
 * scripts are not meant to read. Nothing is authorised by it.
 */
export function dataScope(hostname: string, kind: string, session: string | undefined): string {
  const digest = createHash('sha256')
    .update([hostname, kind, session ?? ''].join('\n'))
    .digest('base64url');
  return `${kind}:${digest.slice(0, 22)}`;
}
