// F01-S02, AC-4: under a rush, no response is ever served with another salon's data.
//
// Case: TC-F01-S02-020. "At least 300 requests to GET /api/v1/salon, spread evenly over the three
// salons' hostnames and released together by at least 50 virtual users, each return the salon of
// their own hostname, with zero mismatches and zero errors."
//
// Scenario: 140 virtual users released at the same instant, one burst, no ramp.
//   by_hostname        60 users x 5 requests = 300, each to http://<slug>.localhost:<port>, the
//                      salon changing with every request (100 per salon).
//   on_one_connection  60 users x 5 requests = 300, each user on ONE kept-alive connection to the
//                      API, naming a different salon in the Host header of every request (100 per
//                      salon): what a pooled connection or a reused thread would have to get wrong.
//   no_salon           20 users x 5 requests = 100 on the bare platform domain, between the
//                      others: a request with no salon must never be handed one.
// Data: the three seeded salons, one brand row each. Machine: the local environment.
//
// The invariant is in the thresholds, so k6 exits non-zero when it is broken. A local result is a
// real result for correctness under a race; it says nothing about capacity.
import { check } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import { Counter } from 'k6/metrics';

import { onHostname, PLATFORM_DOMAIN, salons, salonUrl } from '../../shared/tenants.ts';

const API_PORT = __ENV.SALON_API_PORT || '8090';
const API_URL = (__ENV.QA_API_URL || `http://localhost:${API_PORT}`).replace(/\/+$/, '');
const PATH = '/api/v1/salon';
const USERS = 60;
const REQUESTS_EACH = 5;
const PER_SALON = (USERS * REQUESTS_EACH) / salons.length;

// Every request adds to each counter (a one or a zero), so a threshold never passes on no data.
const served = new Counter('served_own_salon');
const mismatches = new Counter('served_another_salon');
const errors = new Counter('errors');
const noSalonRefused = new Counter('no_salon_refused');
const noSalonServed = new Counter('no_salon_was_served_a_salon');

const perSalon = {};
for (const salon of salons) {
  perSalon[`served_own_salon{scenario:by_hostname,salon:${salon.slug}}`] = [`count==${PER_SALON}`];
  perSalon[`served_own_salon{scenario:on_one_connection,salon:${salon.slug}}`] = [`count==${PER_SALON}`];
}

const burst = (exec, vus) => ({ executor: 'per-vu-iterations', exec, vus, iterations: REQUESTS_EACH, maxDuration: '60s', gracefulStop: '0s' });

export const options = {
  scenarios: {
    by_hostname: burst('byHostname', USERS),
    on_one_connection: burst('onOneConnection', USERS),
    no_salon: burst('noSalon', 20),
  },
  thresholds: {
    served_own_salon: [`count==${2 * USERS * REQUESTS_EACH}`], // 600: every request got its own salon
    ...perSalon, // 100 per salon and scenario: spread evenly, and every salon really asked for
    served_another_salon: ['count==0'], // the invariant
    errors: ['count==0'],
    no_salon_refused: ['count==100'],
    no_salon_was_served_a_salon: ['count==0'],
    checks: ['rate==1'],
  },
};

export function setup() {
  // The data exists and each owner can read it, before anything is concluded from the rush.
  for (const salon of salons) {
    const response = http.get(`${salonUrl(salon, API_URL)}${PATH}`);
    if (response.status !== 200 || response.json('name') !== salon.name) {
      throw new Error(`ENVIRONMENT or seed: ${salon.hostname} did not return its own brand before the rush (status ${response.status}, body ${response.body})`);
    }
  }
  // Every user waits for the same instant, so the first requests leave together.
  return { releaseAt: Date.now() + 1500 };
}

function waitForRelease(data) {
  if (__ITER === 0) {
    while (Date.now() < data.releaseAt) {
      // spin: k6's sleep() is too coarse to release 140 users within a millisecond of each other
    }
  }
}

/**
 * Which salon this request asks for. The scenario numbers its requests 0 to 299 as they start,
 * whichever user sends them, so each salon gets exactly a third and a user's kept-alive connection
 * carries whichever salon comes up next.
 */
const turn = () => salons[exec.scenario.iterationInTest % salons.length];

function judge(response, salon) {
  let brand = null;
  try {
    brand = response.json();
  } catch (_) {
    brand = null;
  }
  const own = response.status === 200 && brand !== null && brand.slug === salon.slug && brand.name === salon.name && brand.brandColour === salon.brandColour && brand.timeZone === salon.timeZone && Object.keys(brand).length === 4;
  const body = String(response.body || '');
  const another = salons.some((other) => other !== salon && (body.includes(other.name) || body.includes(other.id) || body.includes(other.brandColour) || body.includes(`"${other.slug}"`)));
  served.add(own ? 1 : 0, { salon: salon.slug });
  mismatches.add(another ? 1 : 0, { salon: salon.slug });
  errors.add(own || another ? 0 : 1, { salon: salon.slug });
  check(response, { 'the salon of the request\'s own hostname': () => own });
  if (!own) {
    console.error(`asked ${salon.hostname}, got ${response.status} ${body.slice(0, 200)}`);
  }
}

export function byHostname(data) {
  waitForRelease(data);
  const salon = turn();
  judge(http.get(`${salonUrl(salon, API_URL)}${PATH}`, { tags: { salon: salon.slug } }), salon);
}

export function onOneConnection(data) {
  waitForRelease(data);
  const salon = turn();
  // One address, so one kept-alive connection per user; the salon is named in the Host header.
  judge(http.get(`${API_URL}${PATH}`, { headers: { Host: `${salon.hostname}:${API_PORT}` }, tags: { salon: salon.slug } }), salon);
}

export function noSalon(data) {
  waitForRelease(data);
  const response = http.get(`${onHostname(API_URL, PLATFORM_DOMAIN)}${PATH}`, { headers: { Host: `${PLATFORM_DOMAIN}:${API_PORT}` }, responseCallback: http.expectedStatuses(404) });
  const body = String(response.body || '');
  const servedSalon = response.status === 200 || salons.some((salon) => body.includes(salon.name) || body.includes(salon.id));
  noSalonRefused.add(response.status === 404 && !servedSalon ? 1 : 0);
  noSalonServed.add(servedSalon ? 1 : 0);
  check(response, { 'no salon for a hostname that names none': () => response.status === 404 && !servedSalon });
}

export function teardown() {
  // Read back afterwards: every salon is still served its own brand.
  for (const salon of salons) {
    const response = http.get(`${salonUrl(salon, API_URL)}${PATH}`);
    if (response.status !== 200 || response.json('slug') !== salon.slug || response.json('name') !== salon.name || response.json('brandColour') !== salon.brandColour) {
      throw new Error(`after the rush ${salon.hostname} no longer returns its own brand: ${response.status} ${response.body}`);
    }
  }
}
