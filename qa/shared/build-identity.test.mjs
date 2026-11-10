// node --test qa/shared/build-identity.test.mjs (run by qa/scripts/sdlc selftest)
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  ALLOW_MISMATCH,
  BUILD_SOURCES_FILE,
  buildMismatch,
  buildSources,
  mismatchAllowed,
  staleBuild,
} from './build-identity.mjs';

const REPOSITORY = join(dirname(fileURLToPath(import.meta.url)), '..', '..');

/** A repository with the real source list, an API source file and a QA test file. */
function repository() {
  const root = mkdtempSync(join(tmpdir(), 'build-identity-'));
  const git = (...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' }).trim();
  git('init', '-q', '-b', 'main');
  git('config', 'user.email', 'qa@example.invalid');
  git('config', 'user.name', 'Tooling QA');
  const write = (path, text) => {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), text);
  };
  write(BUILD_SOURCES_FILE, readFileSync(join(REPOSITORY, BUILD_SOURCES_FILE), 'utf8'));
  write('apps/api/Api.java', 'class Api {}\n');
  write('qa/api/F07/x.spec.ts', "test('x', () => {});\n");
  const commit = (message) => {
    git('add', '.');
    git('commit', '-q', '-m', message);
    return git('rev-parse', 'HEAD');
  };
  return { root, git, write, commit, cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

test('the source list is the one qa-redeploy.sh reads, and names the API and web sources', () => {
  const sources = buildSources(REPOSITORY);
  assert.ok(sources.includes('apps') && sources.includes('packages') && sources.includes('pnpm-lock.yaml'));
  assert.match(readFileSync(join(REPOSITORY, 'infra/scripts/qa-redeploy.sh'), 'utf8'), /build-sources\.txt/);
});

test('a build is current at HEAD, and after commits that change only tests or records', (t) => {
  const repo = repository();
  t.after(repo.cleanup);
  const built = repo.commit('build');
  assert.equal(staleBuild({ running: built, root: repo.root }), null);
  assert.equal(staleBuild({ running: built.slice(0, 7).toUpperCase(), root: repo.root }), null);
  repo.write('qa/api/F07/x.spec.ts', "test('x, repaired', () => {});\n");
  repo.write('features/F07/bugs/BUG-F07-001.md', '---\nid: BUG-F07-001\n---\n');
  repo.commit('test: repair a test and file a bug');
  assert.equal(staleBuild({ running: built, root: repo.root }), null);
});

test('a build is stale once HEAD changes a build source, or when it is not behind HEAD', (t) => {
  const repo = repository();
  t.after(repo.cleanup);
  const built = repo.commit('build');
  repo.write('apps/api/Api.java', 'class Api { int fixed; }\n');
  const fixed = repo.commit('fix: the API');
  assert.match(staleBuild({ running: built, root: repo.root }), /changed the build's sources/);
  // A change undone again leaves the sources as built: still current.
  repo.write('apps/api/Api.java', 'class Api {}\n');
  repo.commit('revert: the API');
  assert.equal(staleBuild({ running: built, root: repo.root }), null);
  // A build of another line of work is not an ancestor of HEAD.
  repo.git('checkout', '-q', '-b', 'other', fixed);
  repo.write('qa/api/F07/other.spec.ts', 'x\n');
  const other = repo.commit('other');
  repo.git('checkout', '-q', 'main');
  assert.match(staleBuild({ running: other, root: repo.root }), /not an ancestor/);
});

test('an unknown commit, a placeholder or no answer is stale', (t) => {
  const repo = repository();
  t.after(repo.cleanup);
  repo.commit('build');
  assert.match(staleBuild({ running: '0123456789abcdef0123456789abcdef01234567', root: repo.root }), /not a commit/);
  for (const running of [undefined, '', 'unknown', 'abc12', '7cef6f7d-dirty']) {
    assert.match(staleBuild({ running, root: repo.root }), /did not say which commit/, String(running));
  }
});

test('the refusal says why, names HEAD, and says how to rebuild or override', () => {
  const message = buildMismatch({ reason: 'did not say which commit it was built from', head: 'abc', apiUrl: 'http://localhost:8190' });
  assert.match(message, /http:\/\/localhost:8190 did not say/);
  assert.match(message, /HEAD abc/);
  assert.match(message, /infra\/scripts\/qa-redeploy\.sh/);
  assert.match(message, new RegExp(`${ALLOW_MISMATCH}=1`));
});

test('only QA_ALLOW_BUILD_MISMATCH=1 allows a build that is not current', () => {
  assert.equal(mismatchAllowed({ QA_ALLOW_BUILD_MISMATCH: '1' }), true);
  for (const value of [undefined, '', '0', 'true', 'yes']) {
    assert.equal(mismatchAllowed({ QA_ALLOW_BUILD_MISMATCH: value }), false, String(value));
  }
});
