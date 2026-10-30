#!/usr/bin/env node
// The API contract: one OpenAPI document generated from the API's controllers, and one TypeScript
// client generated from that document. Both are committed; neither is edited by hand.
//
//   node packages/api-contracts/scripts/contracts.mjs generate   rewrite both from the controllers
//   node packages/api-contracts/scripts/contracts.mjs check      fail when either is stale
//
// Run through `pnpm contracts:generate` and `pnpm contracts:check` at the repository root.
// Both start the API's document test, so they need what the API build needs: JDK 25 and Docker.
import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { compareDocument, findStaleFiles, renderClient } from './lib.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const apiDirectory = join(root, 'apps', 'api');
const exportedDocument = join(apiDirectory, 'target', 'openapi.json');
const committedDocument = join(root, 'docs', 'api', 'openapi.json');
const generatedDirectory = join(root, 'packages', 'api-contracts', 'src', 'generated');

const shown = (path) => relative(root, path).split('\\').join('/');
const say = (message) => console.log(`contracts: ${message}`);

/** Runs the API's document test in one of its modes and returns the document it generated. */
function documentFromControllers(mode) {
  say("generating the OpenAPI document from the API's controllers");
  rmSync(exportedDocument, { force: true });
  const maven = process.platform === 'win32' ? 'mvnw.cmd' : './mvnw';
  const result = spawnSync(
    maven,
    ['-q', 'test', '-Dtest=OpenApiDocumentTest', `-Dcontracts.mode=${mode}`, '-Dsurefire.failIfNoSpecifiedTests=false'],
    { cwd: apiDirectory, encoding: 'utf8', shell: process.platform === 'win32' },
  );
  if (result.error || result.status !== 0) {
    process.stderr.write(`${result.stdout ?? ''}${result.stderr ?? ''}`);
    if (result.error) {
      console.error(`contracts: could not run ${maven} in ${shown(apiDirectory)}: ${result.error.message}`);
    }
    console.error('contracts: the API could not generate its OpenAPI document. The output above says why.');
    process.exit(2);
  }
  return readFileSync(exportedDocument, 'utf8');
}

async function generate() {
  const document = documentFromControllers('update');
  say(`wrote ${shown(committedDocument)}`);
  const files = await renderClient(document);
  rmSync(generatedDirectory, { recursive: true, force: true });
  mkdirSync(generatedDirectory, { recursive: true });
  for (const [file, content] of files) {
    writeFileSync(join(generatedDirectory, file), content, 'utf8');
    say(`wrote ${shown(join(generatedDirectory, file))}`);
  }
}

async function check() {
  const document = documentFromControllers('export');
  const stale = [];

  const documentState = compareDocument(document, committedDocument);
  if (documentState !== 'current') {
    stale.push(
      `${shown(committedDocument)} (${documentState === 'missing' ? 'missing' : "not what the API's controllers generate"})`,
    );
  }

  // The client is compared with what the controllers' document generates, not with the committed
  // document, so that a stale document and a stale client are each named for what they are.
  const reasons = {
    missing: 'missing',
    different: 'not what the OpenAPI document generates',
    unexpected: 'not a generated file; this folder holds only generated files',
  };
  for (const { file, reason } of findStaleFiles(await renderClient(document), generatedDirectory)) {
    stale.push(`${shown(join(generatedDirectory, file))} (${reasons[reason]})`);
  }

  if (stale.length > 0) {
    for (const entry of stale) {
      console.error(`contracts: STALE ${entry}`);
    }
    console.error('contracts: run `pnpm contracts:generate` and commit the result.');
    process.exit(1);
  }
  say(`${shown(committedDocument)} and ${shown(generatedDirectory)} are current`);
}

const command = process.argv[2];
if (command === 'generate') {
  await generate();
} else if (command === 'check') {
  await check();
} else {
  console.error('usage: contracts.mjs <generate|check>');
  process.exit(64);
}
