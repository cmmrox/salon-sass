import { cookies, headers } from 'next/headers';
import { notFound, redirect } from 'next/navigation';
import { ConsoleDenied, PlanDetail, PlanDirectory, InvoiceList } from '@/features/platform';
import { operatorEntry, operatorCookie } from '@/features/platform/server';
import { consoleRequestHost } from '@/lib/tenancy/console-host';
import { platformDomainOf } from '@/lib/tenancy/hostname';
import { ConsolePending, ConsoleUnavailable } from './ConsolePending';
import { ConsoleShell, type ConsoleView } from './ConsoleShell';
import { SalonDetail, SalonDirectory } from './SalonScreens';
import { SupportSessionList } from './SupportScreens';

/**
 * A protected console page, decided on the server before anything is sent: without a usable operator
 * session the response is a redirect to sign-in; an operator without the section's permission sees the
 * denial and Sign out, never management links; otherwise the console frame with the section.
 */
export async function ConsolePage({ view, id, section }: { view: Exclude<ConsoleView, 'none'>; id?: string; section?: 'invoices' }) {
  const host = consoleRequestHost((await headers()).get('host'));
  if (!host) notFound();
  const store = await cookies();
  const entry = await operatorEntry({ access: store.get(operatorCookie('access'))?.value, refresh: store.get(operatorCookie('refresh'))?.value, expiry: store.get(operatorCookie('expires'))?.value }, host.hostname);
  if (entry.kind === 'signed-out') redirect('/console/sign-in');
  if (entry.kind === 'pending') return <ConsoleShell><ConsolePending /></ConsoleShell>;
  if (entry.kind === 'unavailable') return <ConsoleShell><ConsoleUnavailable /></ConsoleShell>;
  const permissions = entry.principal.permissions;
  const required = section === 'invoices' ? ['operator.billing.read'] : view === 'salons' ? ['operator.salon.read'] : view === 'support' ? ['operator.support.start', 'operator.support.read'] : ['operator.plan.read'];
  if (!required.some((permission) => permissions.includes(permission))) return <ConsoleShell permissions={permissions}><ConsoleDenied /></ConsoleShell>;
  const platformDomain = platformDomainOf(process.env.SALON_PLATFORM_DOMAIN);
  return (
    <ConsoleShell permissions={permissions} view={view}>
      {view === 'support' ? <SupportSessionList admin={permissions.includes('operator.support.read')} />
        : view === 'salons' ? (id ? (section === 'invoices' ? <InvoiceList id={id} /> : <SalonDetail id={id} permissions={permissions} />) : <SalonDirectory platformDomain={platformDomain} permissions={permissions} />) : id ? <PlanDetail id={id} /> : <PlanDirectory />}
    </ConsoleShell>
  );
}
