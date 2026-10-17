/**
 * Taking a service of the local environment away and bringing it back, from a Playwright test.
 * Always through the developer's own script, infra/scripts/local-service.sh, and always restored:
 * a test that stops something starts it again, even when the test fails.
 */
import { execFile } from 'node:child_process';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { promisify } from 'node:util';

import { PRODUCT_ROOT, apiUrl, ports } from './env';

const run = promisify(execFile);
const SERVICE_SCRIPT = path.join(PRODUCT_ROOT, 'infra', 'scripts', 'local-service.sh');

export type Service = 'postgres' | 'api' | 'web';

async function service(action: 'start' | 'stop', name: Service): Promise<void> {
  try {
    await run(SERVICE_SCRIPT, [action, name], { cwd: PRODUCT_ROOT, timeout: 180_000 });
  } catch (error) {
    const output = error as { stdout?: string; stderr?: string; message: string };
    throw new Error(
      `ENVIRONMENT: infra/scripts/local-service.sh ${action} ${name} failed: ` +
        `${output.stderr ?? ''}${output.stdout ?? ''}${output.message}`,
    );
  }
}

export async function apiAnswers(): Promise<boolean> {
  try {
    const response = await fetch(`${apiUrl}/actuator/health`, { signal: AbortSignal.timeout(3_000) });
    return response.status === 200;
  } catch {
    return false;
  }
}

function portIsFree(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = net.connect({ port, host: '127.0.0.1' });
    socket.once('connect', () => {
      socket.destroy();
      resolve(false);
    });
    socket.once('error', () => resolve(true));
  });
}

/** Stops the API and proves it is gone: a test of "the API is down" must not run against a live one. */
export async function stopApi(): Promise<void> {
  await service('stop', 'api');
  if (!(await portIsFree(ports.api))) {
    throw new Error(`ENVIRONMENT: the API was stopped but something still listens on port ${ports.api}`);
  }
}

/** Starts the API and proves it answers. */
export async function startApi(): Promise<void> {
  await service('start', 'api');
  if (!(await apiAnswers())) {
    throw new Error('ENVIRONMENT: the API was started but its health does not answer 200');
  }
}

export type StandIn = { requests: () => number; close: () => Promise<void> };

/**
 * Something that is not the API, listening where the web app expects the API. Only ever started
 * while the real API is stopped. `respond` decides what a caller gets; a handler that never
 * answers stands for a service that accepts a connection and hangs.
 */
export async function standInForApi(
  respond: (request: http.IncomingMessage, response: http.ServerResponse) => void,
): Promise<StandIn> {
  if (!(await portIsFree(ports.api))) {
    throw new Error(`ENVIRONMENT: port ${ports.api} is in use; stop the API before standing in for it`);
  }
  let count = 0;
  const sockets = new Set<net.Socket>();
  const server = http.createServer((request, response) => {
    count += 1;
    respond(request, response);
  });
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
  });
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    // Every interface, as the real API listens: the web app reaches it as "localhost".
    server.listen(ports.api, () => resolve());
  });
  return {
    requests: () => count,
    close: () =>
      new Promise<void>((resolve) => {
        sockets.forEach((socket) => socket.destroy());
        server.close(() => resolve());
      }),
  };
}

/** A well-formed answer to GET /api/v1/meta carrying the given service name and version. */
export function metaAnswer(service: string, version: string) {
  return (request: http.IncomingMessage, response: http.ServerResponse): void => {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ service, version, commit: 'unknown', time: new Date().toISOString() }));
  };
}
