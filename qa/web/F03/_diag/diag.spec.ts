import { test, expect } from '@playwright/test';
import { contractValidator, exact, plusExact, durableRowCounts, TIME_CLEANED_TABLES } from '../../../shared/operator';
import { alpha } from '../../../shared/tenants';
test('helper failure proof', async () => {
  const v = contractValidator('SalonDetail');
  const good = { id: '00000000-0000-4000-8000-000000000001', name: 'n', slug: 'abc', hostname: 'abc.localhost', status: 'active', country: null, currency: null,
    timeZone: 'Asia/Colombo', defaultLanguage: null, contactEmail: null, contactPhone: null, address: null, plan: null, ownerEmail: null,
    createdAt: '2026-10-19T00:00:00Z', updatedAt: '2026-10-19T00:00:00Z', version: 0, closedAt: null, deleteAt: null, deletedAt: null };
  const out: Record<string, boolean> = { nullPlan: v(good) as boolean, planObject: v({ ...good, plan: { id: good.id, key: 'k', name: 'n' } }) as boolean,
    planString: v({ ...good, plan: 'x' }) as boolean, planMissingKey: v({ ...good, plan: { id: good.id, name: 'n' } }) as boolean,
    missingSlug: v((({ slug, ...r }) => r)(good)) as boolean, versionString: v({ ...good, version: '1' }) as boolean };
  const n = contractValidator('SubscriptionState');
  const sub = { state: 'active', trialEndsAt: '2026-10-19T00:00:00Z', readOnlyAt: null, pastDueSince: null };
  Object.assign(out, { noticeNull: n({ ...sub, notice: null }) as boolean, noticeKnown: n({ ...sub, notice: 'past_due' }) as boolean, noticeUnknown: n({ ...sub, notice: 'bogus' }) as boolean });
  console.log(JSON.stringify(out));
  console.log(exact('2026-11-16T08:15:30.254315Z'), exact('2026-11-16T08:15:30Z'), plusExact('2026-11-16T08:15:30.254315Z', 30 * 86_400_000), exact('2026-11-16T08:15:30.254315Z') === exact('2026-11-16T08:15:30.254Z'));
  console.log(Object.keys(durableRowCounts(alpha.id)).filter(t => TIME_CLEANED_TABLES.includes(t)).length, Object.keys(durableRowCounts(alpha.id)).length);
});
