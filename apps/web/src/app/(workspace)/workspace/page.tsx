import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { hasCredentials } from '@/lib/identity/entry';
import { cookieName } from '@/lib/identity/server';
/** Entry decided on the server: sign-in without credentials, otherwise the account (which checks them). */
export default async function Page() {
  const store = await cookies();
  redirect(hasCredentials({ access: store.get(cookieName('staff', 'access'))?.value, refresh: store.get(cookieName('staff', 'refresh'))?.value }) ? '/workspace/account' : '/workspace/sign-in');
}
