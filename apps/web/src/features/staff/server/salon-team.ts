import 'server-only';

import { createApiClient, type components } from '@salon/api-contracts/staff';

import { createServerApiClient } from '@/lib/api-core/client';

type Call = { headers: Record<string, string | undefined>; signal: AbortSignal };
type NewMember = components['schemas']['NewTeamMember'];

const record = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
/** A value as the browser sent it; anything else is passed on as it came, for the API to refuse by name. */
const as = <T>(value: unknown) => value as T;

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
/** An id for a path, checked for its form, so nothing else reaches the path; the API decides the rest. */
const idOf = (value: unknown) => (typeof value === 'string' && UUID.test(value) ? value : '0');

/** A person's fields, rebuilt from the contract's own names; nothing else of the browser's goes on. */
function memberOf(body: Record<string, unknown>): NewMember {
  const services = record(body.services);
  return {
    name: as<string>(body.name),
    title: as<string | null>(body.title ?? null),
    highlights: as<string | null>(body.highlights ?? null),
    bio: as<string | null>(body.bio ?? null),
    shownOnline: as<boolean>(body.shownOnline),
    services: { categoryIds: as<string[]>(services.categoryIds), serviceIds: as<string[]>(services.serviceIds) },
    signInAccountId: as<string | null>(body.signInAccountId ?? null),
  };
}

/**
 * The team paths the workspace BFF reaches (F06-S01). Every request is rebuilt from the contract's own fields; the BFF
 * checks the session and the permission before any of them. Null when the API is not configured.
 */
export function salonTeamFor(hostname: string) {
  const client = createServerApiClient(createApiClient, { hostname });
  if (!client) return null;
  return {
    members: (call: Call) => client.GET('/api/v1/team/members', call),
    signInAccounts: (call: Call) => client.GET('/api/v1/team/sign-in-accounts', call),
    change: (call: Call, action: string, raw: unknown) => {
      const body = record(raw);
      const path = { path: { id: idOf(body.id) } };
      switch (action) {
        case 'team-member-add':
          return client.POST('/api/v1/team/members', { ...call, body: memberOf(body) });
        case 'team-member-save':
          return client.PUT('/api/v1/team/members/{id}', {
            ...call,
            params: path,
            body: { ...memberOf(body), shownOnline: as<boolean>(body.shownOnline), version: as<number>(body.version) },
          });
        case 'team-member-remove':
          return client.DELETE('/api/v1/team/members/{id}', {
            ...call,
            params: { ...path, query: { version: as<number>(body.version) } },
          });
        default:
          return client.DELETE('/api/v1/team/members/{id}/photo', { ...call, params: path });
      }
    },
    uploadPhoto: (call: Call, memberId: string, file: Blob) => {
      // The form is built here from the one field the contract names; nothing else of the browser's goes on.
      const form = new FormData();
      form.append('file', file, 'upload');
      return client.PUT('/api/v1/team/members/{id}/photo', {
        ...call,
        params: { path: { id: idOf(memberId) } },
        body: form as unknown as { file: string },
        bodySerializer: (body) => body as unknown as FormData,
      });
    },
  };
}
