// Whether the product under test was built from the commit being tested. Playwright's global setup
// (global-setup.ts) refuses a run when it was not; infra/scripts/qa-redeploy.sh rebuilds the QA
// instance at HEAD and makes the same comparison. Pure functions, so they are tested on their own
// (build-identity.test.mjs, run by qa/scripts/sdlc selftest).

/** Set to 1 to run against a build of another commit on purpose; the run then says so. */
export const ALLOW_MISMATCH = 'QA_ALLOW_BUILD_MISMATCH';

const HEX = /^[0-9a-f]+$/;

/** Two commit ids name one commit: equal, or one an abbreviation (7 or more hex digits) of the other. */
export function sameCommit(a, b) {
  const x = String(a ?? '').trim().toLowerCase();
  const y = String(b ?? '').trim().toLowerCase();
  if (x.length < 7 || y.length < 7 || !HEX.test(x) || !HEX.test(y)) {
    return false;
  }
  return x.startsWith(y) || y.startsWith(x);
}

/**
 * Why a run must not start against this build, or null when the build is the tested commit.
 * running is /api/v1/meta's commit (undefined when it did not answer); head is the checkout's HEAD.
 */
export function buildMismatch({ running, head, apiUrl }) {
  if (sameCommit(running, head)) {
    return null;
  }
  const built = running ? `was built from commit ${running}` : 'did not say which commit it was built from';
  return `the API at ${apiUrl} ${built}, but this checkout is at HEAD ${head || '(unknown)'}, so the run ` +
    'would not test this commit. Rebuild the QA instance at HEAD with infra/scripts/qa-redeploy.sh and run ' +
    `again, or set ${ALLOW_MISMATCH}=1 to test that build on purpose and say so in the evidence`;
}

/** Whether the environment asks to run against a mismatched build anyway. */
export function mismatchAllowed(environment) {
  return environment[ALLOW_MISMATCH] === '1';
}
