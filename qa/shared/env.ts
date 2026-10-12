/**
 * Where the product under test is, and where a run writes its evidence. The only place an address
 * or a port is written: tests ask this module and never spell one out.
 *
 * Order: a variable in the shell, then qa/.env.local, then the root .env the product's own scripts
 * read, then the product's documented defaults.
 */
import fs from 'node:fs';
import path from 'node:path';

export const QA_ROOT = path.resolve(__dirname, '..');
export const REPO_ROOT = path.resolve(QA_ROOT, '..');

function readEnvFile(file: string): Record<string, string> {
  if (!fs.existsSync(file)) {
    return {};
  }
  const values: Record<string, string> = {};
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$/.exec(line);
    if (match && !line.trimStart().startsWith('#')) {
      values[match[1]] = match[2].replace(/^"(.*)"$/, '$1');
    }
  }
  return values;
}

const qaLocal = readEnvFile(path.join(QA_ROOT, '.env.local'));
const productLocal = readEnvFile(path.join(REPO_ROOT, '.env'));

function setting(name: string): string | undefined {
  return process.env[name] || qaLocal[name] || productLocal[name] || undefined;
}

/** The product's documented local ports (README.md, .env.example). */
export const ports = {
  api: Number(setting('SALON_API_PORT') ?? 8090),
  web: Number(setting('SALON_WEB_PORT') ?? 3100),
  postgres: Number(setting('SALON_POSTGRES_PORT') ?? 5440),
};

export const apiUrl = (setting('QA_API_URL') ?? `http://localhost:${ports.api}`).replace(/\/+$/, '');
export const webUrl = (setting('QA_WEB_URL') ?? `http://localhost:${ports.web}`).replace(/\/+$/, '');

/** The folder of the run in progress. `qa/scripts/sdlc run` sets QA_REPORT_DIR. */
export const reportDir = process.env.QA_REPORT_DIR ?? path.join(QA_ROOT, 'reports', 'adhoc');
