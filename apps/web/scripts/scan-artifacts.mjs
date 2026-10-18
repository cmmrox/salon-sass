#!/usr/bin/env node
// After `next build`: the built web artifacts (.next/standalone, which the image copies, and
// .next/static) must contain no environment file, no retained key file and no identity or operator
// key value (F03-S01 AC-20; F03-S02 adds the billing provider key, F03-S03 the export key). Every file is read, ignored paths included. Values are compared, never
// printed. Exits non-zero naming only the offending file paths.
//
//   node scripts/scan-artifacts.mjs            scans apps/web/.next
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const KEY_VARIABLES = ['SALON_IDENTITY_SIGNING_KEY', 'SALON_IDENTITY_CHALLENGE_KEY', 'SALON_IDENTITY_ENCRYPTION_KEY', 'SALON_IDENTITY_PROXY_KEY',
  'SALON_OPERATOR_SIGNING_KEY', 'SALON_OPERATOR_CHALLENGE_KEY', 'SALON_OPERATOR_ENCRYPTION_KEY', 'SALON_OPERATOR_PROXY_KEY', 'SALON_OPERATOR_FINGERPRINT_KEY',
  'SALON_BILLING_PROVIDER_KEY', 'SALON_EXPORT_KEY'];

/** Every 64-hex key value known to this machine: configured variables and the retained local key files. */
export function knownKeyValues(environment, keyFiles) {
  const values = new Set();
  for (const name of KEY_VARIABLES) if (/^[a-fA-F0-9]{64}$/.test(environment[name] ?? '')) values.add(environment[name].toLowerCase());
  for (const file of keyFiles) {
    if (!existsSync(file)) continue;
    for (const match of readFileSync(file, 'utf8').matchAll(/^[a-z]+=([a-fA-F0-9]{64})$/gm)) values.add(match[1].toLowerCase());
  }
  return [...values];
}

function files(directory) {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    const stat = statSync(path, { throwIfNoEntry: false });
    if (!stat) return [];
    return stat.isDirectory() ? files(path) : [path];
  });
}

/** The offending files under the given roots: an environment or key file, or one holding a key value. */
export function scanArtifacts(roots, keyValues) {
  const found = [];
  for (const root of roots) {
    if (!existsSync(root)) continue;
    for (const path of files(root)) {
      const name = basename(path);
      if (name === '.env' || name.startsWith('.env.') || /keys\.properties$/.test(name) || /keys\.properties\.lock$/.test(name)) {
        found.push(path);
        continue;
      }
      if (keyValues.length === 0) continue;
      const content = readFileSync(path).toString('latin1').toLowerCase();
      if (keyValues.some((value) => content.includes(value))) found.push(path);
    }
  }
  return found;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const root = resolve(web, '..', '..');
  const keyFiles = [process.env.SALON_IDENTITY_LOCAL_KEY_FILE ?? join(root, '.local', 'identity-keys.properties'),
    process.env.SALON_OPERATOR_LOCAL_KEY_FILE ?? join(root, '.local', 'operator-keys.properties'),
    process.env.SALON_BILLING_LOCAL_KEY_FILE ?? join(root, '.local', 'billing-keys.properties'),
    process.env.SALON_EXPORT_LOCAL_KEY_FILE ?? join(root, '.local', 'export-keys.properties')];
  const roots = [join(web, '.next', 'standalone'), join(web, '.next', 'static')];
  if (!existsSync(roots[0])) {
    console.error('scan-artifacts: no .next/standalone output to scan; run next build first');
    process.exit(2);
  }
  const values = knownKeyValues(process.env, keyFiles);
  const found = scanArtifacts(roots, values);
  if (found.length > 0) {
    console.error(`scan-artifacts: ${found.length} built file(s) hold an environment file, a key file or a key value:`);
    for (const path of found) console.error(`  ${relative(web, path)}`);
    process.exit(1);
  }
  console.log(`scan-artifacts: no environment file, key file or any of ${values.length} known key values in the built artifacts`);
}
