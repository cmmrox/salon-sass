/**
 * The local product's API and web logs (infra/scripts: `.local/logs/<service>.log`), read from an
 * offset so a test searches only what it caused. Read-only.
 */
import fs from 'node:fs';
import path from 'node:path';

import { PRODUCT_ROOT } from './env';

const SERVICES = ['api', 'web'] as const;
const file = (service: string) => path.join(PRODUCT_ROOT, '.local', 'logs', `${service}.log`);

export type LogOffsets = Record<(typeof SERVICES)[number], number>;

export function logOffsets(): LogOffsets {
  const offsets = {} as LogOffsets;
  for (const service of SERVICES) {
    if (!fs.existsSync(file(service))) throw new Error(`ENVIRONMENT: the local ${service} log ${file(service)} is not there`);
    offsets[service] = fs.statSync(file(service)).size;
  }
  return offsets;
}

/** Everything the API and web wrote since the offsets; non-empty, or the search proves nothing. */
export function logsSince(offsets: LogOffsets): string {
  let text = '';
  for (const service of SERVICES) {
    const buffer = fs.readFileSync(file(service));
    text += buffer.subarray(Math.min(offsets[service], buffer.length)).toString('utf8');
  }
  return text;
}
