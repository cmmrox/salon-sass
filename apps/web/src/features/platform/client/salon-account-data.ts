'use client';
import type { components } from '@salon/api-contracts/platform';
import { moduleData } from '@/lib/api-core/data-layer';
import { workspaceSession, type WorkspaceKind } from '@/lib/api-core/request';
import type { Entitlements } from '../types';

/** The salon's account as the workspace reads it (F03): its plan and limits, its invoices, its support sessions. */
export type SalonAccountReads = {
  entitlements: { answer: Entitlements };
  invoices: { query: { page: number }; answer: components['schemas']['InvoicePage'] };
  'support-sessions': { query: { page: number }; answer: components['schemas']['SalonSupportSessionPage'] };
};

/** The Owner's one change to the salon's account here: ending a support session (F03-S04). */
export type SalonAccountChanges = {
  'support-revoke': { body: { id: string }; answer: { ok: true } };
};

const account = moduleData<SalonAccountReads, SalonAccountChanges>('platform');

/** The salon's plan, limits, usage and subscription state. */
export const useEntitlements = (kind: WorkspaceKind) => account.useRead(workspaceSession(kind), 'entitlements');

/** A page of the salon's invoices; the last page stays on screen while the next is read. */
export const useInvoices = (kind: WorkspaceKind, page: number) =>
  account.useRead(workspaceSession(kind), 'invoices', { page }, { keepPrevious: true });

/** A page of the salon's support sessions, active and past. */
export const useSupportSessions = (kind: WorkspaceKind, page: number) =>
  account.useRead(workspaceSession(kind), 'support-sessions', { page }, { keepPrevious: true });

/** The salon account's changes, each made once. */
export const useSalonAccountChanges = (kind: WorkspaceKind) => account.useChanges(workspaceSession(kind));
