import { cookies, headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { createServerApiClient } from '@/lib/api/client';
import { operatorEntry } from '@/lib/operator/entry';
import { consoleRequestHost } from '@/lib/operator/host';
import { operatorCookie } from '@/lib/operator/server';
import { platformDomainOf } from '@/lib/tenancy/hostname';
import { ConsoleDenied } from './ConsoleControls';
import { ConsolePending } from './ConsolePending';
import { ConsoleShell, type ConsoleView } from './ConsoleShell';
import { PlanDetail, PlanDirectory } from './PlanScreens';
import { SalonDetail, SalonDirectory } from './SalonScreens';
import { InvoiceList } from './BillingScreens';

/**
 * A protected console page, decided on the server before anything is sent: without a usable operator
 * session the response is a redirect to sign-in; an operator without the section's permission sees the
 * denial and Sign out, never management links; otherwise the console frame with the section.
 */
export async function ConsolePage({ view, id, section }: { view: Exclude<ConsoleView, 'none'>; id?: string; section?: 'invoices' }) {
  const host = consoleRequestHost((await headers()).get('host'));
  if (!host) notFound();
  const store = await cookies();
  const entry = await operatorEntry({ access: store.get(operatorCookie('access'))?.value, refresh: store.get(operatorCookie('refresh'))?.value, expiry: store.get(operatorCookie('expires'))?.value }, createServerApiClient({ hostname: host.hostname }));
  if (entry.kind === 'signed-out') redirect('/console/sign-in');
  if (entry.kind === 'pending') return <ConsoleShell><ConsolePending /></ConsoleShell>;
  const permissions = entry.principal.permissions;
  const required = section === 'invoices' ? 'operator.billing.read' : view === 'salons' ? 'operator.salon.read' : 'operator.plan.read';
  if (!permissions.includes(required)) return <ConsoleShell permissions={permissions}><ConsoleDenied /></ConsoleShell>;
  const platformDomain = platformDomainOf(process.env.SALON_PLATFORM_DOMAIN);
  return (
    <ConsoleShell permissions={permissions} view={view}>
      {view === 'salons' ? (id ? (section === 'invoices' ? <InvoiceList id={id} /> : <SalonDetail id={id} />) : <SalonDirectory platformDomain={platformDomain} />) : id ? <PlanDetail id={id} /> : <PlanDirectory />}
    </ConsoleShell>
  );
}
