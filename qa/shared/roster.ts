/**
 * The fixed local account roster (decision 099): `infra/local/accounts.json` of the product under
 * test, the one list of people who exist in every local instance. Five staff per synthetic salon (one
 * per role) and one operator per operator role, every password `123456@321`, a fixed TOTP seed for
 * each salon Owner and each operator. A test that needs only "someone in this role" signs in as the
 * roster's account; a test that changes a person adds an extra account (shared/people.ts) instead.
 *
 * Every accessor returns a fresh copy, so a test that edits its copy never edits the roster.
 */
import fs from 'node:fs';
import path from 'node:path';

import { PRODUCT_ROOT } from './env';

export const ROSTER_FILE = path.join(PRODUCT_ROOT, 'infra', 'local', 'accounts.json');
/** The roster's development password: accepted only by the local seed hooks, refused by every product path. */
export const DEVELOPMENT_PASSWORD = '123456@321';

export type RosterRole = 'OWNER' | 'MANAGER' | 'RECEPTIONIST' | 'PROFESSIONAL' | 'ACCOUNTANT';
export type RosterOperatorRole = 'OPERATOR_ADMIN' | 'OPERATOR_SUPPORT';
type StaffEntry = { name: string; email: string; mobile: string; role: RosterRole; password: string; totpSecret?: string };
type OperatorEntry = { name: string; email: string; role: RosterOperatorRole; password: string; totpSecret: string };
type RosterFile = { salons: Record<string, StaffEntry[]>; operators: OperatorEntry[] };

/** A roster staff member in the shape of the identity seed hook (`SeedAccount`), plus the name. */
export type RosterStaff = { name: string; email: string; mobile: string; password: string; role: RosterRole; totpSecret?: string };
/** A roster operator in the shape of the operator seed hook (`OperatorAccount`). */
export type RosterOperator = { email: string; password: string; role: RosterOperatorRole; displayName: string; totpSecret: string };

let loaded: RosterFile | undefined;
function file(): RosterFile {
  if (!loaded) {
    if (!fs.existsSync(ROSTER_FILE)) throw new Error(`ENVIRONMENT: the fixed account roster ${ROSTER_FILE} is not there (decision 099)`);
    loaded = JSON.parse(fs.readFileSync(ROSTER_FILE, 'utf8')) as RosterFile;
    if (!loaded.salons || !Array.isArray(loaded.operators)) throw new Error(`ENVIRONMENT: ${ROSTER_FILE} is not the roster's shape`);
  }
  return loaded;
}

const slugOf = (salon: string | { slug: string }) => (typeof salon === 'string' ? salon : salon.slug);

/** Whether the roster has staff for this salon (alpha, beta and gamma do; a provisioned salon does not). */
export function hasRoster(salon: string | { slug: string }): boolean {
  return Boolean(file().salons[slugOf(salon)]);
}

/** Every roster staff member of one synthetic salon. */
export function rosterStaff(salon: string | { slug: string }): RosterStaff[] {
  const entries = file().salons[slugOf(salon)];
  if (!entries) throw new Error(`ENVIRONMENT: the roster has no staff for salon '${slugOf(salon)}'`);
  return entries.map(({ name, email, mobile, role, password, totpSecret }) =>
    ({ name, email, mobile, password, role, ...(totpSecret ? { totpSecret } : {}) }));
}

/** The roster's one staff member in this role at this salon, e.g. `roster(alpha, 'MANAGER')`. */
export function roster(salon: string | { slug: string }, role: RosterRole): RosterStaff {
  const found = rosterStaff(salon).filter(entry => entry.role === role);
  if (found.length !== 1) throw new Error(`ENVIRONMENT: the roster has ${found.length} ${role} accounts at '${slugOf(salon)}', not one`);
  return found[0];
}

/** Every roster operator. */
export function rosterOperators(): RosterOperator[] {
  return file().operators.map(({ name, email, role, password, totpSecret }) => ({ email, password, role, displayName: name, totpSecret }));
}

/** The roster's operator in this role, e.g. `rosterOperator('OPERATOR_SUPPORT')`. */
export function rosterOperator(role: RosterOperatorRole): RosterOperator {
  const found = rosterOperators().filter(entry => entry.role === role);
  if (found.length !== 1) throw new Error(`ENVIRONMENT: the roster has ${found.length} ${role} operators, not one`);
  return found[0];
}

/** Whether an email belongs to a roster account (staff of any salon, or an operator). */
export function isRosterEmail(email: string): boolean {
  const wanted = email.toLowerCase();
  const roster = file();
  return Object.values(roster.salons).some(list => list.some(entry => entry.email.toLowerCase() === wanted))
    || roster.operators.some(entry => entry.email.toLowerCase() === wanted);
}
