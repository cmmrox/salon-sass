// node --test qa/shared/build-identity.test.mjs (run by qa/scripts/sdlc selftest)
import assert from 'node:assert/strict';
import test from 'node:test';

import { ALLOW_MISMATCH, buildMismatch, mismatchAllowed, sameCommit } from './build-identity.mjs';

const HEAD = '7cef6f7d0123456789abcdef0123456789abcdef';

test('a full or abbreviated id of the same commit is the same commit', () => {
  assert.equal(sameCommit(HEAD, HEAD), true);
  assert.equal(sameCommit(HEAD.slice(0, 7), HEAD), true);
  assert.equal(sameCommit(HEAD, HEAD.slice(0, 12)), true);
  assert.equal(sameCommit(HEAD.toUpperCase(), ` ${HEAD}\n`), true);
});

test('another commit, a too-short id, a placeholder or nothing is not', () => {
  assert.equal(sameCommit('0123456789abcdef0123456789abcdef01234567', HEAD), false);
  assert.equal(sameCommit(HEAD.slice(0, 6), HEAD), false);
  assert.equal(sameCommit('unknown', HEAD), false);
  assert.equal(sameCommit(undefined, HEAD), false);
  assert.equal(sameCommit('', ''), false);
  assert.equal(sameCommit('7cef6f7d-dirty', HEAD), false);
});

test('a mismatch names both commits and how to rebuild', () => {
  assert.equal(buildMismatch({ running: HEAD, head: HEAD, apiUrl: 'http://localhost:8190' }), null);
  const message = buildMismatch({ running: '0123456789abcdef', head: HEAD, apiUrl: 'http://localhost:8190' });
  assert.match(message, /built from commit 0123456789abcdef/);
  assert.match(message, new RegExp(`HEAD ${HEAD}`));
  assert.match(message, /infra\/scripts\/qa-redeploy\.sh/);
  assert.match(message, new RegExp(`${ALLOW_MISMATCH}=1`));
  assert.match(buildMismatch({ running: undefined, head: HEAD, apiUrl: 'x' }), /did not say which commit/);
});

test('only QA_ALLOW_BUILD_MISMATCH=1 allows a mismatch', () => {
  assert.equal(mismatchAllowed({ QA_ALLOW_BUILD_MISMATCH: '1' }), true);
  for (const value of [undefined, '', '0', 'true', 'yes']) {
    assert.equal(mismatchAllowed({ QA_ALLOW_BUILD_MISMATCH: value }), false, String(value));
  }
});
