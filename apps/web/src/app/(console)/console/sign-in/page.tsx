import { headers } from 'next/headers';
import { notFound } from 'next/navigation';
import { ConsoleShell } from '@/surfaces/console/ConsoleShell';
import { ConsoleSignIn } from '@/surfaces/console/ConsoleSignIn';
import { consoleRequestHost } from '@/lib/tenancy/console-host';

/** Operator sign-in and MFA, on the console host only. */
export default async function Page() {
  if (!consoleRequestHost((await headers()).get('host'))) notFound();
  return <ConsoleShell signedIn={false}><ConsoleSignIn /></ConsoleShell>;
}
