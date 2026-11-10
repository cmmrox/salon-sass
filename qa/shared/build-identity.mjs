// Whether the product under test is a build of what is being tested. `qa/scripts/sdlc run` checks
// it once before a run, and Playwright's global setup (global-setup.ts) again when a test file is
// run directly; infra/scripts/qa-redeploy.sh rebuilds the QA instance and applies the same rule.
// Tested by build-identity.test.mjs, which qa/scripts/sdlc selftest runs.
//
// The rule: the API is current when the commit it reports (/api/v1/meta) holds every change HEAD
// has to the build's sources (infra/scripts/lib/build-sources.txt). That commit is an ancestor
// of HEAD, and `git diff --quiet <reported> HEAD -- <sources>` finds nothing. So a commit that
// changes only tests, bug files or records needs no rebuild.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/** Set to 1 to run against a build that is not current on purpose; the run then says so. */
export const ALLOW_MISMATCH = 'QA_ALLOW_BUILD_MISMATCH';
/** The one list of the build's sources, relative to the repository root. */
export const BUILD_SOURCES_FILE = 'infra/scripts/lib/build-sources.txt';

const HEX = /^[0-9a-f]+$/;

/** The build's sources, from BUILD_SOURCES_FILE in this repository. */
export function buildSources(root) {
  return readFileSync(join(root, BUILD_SOURCES_FILE), 'utf8')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'));
}

/** Runs git in root; answers whether it exited 0. */
function gitSucceeds(root, args) {
  try {
    execFileSync('git', ['-C', root, ...args], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

/**
 * Why the build the API reports is not current for HEAD in root, or null when it is.
 * running is /api/v1/meta's commit (undefined when it did not answer with one).
 */
export function staleBuild({ running, root }) {
  const commit = String(running ?? '').trim().toLowerCase();
  if (commit.length < 7 || !HEX.test(commit)) {
    return 'did not say which commit it was built from';
  }
  if (!gitSucceeds(root, ['cat-file', '-e', `${commit}^{commit}`])) {
    return `was built from commit ${commit}, which is not a commit of this repository`;
  }
  if (!gitSucceeds(root, ['merge-base', '--is-ancestor', commit, 'HEAD'])) {
    return `was built from commit ${commit}, which is not an ancestor of this checkout's HEAD`;
  }
  if (!gitSucceeds(root, ['diff', '--quiet', commit, 'HEAD', '--', ...buildSources(root)])) {
    return `was built from commit ${commit}, and HEAD has changed the build's sources (${BUILD_SOURCES_FILE}) since`;
  }
  return null;
}

/** The refusal for a build that is not current: why, and what to do. */
export function buildMismatch({ reason, head, apiUrl }) {
  return `the API at ${apiUrl} ${reason}, so the run would not test this checkout's HEAD ${head || '(unknown)'}. ` +
    'Rebuild the QA instance with infra/scripts/qa-redeploy.sh and run again, or set ' +
    `${ALLOW_MISMATCH}=1 to test that build on purpose and say so in the evidence`;
}

/** Whether the environment asks to run against a build that is not current anyway. */
export function mismatchAllowed(environment) {
  return environment[ALLOW_MISMATCH] === '1';
}
