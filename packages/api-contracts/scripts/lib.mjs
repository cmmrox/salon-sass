// The parts of the contract tooling that need no API and no file system layout, so that they can
// be tested on their own. The command line is in contracts.mjs.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import openapiTS, { astToString, COMMENT_HEADER } from 'openapi-typescript';

/** The one file the client is generated into, relative to the generated folder. */
export const SCHEMA_FILE = 'schema.ts';

/**
 * Generates the client's files from an OpenAPI document.
 *
 * @param {string} documentText the OpenAPI document, as JSON text
 * @returns {Promise<Map<string, string>>} file name (relative to the generated folder) to content
 */
export async function renderClient(documentText) {
  const ast = await openapiTS(JSON.parse(documentText));
  return new Map([[SCHEMA_FILE, `${COMMENT_HEADER}${astToString(ast)}`]]);
}

/**
 * Compares what should be in a folder with what is there.
 *
 * @param {Map<string, string>} expected file name to content
 * @param {string} directory the folder that should hold exactly those files
 * @returns {{ file: string, reason: 'missing' | 'different' | 'unexpected' }[]} one entry per stale file
 */
export function findStaleFiles(expected, directory) {
  const stale = [];
  for (const [file, content] of expected) {
    const path = join(directory, file);
    if (!existsSync(path)) {
      stale.push({ file, reason: 'missing' });
    } else if (readFileSync(path, 'utf8') !== content) {
      stale.push({ file, reason: 'different' });
    }
  }
  if (existsSync(directory)) {
    for (const file of readdirSync(directory).sort()) {
      if (!expected.has(file)) {
        stale.push({ file, reason: 'unexpected' });
      }
    }
  }
  return stale;
}

/**
 * Compares the committed OpenAPI document with the one the API's controllers generate.
 *
 * @param {string} generatedText what the controllers generate
 * @param {string} committedPath where the committed document is
 * @returns {'current' | 'missing' | 'different'}
 */
export function compareDocument(generatedText, committedPath) {
  if (!existsSync(committedPath)) {
    return 'missing';
  }
  return readFileSync(committedPath, 'utf8') === generatedText ? 'current' : 'different';
}
