#!/usr/bin/env node
// Reads the fixed local account roster (infra/local/accounts.json, decision 105) for
// infra/scripts/local-accounts.sh. Sourced by nothing else and never part of a deployed build.
//
//   node accounts.mjs <roster> salons            the salons' slugs, one per line
//   node accounts.mjs <roster> staff <slug>      the body of POST /local/identity/seed for that salon
//   node accounts.mjs <roster> operators         the body of POST /local/operator/seed, keeping existing accounts
//   node accounts.mjs <roster> keys              one line per authenticator: its title, a tab, its otpauth:// link
//
// A request body carries the roster's passwords and TOTP seeds, so it is written to standard output for curl to
// read from a pipe, never put on a command line. A roster that is not as expected is refused with exit status 2.
import { readFileSync } from 'node:fs';

const [file, command, slug] = process.argv.slice(2);

function refuse(message) {
  console.error(`accounts.mjs: ${file}: ${message}`);
  process.exit(2);
}

let roster;
try {
  roster = JSON.parse(readFileSync(file, 'utf8'));
} catch (error) {
  refuse(`cannot be read as JSON (${error.message})`);
}

const STAFF_ROLES = ['OWNER', 'MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT'];
const OPERATOR_ROLES = ['OPERATOR_ADMIN', 'OPERATOR_SUPPORT'];
const SEED = /^[A-Z2-7]{32}$/;

function check(account, where, roles) {
  for (const field of ['name', 'email', 'role', 'password']) {
    if (typeof account[field] !== 'string' || account[field] === '') refuse(`${where} has no ${field}`);
  }
  if (!roles.includes(account.role)) refuse(`${where} has the role '${account.role}', not one of ${roles.join(', ')}`);
  if (account.totpSecret !== undefined && !SEED.test(account.totpSecret))
    refuse(`${where} has a totpSecret that is not 32 base32 characters`);
}

const salons = roster.salons ?? {};
const operators = roster.operators ?? [];
for (const [name, staff] of Object.entries(salons)) {
  if (!Array.isArray(staff) || staff.length === 0) refuse(`salon '${name}' has no staff`);
  staff.forEach((account, i) => check(account, `salons.${name}[${i}]`, STAFF_ROLES));
}
if (!Array.isArray(operators)) refuse('operators is not a list');
operators.forEach((account, i) => check(account, `operators[${i}]`, OPERATOR_ROLES));

function link(issuer, account) {
  const label = encodeURIComponent(`${issuer}:${account.email}`).replace('%3A', ':');
  return `otpauth://totp/${label}?secret=${account.totpSecret}&issuer=${encodeURIComponent(issuer)}&algorithm=SHA1&digits=6&period=30`;
}

const write = (value) => process.stdout.write(`${typeof value === 'string' ? value : JSON.stringify(value)}\n`);

switch (command) {
  case 'salons':
    write(Object.keys(salons).join('\n'));
    break;
  case 'staff': {
    const staff = salons[slug];
    if (!staff) refuse(`has no salon '${slug}'`);
    write({
      accounts: staff.map((account) => ({
        email: account.email,
        ...(account.mobile ? { mobile: account.mobile } : {}),
        password: account.password,
        role: account.role,
        ...(account.totpSecret ? { totpSecret: account.totpSecret } : {}),
      })),
    });
    break;
  }
  case 'operators':
    write({
      keepExisting: true,
      accounts: operators.map((account) => ({
        email: account.email,
        displayName: account.name,
        role: account.role,
        password: account.password,
        totpSecret: account.totpSecret ?? null,
      })),
    });
    break;
  case 'keys': {
    const staffIssuer = roster.issuers?.staff ?? 'Salon local';
    const operatorIssuer = roster.issuers?.operators ?? 'Salon Console local';
    const lines = [];
    for (const [name, staff] of Object.entries(salons)) {
      for (const account of staff.filter((a) => a.totpSecret)) {
        const issuer = `${staffIssuer} ${name}`;
        lines.push(`${name} ${account.role.toLowerCase()}, ${account.name} <${account.email}>\t${link(issuer, account)}`);
      }
    }
    for (const account of operators.filter((a) => a.totpSecret)) {
      const role = account.role.replace('OPERATOR_', '').toLowerCase();
      lines.push(`console ${role}, ${account.name} <${account.email}>\t${link(operatorIssuer, account)}`);
    }
    write(lines.join('\n'));
    break;
  }
  default:
    refuse(`unknown command '${command ?? ''}': salons, staff <slug>, operators or keys`);
}
