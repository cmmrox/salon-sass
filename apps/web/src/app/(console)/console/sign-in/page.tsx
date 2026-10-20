import { headers } from 'next/headers';
import { notFound } from 'next/navigation';
import { ConsoleShell } from '@/components/console/ConsoleShell';
import { ConsoleSignIn } from '@/components/console/ConsoleSignIn';
import { consoleRequestHost } from '@/lib/operator/host';

/** Operator sign-in and MFA, on the console host only. */
export default async function Page() {
  if (!consoleRequestHost((await headers()).get('host'))) notFound();
  return <ConsoleShell signedIn={false}><ConsoleSignIn /></ConsoleShell>;
}
