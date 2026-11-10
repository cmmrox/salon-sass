/**
 * F07-S02 AC-11 and NFR-03: the availability read under load, in the release suite only, run when the owner asks
 * (decision 068). Never part of a build, a fix or a re-verification, and not run during F07's build.
 *
 * Preparation makes AC-11's salon (20 eligible professionals, each with a break, a block and 15 bookings that day) and two
 * lighter salons, through the real APIs, writes a Receptionist's credentials for each to a 0600 file under the ignored
 * qa/.auth/, runs qa/load/F07/S02-availability.js (whose thresholds hold the target: p95 under 500 ms) and removes the
 * file whatever happens. The salons are the operator fixture's, removed after the test. The result is a baseline of this
 * machine, recorded with the machine and the data volume; it is not a certification of NFR-03.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';

import { bookingSalon, holdInput, plusMinutes, staffAt, type BookingSalon } from '../../shared/booking';
import { serviceInput } from '../../shared/catalogue';
import { QA_ROOT, apiUrl, reportDir } from '../../shared/env';
import { plusDays } from '../../shared/salon-hours';
import { Absences, blockInput } from '../../shared/team-leave';
import { Schedules, brk, weekOf } from '../../shared/team-schedule';
import { test, expect } from '../../shared/operator';

const execute = promisify(execFile);

/** Fifteen 20-minute bookings that day for each professional, around a 13:00 break and a 15:00 block. */
const SLOTS = [...Array.from({ length: 12 }, (_, n) => plusMinutes('09:00', 20 * n)), '14:00', '14:20', '14:40'];

async function busyDay(s: BookingSalon, date: string) {
  const trim = await s.catalogue.create(s.salon, s.owner.tokens, serviceInput(s.category.id, { name: 'Fringe trim', durationMinutes: 15, bufferMinutes: 5 }));
  const schedules = new Schedules(s.team);
  const absences = new Absences(s.team);
  let bookings = 0;
  for (const m of s.members) {
    const current = await s.team.get(s.salon, s.owner.tokens, m.id);
    await s.team.save(s.salon, s.owner.tokens, current, { services: { categoryIds: [], serviceIds: [s.service.id, trim.id] } });
    await schedules.save(s.salon, s.owner.tokens, m.id, weekOf('09:00', '19:00', [], { monday: { breaks: [brk('13:00', '13:30')] }, tuesday: { breaks: [brk('13:00', '13:30')] },
      wednesday: { breaks: [brk('13:00', '13:30')] }, thursday: { breaks: [brk('13:00', '13:30')] }, friday: { breaks: [brk('13:00', '13:30')] },
      saturday: { breaks: [brk('13:00', '13:30')] }, sunday: { breaks: [brk('13:00', '13:30')] } }));
    await absences.addBlock(s.salon, s.owner.tokens, m.id, blockInput(date, { starts: '15:00', finishes: '15:30' }));
    for (const time of SLOTS) {
      const hold = await s.bookings.place(s.salon, s.owner.tokens, holdInput(m.id, trim.id, date, time));
      await s.bookings.confirm(s.salon, s.owner.tokens, hold.id);
      bookings++;
    }
  }
  return bookings;
}

test('TC-F07-S02-018 AC-11, NFR-03: with 20 eligible professionals each with a break, a block and 15 bookings that day, the availability read answers in under 500 ms at the 95th percentile for ten concurrent users over a minute, across three salons, failing under 1%', async ({ identity, operator, clock }) => {
  test.setTimeout(30 * 60_000);
  let k6Version: string;
  try { k6Version = (await execute('k6', ['version'])).stdout.trim(); }
  catch { throw new Error('ENVIRONMENT: k6 is required for the F07 availability load scenario (docs/qa/automation.md)'); }
  const busy = await bookingSalon(identity, operator, clock, { people: 20 });
  const date = plusDays(busy.today, 3);
  const bookingsThatDay = await busyDay(busy, date);
  const light = [await bookingSalon(identity, operator, clock, { people: 2, keepClock: true }), await bookingSalon(identity, operator, clock, { people: 2, keepClock: true })];
  for (const s of light) for (const time of ['10:00', '14:00']) await s.bookings.place(s.salon, s.owner.tokens, holdInput(s.members[0].id, s.service.id, date, time));
  const describe = async (s: BookingSalon, kind: string, count: number) => ({
    kind, hostname: s.salon.hostname, serviceId: s.service.id, date, memberIds: s.members.map(m => m.id), bookingsThatDay: count,
    accessToken: (await staffAt(s, 'RECEPTIONIST')).tokens.accessToken,
  });
  const fixture = { apiUrl, salons: [await describe(busy, 'busy', bookingsThatDay), ...await Promise.all(light.map(s => describe(s, 'light', 2)))] };
  const directory = path.join(QA_ROOT, '.auth');
  fs.mkdirSync(directory, { recursive: true });
  const file = path.join(directory, `f07-availability-${randomUUID()}.json`);
  const summary = path.join(reportDir, 'F07-S02-availability-k6-summary.json');
  try {
    fs.writeFileSync(file, JSON.stringify(fixture), { mode: 0o600, flag: 'wx' });
    expect(fs.statSync(file).mode & 0o777, 'credentials readable by this user only').toBe(0o600);
    test.info().annotations.push({ type: 'baseline', description: `${os.cpus()[0]?.model ?? 'unknown CPU'} x${os.cpus().length}, ${Math.round(os.totalmem() / 2 ** 30)} GB, ${k6Version}; busy salon 20 professionals, ${bookingsThatDay} bookings that day; two light salons` });
    const run = await execute('k6', ['run', '--quiet', '--summary-export', summary, path.join(QA_ROOT, 'load', 'F07', 'S02-availability.js')],
      { env: { ...process.env, F07_LOAD_FIXTURE: file }, maxBuffer: 16 * 1024 * 1024 }).then(() => 0, (error: { code?: number }) => error.code ?? 1);
    expect(run, `k6 met every threshold (summary ${summary})`).toBe(0);
  } finally {
    fs.rmSync(file, { force: true });
  }
});
