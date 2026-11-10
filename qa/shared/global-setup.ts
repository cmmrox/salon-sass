/**
 * Refuses to start a run against a product that is not there, or not the build of the commit
 * under test. A test can only fail meaningfully if it could have passed, so a stopped service is
 * reported as what it is, an environment fault, before any test runs; and a pass is evidence for
 * the checkout's HEAD only if the API under test is a current build of it: its /api/v1/meta
 * commit holds every change HEAD has to the build's sources (build-identity.mjs).
 */
import { execFileSync } from 'node:child_process';

import { ALLOW_MISMATCH, buildMismatch, mismatchAllowed, staleBuild } from './build-identity.mjs';
import { apiUrl, REPO_ROOT, webUrl } from './env';

async function status(url: string): Promise<number | string> {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(10_000), redirect: 'manual' });
    return response.status;
  } catch (error) {
    const cause = (error as { cause?: { code?: string } }).cause;
    return cause?.code ?? (error as Error).name;
  }
}

/** The commit /api/v1/meta reports, or undefined when it does not answer with one. */
async function runningCommit(): Promise<string | undefined> {
  try {
    const response = await fetch(`${apiUrl}/api/v1/meta`, { signal: AbortSignal.timeout(10_000) });
    const body = (await response.json()) as { commit?: unknown };
    return typeof body.commit === 'string' ? body.commit : undefined;
  } catch {
    return undefined;
  }
}

function checkoutHead(): string | undefined {
  try {
    return execFileSync('git', ['-C', REPO_ROOT, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
  } catch {
    return undefined;
  }
}

export default async function globalSetup(): Promise<void> {
  const health = await status(`${apiUrl}/actuator/health`);
  const home = await status(`${webUrl}/`);
  const missing: string[] = [];
  if (health !== 200) {
    missing.push(`the API's health at ${apiUrl}/actuator/health answered ${health}, expected 200`);
  }
  if (home !== 200) {
    missing.push(`the web app at ${webUrl}/ answered ${home}, expected 200`);
  }
  if (missing.length > 0) {
    throw new Error(
      `ENVIRONMENT: the product is not running, so nothing was tested: ${missing.join('; ')}. ` +
        'Start it with infra/scripts/local-up.sh and run again.',
    );
  }
  // `sdlc run` checks this once before its first entry; this is the same check for a test file run
  // directly with Playwright.
  const reason = staleBuild({ running: await runningCommit(), root: REPO_ROOT });
  const mismatch = reason ? buildMismatch({ reason, head: checkoutHead(), apiUrl }) : null;
  if (mismatch && mismatchAllowed(process.env)) {
    console.warn(`BUILD MISMATCH ALLOWED (${ALLOW_MISMATCH}=1): ${mismatch}`);
  } else if (mismatch) {
    throw new Error(`ENVIRONMENT: nothing was tested: ${mismatch}.`);
  }
}
