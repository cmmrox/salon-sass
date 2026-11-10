'use client';
import { moduleData } from '@/lib/api-core/data-layer';
import { workspaceSession, type WorkspaceKind } from '@/lib/api-core/request';
import type { BrandDraft, ProfileDraft, UploadedBrandImage } from '../types';

/** The branding module's BFF reads: the salon's profile and brand drafts (F04-S01, F04-S02). */
type BrandingReads = {
  'profile-draft': { answer: ProfileDraft };
  'brand-draft': { answer: BrandDraft };
};

type Versioned = { version: unknown };

/** The branding module's BFF changes, each answering the draft as it is now (an upload, the image it stored). */
type BrandingChanges = {
  'profile-save': { body: Record<string, unknown>; answer: ProfileDraft };
  'profile-publish': { body: Versioned; answer: ProfileDraft };
  'profile-discard': { body: Versioned; answer: ProfileDraft };
  'brand-save': { body: Record<string, unknown>; answer: BrandDraft };
  'brand-reset': { body: Versioned; answer: BrandDraft };
  'brand-publish': { body: Versioned; answer: BrandDraft };
  'brand-discard': { body: Versioned; answer: BrandDraft };
  'brand-image': { body: FormData; answer: UploadedBrandImage };
};

const branding = moduleData<BrandingReads, BrandingChanges>('branding');

/** A settings tab's draft: the profile's or the brand's. */
export const useDraft = (kind: WorkspaceKind, subject: 'profile' | 'brand') =>
  branding.useRead(workspaceSession(kind), `${subject}-draft`);

/** Puts a change's answer in place of a read's. */
export const useSetBrandingAnswer = (kind: WorkspaceKind) => branding.useSetAnswer(workspaceSession(kind));

/** The branding module's changes, each made once and typed by its action. */
export const useBrandingChanges = (kind: WorkspaceKind) => branding.useChanges(workspaceSession(kind));
