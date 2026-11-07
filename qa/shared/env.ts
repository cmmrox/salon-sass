/**
 * Where the product under test is, and where a run writes its evidence. The only place an address,
 * a port, a container or a state folder is written: tests ask this module and never spell one out.
 *
 * Automated QA runs against the QA instance (decision 099, docs/operations/environments-and-releases.md,
 * "The QA instance"), never the default instance that holds the owner's manual testing. Its ports,
 * addresses and key files come from the shell or qa/.env.local, then the QA instance's documented
 * defaults; never from the root .env, which describes the default instance. Anything that would
 * aim a test at the default instance's ports refuses the run before a single request is sent.
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

/**
 * The checkout whose running product is under test: its infra scripts, process records, logs and
 * root .env. QA_PRODUCT_ROOT (shell, then qa/.env.local) when QA runs in its own worktree while the
 * product runs from another checkout; otherwise this checkout.
 */
export const PRODUCT_ROOT = path.resolve(process.env.QA_PRODUCT_ROOT || qaLocal.QA_PRODUCT_ROOT || REPO_ROOT);
const productLocal = readEnvFile(path.join(PRODUCT_ROOT, '.env'));

/** A setting the two instances share (database role names, the platform domain): shell, qa/.env.local, root .env. */
export function setting(name: string): string | undefined {
  return process.env[name] || qaLocal[name] || productLocal[name] || undefined;
}
/** A setting of the QA instance only: shell, then qa/.env.local, never the default instance's root .env. */
function instanceSetting(name: string): string | undefined {
  return process.env[name] || qaLocal[name] || undefined;
}

/** The QA instance (infra/scripts/lib/common.sh, `SALON_LOCAL_INSTANCE=qa`). */
export const INSTANCE = 'qa';
export const QA_DEFAULT_PORTS = { api: 8190, web: 3200, postgres: 5540 } as const;
/** The default instance's ports, which a QA run never uses. */
export const DEFAULT_INSTANCE_PORTS = { api: 8090, web: 3100, postgres: 5440 } as const;

const chosenInstance = process.env.SALON_LOCAL_INSTANCE ?? qaLocal.SALON_LOCAL_INSTANCE;
if (chosenInstance !== undefined && chosenInstance !== INSTANCE) {
  throw new Error(`REFUSED: automated QA runs only against the QA instance (SALON_LOCAL_INSTANCE=qa), not '${chosenInstance || 'default'}'. ` +
    'The default instance holds the owner\'s manual testing (decision 099). Nothing was run.');
}
// Every child (an infra script, a shell check, a browser) inherits the QA instance.
process.env.SALON_LOCAL_INSTANCE = INSTANCE;

/** The QA instance's ports. */
export const ports = {
  api: Number(instanceSetting('SALON_API_PORT') ?? QA_DEFAULT_PORTS.api),
  web: Number(instanceSetting('SALON_WEB_PORT') ?? QA_DEFAULT_PORTS.web),
  postgres: Number(instanceSetting('SALON_POSTGRES_PORT') ?? QA_DEFAULT_PORTS.postgres),
};

export const apiUrl = (instanceSetting('QA_API_URL') ?? `http://localhost:${ports.api}`).replace(/\/+$/, '');
export const webUrl = (instanceSetting('QA_WEB_URL') ?? `http://localhost:${ports.web}`).replace(/\/+$/, '');

function portOf(url: string): number {
  const parsed = new URL(url);
  return Number(parsed.port || (parsed.protocol === 'https:' ? 443 : 80));
}
const defaultPorts: number[] = Object.values(DEFAULT_INSTANCE_PORTS);
const aimed = [
  ['SALON_API_PORT', ports.api], ['SALON_WEB_PORT', ports.web], ['SALON_POSTGRES_PORT', ports.postgres],
  ['QA_API_URL', portOf(apiUrl)], ['QA_WEB_URL', portOf(webUrl)],
] as const;
const refused = aimed.filter(([, port]) => defaultPorts.includes(port));
if (refused.length > 0) {
  throw new Error(`REFUSED: ${refused.map(([name, port]) => `${name} is ${port}`).join(', ')}, a port of the default instance ` +
    `(${defaultPorts.join(', ')}), which holds the owner's manual testing (decision 099). QA runs against the QA instance ` +
    `(${Object.values(QA_DEFAULT_PORTS).join(', ')}). Nothing was run.`);
}
// Children see the same ports this process tests.
process.env.SALON_API_PORT = String(ports.api);
process.env.SALON_WEB_PORT = String(ports.web);
process.env.SALON_POSTGRES_PORT = String(ports.postgres);

/** The QA instance's state folder: process ids, logs, local key files (`.local/qa/`). */
export const STATE_DIR = path.join(PRODUCT_ROOT, '.local', INSTANCE);
export const RUN_DIR = path.join(STATE_DIR, 'run');
export const LOG_DIR = path.join(STATE_DIR, 'logs');
/** The QA instance's PostgreSQL container (Compose project `salon-qa`). */
export const POSTGRES_CONTAINER = 'salon-qa-postgres';
/** A service's log in the QA instance: `api` or `web`. */
export const logFile = (service: string) => path.join(LOG_DIR, `${service}.log`);

const KEY_VARIABLES: Record<string, string> = {
  identity: 'SALON_IDENTITY_LOCAL_KEY_FILE', operator: 'SALON_OPERATOR_LOCAL_KEY_FILE', billing: 'SALON_BILLING_LOCAL_KEY_FILE',
  export: 'SALON_EXPORT_LOCAL_KEY_FILE', support: 'SALON_SUPPORT_LOCAL_KEY_FILE',
};
/** A local key file of the QA instance (`.local/qa/<name>-keys.properties`), or the shell's own choice. */
export function keyFile(name: keyof typeof KEY_VARIABLES | string): string {
  const variable = KEY_VARIABLES[name];
  return (variable && instanceSetting(variable)) || path.join(STATE_DIR, `${name}-keys.properties`);
}

/** The environment for an infra script a test calls: always the QA instance. */
export function productEnv(extra: Record<string, string> = {}): NodeJS.ProcessEnv {
  return { ...process.env, SALON_LOCAL_INSTANCE: INSTANCE, SALON_API_PORT: String(ports.api),
    SALON_WEB_PORT: String(ports.web), SALON_POSTGRES_PORT: String(ports.postgres), ...extra };
}

/** The folder of the run in progress. `qa/scripts/sdlc run` sets QA_REPORT_DIR. */
export const reportDir = process.env.QA_REPORT_DIR ?? path.join(QA_ROOT, 'reports', 'adhoc');
