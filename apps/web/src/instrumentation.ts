/**
 * Runs once when the server starts, before it handles a request (Next.js's instrumentation hook).
 * On the server it makes every log line a JSON object, logs one line per request with its trace and
 * its salon, and, behind the edge, lets the server drain before it stops
 * (docs/engineering/web.md; NFR-16).
 */
export async function register(): Promise<void> {
  if (process.env.NEXT_RUNTIME !== 'nodejs') {
    return;
  }
  const { writeConsoleAsJson } = await import('./lib/observability/log');
  const { installRequestLog } = await import('./lib/observability/request-log');
  const { installDrain } = await import('./lib/observability/drain');
  writeConsoleAsJson();
  installRequestLog();
  installDrain();
}
