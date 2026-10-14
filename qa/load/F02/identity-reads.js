// TC-F02-004. One bounded local baseline, never production-scale certification.
// Ten concurrent users, no ramp, 20 seconds. Three fixed synthetic salons, five
// staff roles and 50 customers each; audit volume is verified by fixture preparation.
// Preparation authenticates through the real APIs and keeps authority in a 0600
// ignored fixture file. Hashing, delivery and mutations are outside measured reads.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const names = ['principal', 'devices', 'staff', 'audit', 'profile'];
const served = new Counter('identity_reads');
const pool = new Trend('identity_connection_pool_active');
const thresholds = { checks: ['rate==1'] };
for (const name of names) {
  thresholds[`http_req_duration{name:${name}}`] = ['p(95)<300'];
  thresholds[`http_req_failed{name:${name}}`] = ['rate==0'];
  thresholds[`identity_reads{name:${name}}`] = ['count>0'];
}
for (const salon of ['alpha', 'beta', 'gamma']) {
  for (const role of ['OWNER', 'MANAGER', 'RECEPTIONIST', 'PROFESSIONAL', 'ACCOUNTANT', 'customer']) {
    thresholds[`identity_reads{salon:${salon},actor:${role}}`] = ['count>0'];
  }
}
export const options = {
  scenarios: { reads: { executor: 'constant-vus', vus: 10, duration: '20s', gracefulStop: '5s' } },
  thresholds,
};

// Never echo parser errors or the authority-bearing fixture to the test log.
let fixture;
try { fixture = JSON.parse(open(__ENV.F02_LOAD_FIXTURE)); }
catch (_) { throw new Error('ENVIRONMENT: ignored F02 read fixtures are absent or invalid'); }
if (!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(fixture.apiUrl || '')) {
  throw new Error('ENVIRONMENT: the F02 load baseline requires the explicit local API');
}

function parse(response) { try { return response.json(); } catch (_) { return null; } }
function read(salon, actor, name, path, valid) {
  const response = http.get(`${fixture.apiUrl}/api/v1/identity${path}`, {
    headers: { Host: salon.hostname, Authorization: `Bearer ${actor.accessToken}` },
    tags: { name, salon: salon.slug, actor: actor.role || 'customer' },
    redirects: 0,
  });
  const body = parse(response);
  const foreign = fixture.salons.filter(other => other.slug !== salon.slug)
    .flatMap(other => [...other.staff, ...other.customers].map(person => person.id));
  const text = typeof response.body === 'string' ? response.body : '';
  check(response, {
    'successful allowlisted read': () => response.status === 200 && valid(body),
    'no other salon account': () => !foreign.some(id => text.includes(id)),
  });
  served.add(1, { name, salon: salon.slug, actor: actor.role || 'customer' });
}

export function setup() {
  if (!Array.isArray(fixture.salons) || fixture.salons.map(salon => salon.slug).sort().join(',') !== 'alpha,beta,gamma') throw new Error('ENVIRONMENT: three fixed salon fixtures required');
  for (const salon of fixture.salons) {
    if (salon.staff.length !== 5 || salon.customers.length !== 50 || salon.auditRows < 100 ||
        salon.staff.map(actor => actor.role).sort().join(',') !== 'ACCOUNTANT,MANAGER,OWNER,PROFESSIONAL,RECEPTIONIST' ||
        [...salon.staff, ...salon.customers].some(actor => !actor.id || !actor.accessToken || !actor.sessionId)) {
      throw new Error('ENVIRONMENT: F02 fixture volume or authenticated actors differ');
    }
    const owner = salon.staff.find(actor => actor.role === 'OWNER');
    const response = http.get(`${fixture.apiUrl}/api/v1/identity/audit?size=100`, {
      headers: { Host: salon.hostname, Authorization: `Bearer ${owner.accessToken}` },
      tags: { name: 'fixture-audit-verification' }, redirects: 0,
    });
    const body = parse(response);
    if (response.status !== 200 || !body || !Array.isArray(body.items) || body.items.length !== 100 || body.total !== salon.auditRows) {
      throw new Error('ENVIRONMENT: retained audit volume differs from the prepared fixture');
    }
  }
}

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const salon = fixture.salons[iteration % 3];
  // A deterministic sweep covers every role and customer without relying on VU affinity.
  const position = Math.floor(iteration / 3) % 55;
  const actor = position < 5 ? salon.staff[position] : salon.customers[position - 5];
  read(salon, actor, 'principal', '/me', body => body && body.id === actor.id && body.kind === actor.kind && body.role === actor.role);
  read(salon, actor, 'devices', '/sessions?size=100', body => body && body.items.length === 1 && body.items[0].id === actor.sessionId && body.items[0].current === true);
  if (actor.kind === 'customer') {
    read(salon, actor, 'profile', '/customers/me', body => body && body.id === actor.id && body.name === actor.name && body.version === actor.version);
  } else if (actor.role === 'OWNER' || actor.role === 'MANAGER') {
    read(salon, actor, 'staff', '/staff?size=100', body => body && body.items.length === 5 && body.items.every(item => salon.staff.some(person => person.id === item.id)));
    read(salon, actor, 'audit', '/audit?size=100', body => body && body.items.length === 100 && body.total === salon.auditRows);
  }
  if (iteration % 100 === 0) {
    const result = http.get(`${fixture.apiUrl}/actuator/metrics/hikaricp.connections.active`, { tags: { name: 'pool-observation' } });
    const body = parse(result);
    const value = body && body.measurements && body.measurements.find(measurement => measurement.statistic === 'VALUE');
    check(result, { 'connection pool observation exists': () => result.status === 200 && value && Number.isFinite(value.value) });
    if (value && Number.isFinite(value.value)) pool.add(value.value);
  }
}
