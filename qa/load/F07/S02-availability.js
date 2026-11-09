// TC-F07-S02-018 AC-11, NFR-03: the scenario its preparation (qa/api/F07/S02-availability-load.spec.ts) runs.
// Release suite only; run when the owner asks (decision 068).
//
// Scenario: 10 virtual users, no ramp, 60 seconds, each request one availability read (GET /api/v1/bookings/availability)
// on a salon's own host as its Receptionist. Data: three salons the preparation makes. The busy one is AC-11's salon:
// 20 eligible professionals, each with a break, a block and 15 bookings that day; reads are "No preference" and single
// professionals on that day, alternating. Two lighter salons (2 professionals, a few bookings) share the load, as real
// traffic would. Target (docs/qa/test-plan.md, NFR-03): p95 under 500 ms, under 1% failed. A local result is a baseline
// of this machine, not a certification of NFR-03; the preparation records the machine and the volume beside it.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const reads = new Counter('availability_reads');
export const options = {
  scenarios: { reads: { executor: 'constant-vus', vus: 10, duration: '60s', gracefulStop: '5s' } },
  thresholds: {
    'http_req_duration{name:availability}': ['p(95)<500'],
    'http_req_duration{name:availability,salon:busy}': ['p(95)<500'],
    'http_req_failed{name:availability}': ['rate<0.01'],
    'availability_reads{salon:busy}': ['count>0'],
    'availability_reads{salon:light}': ['count>0'],
    checks: ['rate==1'],
  },
};

// The fixture holds Receptionist credentials: never echo it, nor a parse error of it.
let fixture;
try { fixture = JSON.parse(open(__ENV.F07_LOAD_FIXTURE)); }
catch (_) { throw new Error('ENVIRONMENT: the ignored F07 availability fixture is absent or invalid'); }
if (!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(fixture.apiUrl || '')) throw new Error('ENVIRONMENT: the F07 load scenario runs against the local API only');

export function setup() {
  if (!Array.isArray(fixture.salons) || fixture.salons.filter(s => s.kind === 'busy').length !== 1 || fixture.salons.length < 3) {
    throw new Error('ENVIRONMENT: one busy and at least two light salons are required');
  }
  const busy = fixture.salons.find(s => s.kind === 'busy');
  if (busy.memberIds.length !== 20 || busy.bookingsThatDay !== 300) throw new Error('ENVIRONMENT: the busy salon needs 20 professionals with 15 bookings each that day');
}

export default function () {
  const n = Math.floor(Math.random() * 10);
  const salon = n < 6 ? fixture.salons.find(s => s.kind === 'busy') : fixture.salons.filter(s => s.kind === 'light')[n % 2];
  const member = n % 2 === 0 ? '' : `&memberId=${salon.memberIds[Math.floor(Math.random() * salon.memberIds.length)]}`;
  const response = http.get(`${fixture.apiUrl}/api/v1/bookings/availability?serviceId=${salon.serviceId}&date=${salon.date}${member}`, {
    headers: { Host: salon.hostname, Authorization: `Bearer ${salon.accessToken}` },
    tags: { name: 'availability', salon: salon.kind },
    redirects: 0,
  });
  let body = null;
  try { body = response.json(); } catch (_) { body = null; }
  check(response, {
    'an answer of times': () => response.status === 200 && body !== null && Array.isArray(body.times) && body.date === salon.date,
    'only this salon\'s professionals': () => body === null || body.times.every(t => salon.memberIds.includes(t.member.id)),
  });
  reads.add(1, { salon: salon.kind });
}
