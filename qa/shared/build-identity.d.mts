export const ALLOW_MISMATCH: 'QA_ALLOW_BUILD_MISMATCH';
export const BUILD_SOURCES_FILE: string;
export function buildSources(root: string): string[];
export function staleBuild(build: { running?: string; root: string }): string | null;
export function buildMismatch(build: { reason: string; head?: string; apiUrl: string }): string;
export function mismatchAllowed(environment: Record<string, string | undefined>): boolean;
