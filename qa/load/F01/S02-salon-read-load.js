// F01-S02: the salon's brand under steady traffic. A baseline, not a certification.
//
// Case: TC-F01-S02-021. Every page of a salon's site will begin with this read, so it is held to
// NFR-03's target for reads other than availability search: under 300 ms at the 95th percentile.
//
// Scenario: 30 virtual users, no ramp, 20 seconds, each asking as fast as it is answered, the
// salon changing with every request, a third of the traffic to each salon's hostname.
// Data: the three seeded salons, one brand row each, so the tables are as good as empty: the
// number says what the request costs, not what 2,000 salons cost (NFR-04).
// Machine: the developer's own, with the API, the database and k6 sharing it.
//
// A local result is a baseline to compare the next run with. Certifying NFR-03 needs a staging
// environment of known size (F01-S05). What this run does settle is that sustained mixed traffic
// never returns another salon's brand and never fails: those are in the thresholds with the target.
import { check } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import { Counter } from 'k6/metrics';

import { salons, salonUrl } from '../../shared/tenants.ts';

const API_PORT = __ENV.SALON_API_PORT || '8090';
const API_URL = (__ENV.QA_API_URL || `http://localhost:${API_PORT}`).replace(/\/+$/, '');
const PATH = '/api/v1/salon';

const served = new Counter('served_own_salon');
const mismatches = new Counter('served_another_salon');
const errors = new Counter('errors');

const eachSalonServed = {};
for (const salon of salons) {
  eachSalonServed[`served_own_salon{salon:${salon.slug}}`] = ['count>=1000'];
}

export const options = {
  scenarios: {
    steady: { executor: 'constant-vus', vus: 30, duration: '20s', gracefulStop: '5s' },
  },
  thresholds: {
    'http_req_duration{name:salon}': ['p(95)<300'], // NFR-03, as a local baseline
    'http_req_failed{name:salon}': ['rate==0'],
    served_another_salon: ['count==0'],
    errors: ['count==0'],
    ...eachSalonServed, // real traffic reached every salon: "fast and correct" over nothing proves nothing
    checks: ['rate==1'],
  },
};

export function setup() {
  for (const salon of salons) {
    const response = http.get(`${salonUrl(salon, API_URL)}${PATH}`);
    if (response.status !== 200 || response.json('name') !== salon.name) {
      throw new Error(`ENVIRONMENT or seed: ${salon.hostname} did not return its own brand before the load (status ${response.status}, body ${response.body})`);
    }
  }
}

export default function () {
  const salon = salons[exec.scenario.iterationInTest % salons.length];
  const response = http.get(`${salonUrl(salon, API_URL)}${PATH}`, { tags: { name: 'salon', salon: salon.slug } });
  let brand = null;
  try {
    brand = response.json();
  } catch (_) {
    brand = null;
  }
  const own = response.status === 200 && brand !== null && brand.slug === salon.slug && brand.name === salon.name && brand.brandColour === salon.brandColour;
  const body = String(response.body || '');
  const another = salons.some((other) => other !== salon && (body.includes(other.name) || body.includes(other.id) || body.includes(other.brandColour)));
  served.add(own ? 1 : 0, { salon: salon.slug });
  mismatches.add(another ? 1 : 0, { salon: salon.slug });
  errors.add(own || another ? 0 : 1, { salon: salon.slug });
  check(response, { 'the salon of the request\'s own hostname': () => own });
}
