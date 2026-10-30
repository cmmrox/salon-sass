import { writeLog, type LogSink } from './log';
import { serverState } from './server-state';

export type DrainSettings = {
  /** How long the server keeps answering, already not ready, before it stops taking requests. */
  delayMs: number;
  /** How long requests in flight are then given before the process exits anyway. */
  graceMs: number;
  /** Ends the process; `process.exit` outside tests. */
  exit?: (code: number) => void;
  sink?: LogSink;
};

const INSTALLED = Symbol.for('salon.web.drain');

/**
 * The settings from the environment: `SALON_DRAIN_DELAY_SECONDS` (5 by default, long enough for the
 * edge's health check, which runs every second, to take the instance out of its rotation) and
 * `SALON_SHUTDOWN_GRACE_SECONDS` (30, the API's own grace for requests in flight).
 */
export function drainSettingsFromEnvironment(
  environment: Record<string, string | undefined> = process.env,
): DrainSettings {
  return {
    delayMs: seconds(environment.SALON_DRAIN_DELAY_SECONDS, 5) * 1000,
    graceMs: seconds(environment.SALON_SHUTDOWN_GRACE_SECONDS, 30) * 1000,
  };
}

/**
 * How the web app stops when it is served behind the edge: told to stop (`SIGTERM` or `SIGINT`), it
 * stops answering ready at once, keeps answering every request for the drain delay while the edge
 * takes it out of the rotation, then stops taking connections, gives the requests in flight the
 * grace period, and exits. The framework's own handling of those signals, which would stop taking
 * connections at once, is turned off by `NEXT_MANUAL_SIG_HANDLE=true`; without that this does
 * nothing, and whoever started the server stops it (the local `serve.mjs`).
 */
export function installDrain(settings: DrainSettings = drainSettingsFromEnvironment()): void {
  if (process.env.NEXT_MANUAL_SIG_HANDLE !== 'true') {
    return;
  }
  const holder = globalThis as { [INSTALLED]?: boolean };
  if (holder[INSTALLED]) {
    return;
  }
  holder[INSTALLED] = true;
  for (const signal of ['SIGTERM', 'SIGINT'] as const) {
    process.on(signal, () => {
      void drain(settings);
    });
  }
}

/**
 * Stops the server as {@link installDrain} describes, and resolves once it has called `exit`. A
 * second call while one is under way does nothing.
 */
export async function drain({
  delayMs,
  graceMs,
  exit = (code) => process.exit(code),
  sink,
}: DrainSettings): Promise<void> {
  const state = serverState();
  if (state.draining) {
    return;
  }
  state.draining = true;
  writeLog('INFO', `Stopping: not ready; requests are still answered for ${delayMs} ms`, {}, sink);
  await sleep(delayMs);

  for (const server of state.servers) {
    server.close();
    server.closeIdleConnections();
  }
  const settled = await untilIdle(graceMs);
  for (const server of state.servers) {
    server.closeAllConnections();
  }
  writeLog(
    settled ? 'INFO' : 'WARN',
    settled
      ? 'Stopped: every request was answered'
      : `Stopped: ${state.inFlight} request(s) were not answered within ${graceMs} ms`,
    {},
    sink,
  );
  exit(0);
}

function untilIdle(limitMs: number): Promise<boolean> {
  const state = serverState();
  return new Promise((resolve) => {
    if (state.inFlight <= 0) {
      resolve(true);
      return;
    }
    const timer = setTimeout(() => {
      state.onIdle = undefined;
      resolve(false);
    }, limitMs);
    state.onIdle = () => {
      if (state.inFlight <= 0) {
        clearTimeout(timer);
        state.onIdle = undefined;
        resolve(true);
      }
    };
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function seconds(value: string | undefined, fallback: number): number {
  const parsed = value === undefined || value.trim() === '' ? NaN : Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
}
