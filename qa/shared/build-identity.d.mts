export const ALLOW_MISMATCH: 'QA_ALLOW_BUILD_MISMATCH';
export function sameCommit(a: unknown, b: unknown): boolean;
export function buildMismatch(build: { running?: string; head?: string; apiUrl: string }): string | null;
export function mismatchAllowed(environment: Record<string, string | undefined>): boolean;
