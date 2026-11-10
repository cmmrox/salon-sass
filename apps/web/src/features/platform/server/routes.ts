import 'server-only';
import { platformPages } from '../pages';
import { moduleRoutes } from '@/lib/api-core/routes';
import { salonAccountFor } from './salon-account';

/** A page of a salon account's list, as the BFF asks it: ten a page. */
const pageOf = (query: URLSearchParams) => ({ page: Number(query.get('page') ?? 0), size: 10 });

/**
 * The platform module's workspace pages and BFF actions on a salon's account (F03): Billing, read with `invoice.read`
 * (its plan and limits with `entitlement.read`), and the Owner's Support access, read and revoked with
 * `support.manage`. A support view never reads or ends the salon's support sessions (F03-S04).
 */
export const platformRoutes = moduleRoutes('platform', {
  pages: platformPages,
  reads: {
    entitlements: {
      permission: 'entitlement.read',
      read: (hostname, call) => salonAccountFor(hostname)?.entitlements(call) ?? null,
    },
    invoices: {
      permission: 'invoice.read',
      read: (hostname, call, query) => salonAccountFor(hostname)?.invoices(call, pageOf(query)) ?? null,
    },
    'support-sessions': {
      permission: 'support.manage',
      supportView: false,
      read: (hostname, call, query) => salonAccountFor(hostname)?.supportSessions(call, pageOf(query)) ?? null,
    },
  },
  changes: {
    'support-revoke': {
      permission: 'support.manage',
      change: (hostname, call, body) =>
        salonAccountFor(hostname)?.revokeSupportSession(call, typeof body.id === 'string' ? body.id : '') ?? null,
    },
  },
});
