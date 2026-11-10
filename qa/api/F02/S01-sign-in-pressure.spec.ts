/**
 * F02-S01 staff sign-in under pressure: a burst of sign-ins on some salons while another salon is used, and a sign-in
 * whose rate bucket the security cleanup job removes while it is being counted.
 *
 * Expected results: F02-S01 AC-1 (right, wrong, unknown and removed credentials; the attempt limits), NFR-02 (one salon's
 * traffic never stalls another's), decision 120 (a password is checked outside any transaction, a few at a time; one
 * beyond the cap waits up to two seconds and is answered 503 service_unavailable, which a client may try again), and the
 * bugs these cases were written for: BUG-F02-016 (the check held a pooled connection and the salon's lock, so a burst
 * stalled every salon) and BUG-F02-017 (a bucket removed mid-count answered 500).
 *
 * TC-F02-S01-031 restarts the API with SALON_IDENTITY_PASSWORD_CHECKS_CONCURRENT=1, a one-processor host (the setting's
 * default is the processors the JVM sees), so that a burst a test can send without k6 queues past the two seconds; the
 * API is started again on its defaults afterwards. TC-F02-S01-032 runs, in a database session of its own, the statement
 * the cleanup job runs and holds it open across a sign-in, which is the interleaving the job produces on its own.
 */
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';

import type { APIResponse } from '@playwright/test';

import { expectNoInternals, expectProblem, answerOf } from '../../shared/answers';
import { POSTGRES_CONTAINER } from '../../shared/env';
import { test, expect, alpha, beta, gamma, problem, type Identity, type SeedAccount } from '../../shared/identity';
import { localService } from '../../shared/identity-runtime';

const id = (n: number) => `TC-F02-S01-${String(n).padStart(3, '0')}`;
const ATTEMPTS_PER_ADDRESS = 30; // F02-S01: 30 sign-in attempts per address per salon in 15 minutes
// A read of another salon that waits this long is waiting behind the burst. Measured on this machine with this burst: before
// BUG-F02-016's fix the slowest read of beta took 1,452 ms (qa/reports/20261110-114836-F02-S01-0559e0156b39), after it 188 ms
// (qa/reports/20261110-115357-F02-S01-d6efa279ebc8); the pool's own wait before a 503 is two seconds.
const STALLED_MS = 1_000;

/** The API restarted with these settings for `work`, then on its defaults again. */
async function apiWith<T>(identity: Identity, settings: Record<string, string>, work: () => Promise<T>): Promise<T> {
  localService('stop', 'api');
  try {
    localService('start', 'api', settings);
    await identity.api.reconnect();
    return await work();
  } finally {
    localService('stop', 'api');
    localService('start', 'api');
    await identity.api.reconnect();
  }
}

type Sent = { label: string; salon: typeof alpha; identifier: string; password: string; expect: 'authenticated' | 'invalid_credentials' };
const signInOf = (identity: Identity, sent: Sent) =>
  identity.call(sent.salon, 'POST', '/staff/sign-in', { identifier: sent.identifier, password: sent.password, deviceLabel: 'QA burst' });

/** What a sign-in answered, as a word: its state, or its problem code. */
async function outcome(response: APIResponse): Promise<{ status: number; word: string; body: string }> {
  const answer = await answerOf(response);
  if (answer.status === 200) return { status: 200, word: (JSON.parse(answer.body) as { state: string }).state, body: '' };
  const p = expectProblem(answer, `sign-in answered ${answer.status}`);
  expectNoInternals(answer, `sign-in answered ${answer.status}`);
  return { status: answer.status, word: p.code, body: answer.body.slice(0, 200) };
}

test(`${id(31)} AC-1: a burst of staff sign-ins on two salons leaves a third salon's signed-in requests answered at once; each sign-in is answered as F02-S01 says, or 503 service_unavailable when the password checks are full, and the same request tried again is then answered as it should be`, async ({ identity }) => {
  test.setTimeout(240_000);
  await apiWith(identity, { SALON_IDENTITY_PASSWORD_CHECKS_CONCURRENT: '1' }, async () => {
    // Beta is the salon that must not notice: its Manager is signed in before anything starts.
    const probe = await identity.member(beta, 'MANAGER');
    // Alpha: the right password, a wrong one, and a removed account, each sent last so they queue behind the burst.
    const manager = await identity.member(alpha, 'MANAGER');
    const owner = await identity.member(alpha, 'OWNER');
    const removed = await identity.extra(alpha, 'RECEPTIONIST');
    expect((await identity.call(alpha, 'DELETE', `/staff/${removed.principal.id}`, undefined, owner.tokens)).status(), 'the account is removed').toBe(204);
    const named: Sent[] = [
      { label: 'the right password', salon: alpha, identifier: manager.account.email, password: manager.account.password, expect: 'authenticated' },
      { label: 'a wrong password', salon: alpha, identifier: manager.account.email, password: 'QA wrong password value', expect: 'invalid_credentials' },
      { label: 'a removed account', salon: alpha, identifier: removed.account.email, password: removed.account.password, expect: 'invalid_credentials' },
    ];
    // Alpha has used 3 of its address's 30 attempts above; the burst leaves 7 for the tries again below. Gamma gets 33:
    // 30 are counted and checked, 3 are refused 429 by the limit before any check.
    const unknown = (salon: typeof alpha, n: number): Sent[] => Array.from({ length: n }, (_, k) => ({
      label: `${salon.slug} unknown ${k}`, salon, identifier: `qa-burst-${randomUUID().slice(0, 8)}@example.invalid`, password: 'QA wrong password value', expect: 'invalid_credentials' as const }));
    const burst = [...unknown(alpha, 17), ...unknown(gamma, ATTEMPTS_PER_ADDRESS + 3)];

    // Beta's Manager reads their own account every 100 ms while the burst is in flight.
    const reads: { status: number; ms: number }[] = [];
    let inFlight = true;
    const probing = (async () => {
      while (inFlight) {
        const started = Date.now();
        const r = await identity.call(beta, 'GET', '/me', undefined, probe.tokens);
        reads.push({ status: r.status(), ms: Date.now() - started });
        await new Promise(resolve => setTimeout(resolve, 100));
      }
    })();
    const first = burst.map(sent => signInOf(identity, sent));
    await new Promise(resolve => setTimeout(resolve, 50));
    const last = named.map(sent => signInOf(identity, sent));
    const answers = await Promise.all([...first, ...last].map(r => r.then(outcome)));
    inFlight = false;
    await probing;
    const sent = [...burst, ...named];
    const tally = answers.reduce<Record<string, number>>((all, a) => ({ ...all, [`${a.status} ${a.word}`]: (all[`${a.status} ${a.word}`] ?? 0) + 1 }), {});
    const summary = JSON.stringify({ tally, reads: reads.length, slowestReadMs: Math.max(...reads.map(r => r.ms)), readsNot200: reads.filter(r => r.status !== 200).length });
    test.info().annotations.push({ type: 'burst', description: summary });
    console.log(`burst: ${summary}`);
    // Beta never noticed: every read answered 200, and none waited behind the burst.
    expect(reads.length, 'beta was read while the burst was in flight').toBeGreaterThan(3);
    expect(reads.filter(r => r.status !== 200), 'every read of beta answered 200').toEqual([]);
    expect(Math.max(...reads.map(r => r.ms)), 'no read of beta waited behind the burst').toBeLessThan(STALLED_MS);

    expect(answers.filter(a => a.status >= 500 && a.status !== 503), 'no answer is a 500 or another server error').toEqual([]);
    for (const [n, a] of answers.entries()) {
      const allowed = [`200 authenticated`, `401 invalid_credentials`, `429 rate_limited`, `503 service_unavailable`];
      expect(allowed, `${sent[n].label}: ${a.status} ${a.word} ${a.body}`).toContain(`${a.status} ${a.word}`);
      if (a.status === 200 || a.status === 401) expect(a.word, `${sent[n].label}: answered as its credential says`).toBe(sent[n].expect);
    }
    // The limits hold under the burst: gamma's address is refused exactly beyond its 30 attempts, alpha's is never reached.
    const of = (salon: typeof alpha, word: string) => answers.filter((a, n) => sent[n].salon === salon && a.word === word).length;
    expect(of(gamma, 'rate_limited'), 'gamma: exactly the attempts beyond 30 are refused 429').toBe(3);
    expect(of(alpha, 'rate_limited'), 'alpha: under its limit, nothing is refused 429').toBe(0);
    expect(answers.filter(a => a.status === 503).length, 'with one check at a time, the burst queues past two seconds: some are 503').toBeGreaterThan(0);

    // A 503 is an honest "try again": the same request sent again is answered as its credential says.
    for (const [n, a] of answers.entries()) {
      if (a.status !== 503 || !named.includes(sent[n])) continue;
      const again = await outcome(await signInOf(identity, sent[n]));
      expect(`${again.status} ${again.word}`, `${sent[n].label}, tried again after its 503`).toBe(sent[n].expect === 'authenticated' ? '200 authenticated' : '401 invalid_credentials');
    }
    // And after the burst each named credential is answered as F02-S01 says.
    for (const credential of named) {
      const after = await outcome(await signInOf(identity, credential));
      expect(`${after.status} ${after.word}`, `${credential.label}, after the burst`).toBe(credential.expect === 'authenticated' ? '200 authenticated' : '401 invalid_credentials');
    }
  });
});

/** The identity cleanup job's DELETE of a salon's staff buckets, held open for `holdMs` in a session of its own. */
function heldCleanup(salonId: string, holdMs: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', ['exec', '-i', POSTGRES_CONTAINER, 'psql', '-U', process.env.SALON_DB_USER ?? 'salon', '-d', process.env.SALON_DB_NAME ?? 'salon', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'], { stdio: ['pipe', 'pipe', 'pipe'] });
    let out = '';
    child.stdout.on('data', chunk => { out += String(chunk); });
    child.stderr.on('data', chunk => { out += String(chunk); });
    child.on('error', reject);
    child.on('close', code => (code === 0 ? resolve(out.trim()) : reject(new Error(`ENVIRONMENT: the held cleanup session failed: ${out}`))));
    // ExpiredSecurityState.removeStaffRateBucketsStartedBy, for every bucket rather than those whose window has ended,
    // because the buckets here were made a moment ago; RETURNING says how many it held.
    child.stdin.end(`BEGIN;\nWITH gone AS (DELETE FROM identity_rate_bucket WHERE tenant_id = '${salonId}' AND dimension LIKE 'staff-%' RETURNING 1) SELECT count(*) FROM gone;\nSELECT pg_sleep(${holdMs / 1000});\nCOMMIT;\n`);
  });
}

test(`${id(32)} AC-1: a sign-in with the right password whose rate buckets the security cleanup job removes while the attempt is being counted is answered 200 with a session, not 500`, async ({ identity }) => {
  const manager = await identity.member(alpha, 'MANAGER');
  const account: SeedAccount = manager.account;
  // The first sign-in (above) made alpha's identifier and address buckets for this account.
  const cleanup = heldCleanup(alpha.id, 2_000);
  await new Promise(resolve => setTimeout(resolve, 400));
  const started = Date.now();
  const response = await identity.call(alpha, 'POST', '/staff/sign-in', { identifier: account.email, password: account.password, deviceLabel: 'QA cleanup race' });
  const took = Date.now() - started;
  const held = await cleanup;
  expect(held.split('\n')[0], 'the cleanup removed the two buckets the sign-in counts against').toBe('2');
  const answer = await answerOf(response);
  expect(answer.status, `the sign-in during the cleanup (body ${answer.body.slice(0, 200)})`).toBe(200);
  expect((JSON.parse(answer.body) as { state: string }).state).toBe('authenticated');
  test.info().annotations.push({ type: 'interleaving', description: `the sign-in took ${took} ms while the cleanup held its delete` });
  // Afterwards the limits count from a fresh window: the account signs in again, and a wrong password is a plain 401.
  expect((await identity.signIn(alpha, account)).state).toBe('authenticated');
  await problem(await identity.call(alpha, 'POST', '/staff/sign-in', { identifier: account.email, password: 'QA wrong password value', deviceLabel: 'QA' }), 401, 'invalid_credentials');
});
