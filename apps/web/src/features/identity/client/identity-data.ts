'use client';
import { moduleData } from '@/lib/api-core/data-layer';
import { workspaceSession, type WorkspaceKind } from '@/lib/api-core/request';
import type { Principal } from '../types';

/** The identity module's BFF reads after first render. */
type IdentityReads = {
  /** Who is signed in, as the API confirms it: `GET /api/v1/identity/me`. */
  principal: { answer: Principal };
};

const identity = moduleData<IdentityReads, Record<never, never>>('identity');

/** The signed-in principal, read once the screen opens. */
export const usePrincipal = (kind: WorkspaceKind) => identity.useRead(workspaceSession(kind), 'principal');
