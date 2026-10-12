/**
 * Refuses to start a run against a product that is not there. A test can only fail meaningfully
 * if it could have passed, so a stopped service is reported as what it is, an environment fault,
 * before any test runs.
 */
import { apiUrl, webUrl } from './env';

async function status(url: string): Promise<number | string> {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(10_000), redirect: 'manual' });
    return response.status;
  } catch (error) {
    const cause = (error as { cause?: { code?: string } }).cause;
    return cause?.code ?? (error as Error).name;
  }
}

export default async function globalSetup(): Promise<void> {
  const health = await status(`${apiUrl}/actuator/health`);
  const home = await status(`${webUrl}/`);
  const missing: string[] = [];
  if (health !== 200) {
    missing.push(`the API's health at ${apiUrl}/actuator/health answered ${health}, expected 200`);
  }
  if (home !== 200) {
    missing.push(`the web app at ${webUrl}/ answered ${home}, expected 200`);
  }
  if (missing.length > 0) {
    throw new Error(
      `ENVIRONMENT: the product is not running, so nothing was tested: ${missing.join('; ')}. ` +
        'Start it with infra/scripts/local-up.sh and run again.',
    );
  }
}
